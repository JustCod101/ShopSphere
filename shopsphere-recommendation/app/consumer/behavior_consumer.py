"""MQ 行为消费者（T4.1 Part E）。

订阅 `q.reco.behavior`，双绑：
- `shopsphere.behavior` rk=`user.behavior` —— User 服务行为埋点
- `shopsphere.order`    rk=`order.created` —— Order 服务下单事件（展开为 N 条强行为信号）

幂等：依赖 `behavior_event.event_id` 唯一索引 + MySQL `INSERT ... ON DUPLICATE KEY UPDATE`（no-op）。
失败语义：JSON / 反序列化失败 → reject(requeue=False) 进 DLX；DB 失败 → nack(requeue=False) 进 DLX。
不依赖容器重试 —— 任务约束「失败 nack requeue=False 进 DLX」。
"""
from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

import aio_pika
from aio_pika.abc import AbstractRobustChannel, AbstractRobustConnection
from pydantic import ValidationError
from redis.asyncio import Redis
from sqlalchemy import Engine, text

from app.core.config import AppSettings
from app.schemas.events import OrderCreatedEvent, UserBehaviorEvent

logger = logging.getLogger(__name__)

# 拓扑常量（与 docs/mq-topology.md 一致）
EXCHANGE_BEHAVIOR = "shopsphere.behavior"
EXCHANGE_ORDER = "shopsphere.order"
EXCHANGE_RECO_DLX = "shopsphere.reco.dlx"
QUEUE_BEHAVIOR = "q.reco.behavior"
QUEUE_BEHAVIOR_DLQ = "q.reco.behavior.dlq"
RK_USER_BEHAVIOR = "user.behavior"
RK_ORDER_CREATED = "order.created"

PREFETCH = 20
REDIS_USER_BEHAVIOR_LIMIT = 200  # 在线召回只看每用户最近 N 条
SOURCE_BEHAVIOR = "behavior"
SOURCE_ORDER = "order"


@dataclass
class BehaviorRow:
    """与 behavior_event 表字段对齐的纯数据载体（落库 / Redis 缓存共用）。"""

    event_id: str
    user_id: int
    item_id: int
    action_type: str
    source: str
    ts: datetime  # tz-aware UTC


def _to_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def normalize_user_behavior(payload: dict) -> list[BehaviorRow]:
    """单条 user.behavior → 1 行。"""
    evt = UserBehaviorEvent.model_validate(payload)
    return [
        BehaviorRow(
            event_id=evt.eventId,
            user_id=evt.userId,
            item_id=evt.itemId,
            action_type=evt.actionType,
            source=SOURCE_BEHAVIOR,
            ts=_to_utc(evt.ts),
        )
    ]


def normalize_order_created(payload: dict) -> list[BehaviorRow]:
    """单条 order.created → N 行（每 item 一条，eventId 派生为 order-{orderId}-{productId}）。

    幂等键派生规则（用户拍板）：`f"order-{orderId}-{productId}"` —— 确定性，重投不引入新行。
    """
    evt = OrderCreatedEvent.model_validate(payload)
    ts = _to_utc(evt.ts)
    return [
        BehaviorRow(
            event_id=f"order-{evt.orderId}-{item.productId}",
            user_id=evt.userId,
            item_id=item.productId,
            action_type="order",
            source=SOURCE_ORDER,
            ts=ts,
        )
        for item in evt.items
    ]


class BehaviorConsumer:
    def __init__(self, settings: AppSettings, db_engine: Engine, redis: Redis) -> None:
        self.settings = settings
        self.db_engine = db_engine
        self.redis = redis
        self._conn: Optional[AbstractRobustConnection] = None
        self._channel: Optional[AbstractRobustChannel] = None
        self._consumer_tag: Optional[str] = None
        self._queue: Optional[aio_pika.abc.AbstractRobustQueue] = None

    # ---------- 拓扑声明 + 启停 ----------

    async def start(self) -> None:
        url = self.settings.rabbitmq.amqp_url()
        self._conn = await aio_pika.connect_robust(url)
        self._channel = await self._conn.channel()
        await self._channel.set_qos(prefetch_count=PREFETCH)

        # 主交换机（生产方主声明，本端幂等重声明）
        ex_behavior = await self._channel.declare_exchange(
            EXCHANGE_BEHAVIOR, type=aio_pika.ExchangeType.TOPIC, durable=True
        )
        ex_order = await self._channel.declare_exchange(
            EXCHANGE_ORDER, type=aio_pika.ExchangeType.TOPIC, durable=True
        )
        ex_dlx = await self._channel.declare_exchange(
            EXCHANGE_RECO_DLX, type=aio_pika.ExchangeType.FANOUT, durable=True
        )

        # 死信队列（无消费者，人工运维）
        dlq = await self._channel.declare_queue(QUEUE_BEHAVIOR_DLQ, durable=True)
        await dlq.bind(ex_dlx)

        # 主消费队列（durable + DLX 指向 fanout dlx）
        queue = await self._channel.declare_queue(
            QUEUE_BEHAVIOR,
            durable=True,
            arguments={"x-dead-letter-exchange": EXCHANGE_RECO_DLX},
        )
        await queue.bind(ex_behavior, routing_key=RK_USER_BEHAVIOR)
        await queue.bind(ex_order, routing_key=RK_ORDER_CREATED)

        self._queue = queue
        self._consumer_tag = await queue.consume(self._on_message, no_ack=False)
        logger.info("BehaviorConsumer started: queue=%s", QUEUE_BEHAVIOR)

    async def stop(self) -> None:
        if self._queue and self._consumer_tag:
            try:
                await self._queue.cancel(self._consumer_tag)
            except Exception:  # noqa: BLE001
                logger.exception("cancel consumer failed")
        if self._channel and not self._channel.is_closed:
            try:
                await self._channel.close()
            except Exception:  # noqa: BLE001
                logger.exception("channel close failed")
        if self._conn and not self._conn.is_closed:
            try:
                await self._conn.close()
            except Exception:  # noqa: BLE001
                logger.exception("connection close failed")
        logger.info("BehaviorConsumer stopped")

    # ---------- 消息处理 ----------

    async def _on_message(self, message: aio_pika.abc.AbstractIncomingMessage) -> None:
        routing_key = message.routing_key or ""
        body = message.body
        try:
            payload = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            logger.warning("invalid JSON on rk=%s: %s; size=%d", routing_key, e, len(body))
            await message.reject(requeue=False)
            return

        try:
            if routing_key == RK_USER_BEHAVIOR:
                rows = normalize_user_behavior(payload)
            elif routing_key == RK_ORDER_CREATED:
                rows = normalize_order_created(payload)
            else:
                logger.warning("unknown routing_key=%s; rejecting to DLX", routing_key)
                await message.reject(requeue=False)
                return
        except ValidationError as e:
            logger.warning("schema validation failed rk=%s: %s", routing_key, e.errors())
            await message.reject(requeue=False)
            return
        except Exception:  # noqa: BLE001
            logger.exception("normalize failed rk=%s", routing_key)
            await message.reject(requeue=False)
            return

        if not rows:
            await message.ack()
            return

        try:
            await asyncio.get_running_loop().run_in_executor(None, self._persist, rows)
            await self._cache(rows)
        except Exception:  # noqa: BLE001
            # DB/Redis 失败：nack 进 DLX，不 requeue（任务约束）
            logger.exception("persist/cache failed rk=%s rows=%d", routing_key, len(rows))
            await message.nack(requeue=False)
            return

        await message.ack()

    # ---------- DB 同步落库（executor 中执行）----------

    def _persist(self, rows: list[BehaviorRow]) -> None:
        """`INSERT ... ON DUPLICATE KEY UPDATE event_id=event_id` 即等价 ON CONFLICT DO NOTHING。"""
        sql = text(
            """
            INSERT INTO behavior_event
                (event_id, user_id, item_id, action_type, source, ts)
            VALUES (:event_id, :user_id, :item_id, :action_type, :source, :ts)
            ON DUPLICATE KEY UPDATE event_id = event_id
            """
        )
        params = [
            {
                "event_id": r.event_id,
                "user_id": r.user_id,
                "item_id": r.item_id,
                "action_type": r.action_type,
                "source": r.source,
                "ts": r.ts,
            }
            for r in rows
        ]
        with self.db_engine.begin() as conn:
            conn.execute(sql, params)

    # ---------- Redis 在线特征（T4.3 召回用）----------

    async def _cache(self, rows: list[BehaviorRow]) -> None:
        """ZADD user:behavior:{userId} score=ts_epoch_ms member=itemId；截到最近 N 条。"""
        pipe = self.redis.pipeline()
        # 按 user_id 聚合，减少 ZADD 次数
        per_user: dict[int, list[tuple[float, str]]] = {}
        for r in rows:
            score = r.ts.timestamp() * 1000.0
            per_user.setdefault(r.user_id, []).append((score, str(r.item_id)))
        for user_id, members in per_user.items():
            key = f"user:behavior:{user_id}"
            mapping = {member: score for score, member in members}  # 同 member 取最新 ts
            pipe.zadd(key, mapping)
            # 保留 score 最高（最近）的 N 条，超出从低分端裁剪
            pipe.zremrangebyrank(key, 0, -(REDIS_USER_BEHAVIOR_LIMIT + 1))
        await pipe.execute()
