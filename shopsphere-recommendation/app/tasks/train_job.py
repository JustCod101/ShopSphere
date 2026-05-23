"""TrainJob —— ItemCF 训练编排（T4.2）。

职责:
- 异步触发(fire-and-forget):`/internal/recommend/train` 与 cron 共用入口。
- 互斥锁(Redis SET NX EX 3600):跨进程防并发训练。
- 状态机:t_train_log RUNNING → SUCCESS / FAILED。
- 异常隔离:executor 内捕获一切,落 t_train_log,绝不抛崩 FastAPI。

并发抑制三层(任务约束 + Redis NX 锁)：
- AsyncIOScheduler max_instances=1(单进程内已防)
- coalesce=True(挤压错过的触发)
- Redis NX 锁(多副本部署下唯一兜底)
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Any, Callable

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from pytz import utc
from redis import Redis as SyncRedis
from redis.asyncio import Redis as AsyncRedis
from sqlalchemy import Engine, text

from app.core.config import AppSettings
from app.service.itemcf import TrainResult, train_itemcf

logger = logging.getLogger(__name__)

LOCK_KEY = "reco:training"  # SET NX EX 3600(1h 超时,够 10w 训练 + 缓冲)
LOCK_TTL_SECONDS = 3600

JOB_ID = "itemcf_train"


class TrainJob:
    """触发与生命周期管理 —— 真正训练逻辑在 service/itemcf.train_itemcf。"""

    def __init__(
        self,
        engine: Engine,
        redis_async: AsyncRedis,
        redis_sync: SyncRedis,
        settings: AppSettings,
        *,
        train_fn: Callable[..., TrainResult] = train_itemcf,
    ) -> None:
        self.engine = engine
        self.redis_async = redis_async
        self.redis_sync = redis_sync
        self.settings = settings
        # 注入式 train_fn 便于单测 mock(默认指向真实实现)
        self._train_fn = train_fn

    # ---------- 公开入口 ----------

    async def trigger(self) -> dict[str, Any]:
        """异步触发(fire-and-forget)。

        流程:
          1. Redis NX 抢锁 → 占不到 → 立返 already_running。
          2. INSERT t_train_log RUNNING(executor 内),拿 run_id。
          3. schedule executor 跑 `_run_sync(run_id)`,不 await。
          4. 立即返回 {triggered:True, runId}。
        """
        got = await self.redis_async.set(LOCK_KEY, "1", nx=True, ex=LOCK_TTL_SECONDS)
        if not got:
            logger.info("train trigger blocked: lock held")
            return {"triggered": False, "reason": "already_running"}

        loop = asyncio.get_running_loop()
        try:
            run_id = await loop.run_in_executor(None, self._insert_running)
        except Exception:
            logger.exception("insert t_train_log RUNNING failed; releasing lock")
            # 锁释放需在 async 路径用 async client(executor 内出错,主 loop 还在)
            try:
                await self.redis_async.delete(LOCK_KEY)
            except Exception:  # noqa: BLE001
                logger.exception("release lock after insert failure failed")
            return {"triggered": False, "reason": "insert_log_failed"}

        # fire-and-forget;run_in_executor 返回 Future,不 await,不阻塞主 loop
        loop.run_in_executor(None, self._run_sync, run_id)
        return {"triggered": True, "runId": run_id}

    def schedule(self, scheduler: AsyncIOScheduler) -> None:
        """注册 cron 触发器(回调还是 self.trigger,内部 NX 锁 + fire-and-forget)。"""
        cron = CronTrigger.from_crontab(self.settings.model.train_cron, timezone=utc)
        scheduler.add_job(
            self.trigger,
            cron,
            id=JOB_ID,
            coalesce=True,
            max_instances=1,
            replace_existing=True,
        )
        logger.info(
            "TrainJob scheduled: cron=%s timezone=UTC",
            self.settings.model.train_cron,
        )

    # ---------- executor 内同步路径 ----------

    def _insert_running(self) -> int:
        """INSERT t_train_log status=RUNNING；返回 id。"""
        now = datetime.now(timezone.utc)
        with self.engine.begin() as conn:
            result = conn.execute(
                text(
                    "INSERT INTO t_train_log (started_at, status) "
                    "VALUES (:started_at, 'RUNNING')"
                ),
                {"started_at": now},
            )
            return int(result.lastrowid)

    def _update_success(self, run_id: int, r: TrainResult) -> None:
        with self.engine.begin() as conn:
            conn.execute(
                text(
                    "UPDATE t_train_log SET "
                    "  finished_at = :finished_at, status = 'SUCCESS', "
                    "  user_count = :user_count, item_count = :item_count, "
                    "  behavior_count = :behavior_count, error = NULL "
                    "WHERE id = :id"
                ),
                {
                    "finished_at": datetime.now(timezone.utc),
                    "user_count": r.user_count,
                    "item_count": r.item_count,
                    "behavior_count": r.behavior_count,
                    "id": run_id,
                },
            )

    def _update_failed(self, run_id: int, error: str) -> None:
        # 即使 UPDATE 失败也 swallow —— 上层 finally 仍会释放锁
        try:
            with self.engine.begin() as conn:
                conn.execute(
                    text(
                        "UPDATE t_train_log SET "
                        "  finished_at = :finished_at, status = 'FAILED', error = :error "
                        "WHERE id = :id"
                    ),
                    {
                        "finished_at": datetime.now(timezone.utc),
                        "error": error[:500],
                        "id": run_id,
                    },
                )
        except Exception:
            logger.exception("update t_train_log FAILED itself failed run_id=%s", run_id)

    def _run_sync(self, run_id: int) -> None:
        """executor 线程入口;捕获一切异常,释放锁。"""
        try:
            result = self._train_fn(
                self.engine,
                self.redis_sync,
                sim_topn=self.settings.model.sim_topn,
            )
            self._update_success(run_id, result)
            logger.info(
                "training succeeded run_id=%s elapsed_ms=%d sim_keys=%d",
                run_id,
                result.elapsed_ms,
                result.sim_index_size,
            )
        except Exception as e:  # noqa: BLE001
            logger.exception("training failed run_id=%s", run_id)
            self._update_failed(run_id, repr(e))
        finally:
            try:
                self.redis_sync.delete(LOCK_KEY)
            except Exception:  # noqa: BLE001
                logger.exception("release reco:training lock failed run_id=%s", run_id)
