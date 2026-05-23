"""在线召回 —— T4.3。

两条路径:
  - recall_for_user(): 取 user:behavior:{uid} 最近 N 个 item → pipeline 查 sim:item:* →
    1/(1+idx) 衰减加权聚合 → 过滤已下单 → Top-K。命中缓存直返。
  - recall_similar(): 直读 sim:item:{itemId} Top-K。

冷启动 / 模型未就绪 / 邻居全空 一律回退 hot:items:global + fallback=true(C1 拍板)。
5001/5002 仅作日志埋点("reco.cold_start" / "reco.model_not_ready"),不进 Result.code。
"""
from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional

from redis.asyncio import Redis as AsyncRedis
from sqlalchemy import text
from sqlalchemy.engine import Engine

from app.service.itemcf import KEY_HOT_GLOBAL, KEY_MODEL_READY, KEY_SIM_PREFIX

RECENT_ITEMS_LIMIT = 20
PURCHASED_CACHE_TTL = 1800
PURCHASED_LOOKBACK_DAYS = 90
SIM_NEIGHBOR_TOPN = 50
USER_CACHE_TTL = 600
USER_CACHE_TTL_SHORT = 60

metrics_logger = logging.getLogger("reco.metrics")


@dataclass
class RecallResult:
    items: list[dict]
    fallback: bool
    reason: Optional[str] = None


def cache_key(user_id: int, topk: int) -> str:
    return f"rec:user:{user_id}:topk:{topk}"


def purchased_key(user_id: int) -> str:
    return f"reco:purchased:user:{user_id}"


async def _fallback_hot(redis: AsyncRedis, topk: int, reason: str) -> RecallResult:
    raw = await redis.zrevrange(KEY_HOT_GLOBAL, 0, topk - 1, withscores=True)
    items = [{"itemId": int(m), "score": float(s)} for m, s in raw]
    return RecallResult(items=items, fallback=True, reason=reason)


async def _store_cache(redis: AsyncRedis, key: str, result: RecallResult, ttl: int) -> None:
    payload = json.dumps({"items": result.items, "fallback": result.fallback})
    await redis.set(key, payload, ex=ttl)


async def recall_for_user(
    redis: AsyncRedis, engine: Engine, *, user_id: int, topk: int
) -> RecallResult:
    ck = cache_key(user_id, topk)
    cached = await redis.get(ck)
    if cached:
        data = json.loads(cached)
        return RecallResult(items=data["items"], fallback=data["fallback"], reason=None)

    if not await redis.exists(KEY_MODEL_READY):
        metrics_logger.info(
            "reco.model_not_ready",
            extra={"event": "reco.model_not_ready", "userId": user_id, "topk": topk},
        )
        result = await _fallback_hot(redis, topk, reason="model-not-ready")
        await _store_cache(redis, ck, result, USER_CACHE_TTL_SHORT)
        return result

    recent = await redis.zrevrange(f"user:behavior:{user_id}", 0, RECENT_ITEMS_LIMIT - 1)
    if not recent:
        metrics_logger.info(
            "reco.cold_start",
            extra={
                "event": "reco.cold_start", "userId": user_id, "topk": topk,
                "reason": "no-behavior",
            },
        )
        result = await _fallback_hot(redis, topk, reason="cold-start")
        await _store_cache(redis, ck, result, USER_CACHE_TTL)
        return result

    pipe = redis.pipeline(transaction=False)
    for item_id_str in recent:
        pipe.zrevrange(
            f"{KEY_SIM_PREFIX}{item_id_str}", 0, SIM_NEIGHBOR_TOPN - 1, withscores=True
        )
    neighbor_lists = await pipe.execute()

    scores: dict[str, float] = {}
    for idx, neighbors in enumerate(neighbor_lists):
        w = 1.0 / (1.0 + idx)
        for nb_id, sim_score in neighbors:
            scores[nb_id] = scores.get(nb_id, 0.0) + w * float(sim_score)

    purchased = await load_purchased_cached(redis, engine, user_id)
    if purchased:
        scores = {k: v for k, v in scores.items() if int(k) not in purchased}

    if not scores:
        metrics_logger.info(
            "reco.empty_neighbors",
            extra={"event": "reco.empty_neighbors", "userId": user_id, "topk": topk},
        )
        result = await _fallback_hot(redis, topk, reason="empty-neighbors")
        await _store_cache(redis, ck, result, USER_CACHE_TTL)
        return result

    # score 降序;score 相同时按 itemId 升序,保证同输入同输出稳定
    sorted_items = sorted(scores.items(), key=lambda kv: (-kv[1], int(kv[0])))[:topk]
    items = [{"itemId": int(k), "score": float(v)} for k, v in sorted_items]
    result = RecallResult(items=items, fallback=False, reason=None)
    await _store_cache(redis, ck, result, USER_CACHE_TTL)
    return result


async def recall_similar(
    redis: AsyncRedis, *, item_id: int, topk: int
) -> RecallResult:
    raw = await redis.zrevrange(f"{KEY_SIM_PREFIX}{item_id}", 0, topk - 1, withscores=True)
    if not raw:
        return await _fallback_hot(redis, topk, reason="empty-neighbors")
    items = [{"itemId": int(m), "score": float(s)} for m, s in raw]
    return RecallResult(items=items, fallback=False, reason=None)


async def load_purchased_cached(
    redis: AsyncRedis, engine: Engine, user_id: int
) -> set[int]:
    key = purchased_key(user_id)
    cached = await redis.smembers(key)
    if cached:
        return {int(x) for x in cached}
    loop = asyncio.get_running_loop()
    ids = await loop.run_in_executor(None, _load_purchased_sync, engine, user_id)
    if ids:
        pipe = redis.pipeline(transaction=False)
        pipe.sadd(key, *[str(i) for i in ids])
        pipe.expire(key, PURCHASED_CACHE_TTL)
        await pipe.execute()
    return set(ids)


def _load_purchased_sync(engine: Engine, user_id: int) -> list[int]:
    sql = text(
        "SELECT DISTINCT item_id FROM behavior_event "
        "WHERE user_id = :uid AND action_type = 'order' AND ts >= :since"
    )
    since = datetime.now(timezone.utc) - timedelta(days=PURCHASED_LOOKBACK_DAYS)
    with engine.connect() as conn:
        rows = conn.execute(sql, {"uid": user_id, "since": since}).all()
    return [int(r[0]) for r in rows]
