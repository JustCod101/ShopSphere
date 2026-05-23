"""Redis 客户端工厂 —— async（默认）+ sync（训练任务在 executor 线程用）。"""
from __future__ import annotations

from redis import Redis as SyncRedis
from redis.asyncio import Redis

from app.core.config import RedisSettings


def make_redis(cfg: RedisSettings) -> Redis:
    return Redis(
        host=cfg.host,
        port=cfg.port,
        db=cfg.db,
        password=cfg.password,
        decode_responses=True,
    )


def make_sync_redis(cfg: RedisSettings) -> SyncRedis:
    """训练任务在 executor 线程使用的同步客户端 —— 与 async 实例分离避免跨线程触碰 asyncio loop。"""
    return SyncRedis(
        host=cfg.host,
        port=cfg.port,
        db=cfg.db,
        password=cfg.password,
        decode_responses=True,
    )
