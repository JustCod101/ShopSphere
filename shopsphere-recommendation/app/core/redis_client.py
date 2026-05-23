"""异步 Redis 客户端工厂。"""
from __future__ import annotations

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
