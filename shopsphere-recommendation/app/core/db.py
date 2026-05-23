"""SQLAlchemy 2.0 同步 Engine + sessionmaker（消费者同步落库路径用）。

异步 IO 通过 `loop.run_in_executor` 包装同步 session 调用，避免引入 aiomysql 多一道依赖。
"""
from __future__ import annotations

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import MysqlSettings


def make_engine(cfg: MysqlSettings) -> Engine:
    return create_engine(
        cfg.jdbc_url(),
        pool_size=5,
        max_overflow=5,
        pool_pre_ping=True,
        pool_recycle=3600,
        future=True,
    )


def make_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, autoflush=False, future=True)
