"""FastAPI 入口 + lifespan。

启动顺序（plan §「启动顺序」）：
  日志 → Nacos.load_config → AppSettings → DB Engine → Redis → BehaviorConsumer.start → Nacos.register
关停反向：deregister → consumer.stop → redis.aclose → engine.dispose
"""
from __future__ import annotations

import asyncio
import logging
import os
import sys
from contextlib import asynccontextmanager, suppress
from typing import Optional

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from fastapi import FastAPI
from pythonjsonlogger import jsonlogger
from pytz import utc

from app.api import health, recommend
from app.consumer.behavior_consumer import BehaviorConsumer
from app.core.config import AppSettings, build_settings
from app.core.db import make_engine
from app.core.nacos_client import NacosBootstrap, resolve_register_ip
from app.core.redis_client import make_redis, make_sync_redis
from app.middleware.context import UserContextMiddleware
from app.middleware.exception import install_exception_handlers
from app.tasks.train_job import TrainJob

logger = logging.getLogger(__name__)


async def _nacos_heartbeat_loop(nacos: NacosBootstrap, ip: str, port: int) -> None:
    interval = 5.0
    while True:
        await asyncio.sleep(interval)
        try:
            resp = await asyncio.to_thread(nacos.send_heartbeat, ip, port)
            client_interval = resp.get("clientBeatInterval") if isinstance(resp, dict) else None
            if isinstance(client_interval, int) and client_interval > 0:
                interval = max(1.0, min(client_interval / 1000.0, 10.0))
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logger.exception("nacos heartbeat failed; trying to register again")
            try:
                await asyncio.to_thread(nacos.register, ip, port)
            except Exception:  # noqa: BLE001
                logger.exception("nacos re-register failed")
            interval = 5.0


def _setup_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        jsonlogger.JsonFormatter(
            "%(asctime)s %(levelname)s %(name)s %(message)s",
            rename_fields={"asctime": "ts", "levelname": "level", "name": "logger"},
        )
    )
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())


def _load_settings_from_nacos() -> tuple[AppSettings, NacosBootstrap]:
    nacos_server = os.getenv("NACOS_SERVER", "127.0.0.1:8848")
    namespace = os.getenv("NACOS_NAMESPACE") or None
    profile = os.getenv("APP_PROFILE", "dev")

    bootstrap = NacosBootstrap(server_addresses=nacos_server, namespace=namespace)
    public_cfg = bootstrap.load_yaml_config("shopsphere-recommendation.yaml")
    profile_cfg = bootstrap.load_yaml_config(f"shopsphere-recommendation-{profile}.yaml")
    merged = {**public_cfg, **profile_cfg}  # profile 覆盖公共
    settings = build_settings(merged)
    return settings, bootstrap


@asynccontextmanager
async def lifespan(app: FastAPI):
    _setup_logging()

    settings, nacos = _load_settings_from_nacos()
    app.state.settings = settings
    app.state.nacos = nacos
    logger.info("settings loaded: profile=%s port=%s", settings.profile, settings.server_port)

    # 资源（DB / Redis）
    engine = make_engine(settings.mysql)
    redis = make_redis(settings.redis)
    redis_sync = make_sync_redis(settings.redis)  # T4.2：训练任务在 executor 线程用
    app.state.db_engine = engine
    app.state.redis = redis
    app.state.redis_sync = redis_sync

    # MQ 消费者
    consumer = BehaviorConsumer(settings, engine, redis)
    app.state.consumer = consumer
    await consumer.start()

    # T4.2：APScheduler + TrainJob
    scheduler = AsyncIOScheduler(timezone=utc)
    train_job = TrainJob(engine, redis, redis_sync, settings)
    train_job.schedule(scheduler)
    scheduler.start()
    app.state.scheduler = scheduler
    app.state.train_job = train_job

    # Nacos 注册（最后做，确保流量进来时资源已就绪）
    register_ip = resolve_register_ip()
    register_port = int(os.getenv("SERVER_PORT", str(settings.server_port)))
    app.state.register_ip = register_ip
    app.state.register_port = register_port
    nacos.register(register_ip, register_port)
    heartbeat_task = asyncio.create_task(_nacos_heartbeat_loop(nacos, register_ip, register_port))
    app.state.nacos_heartbeat_task = heartbeat_task

    try:
        yield
    finally:
        # 反向关停（best-effort）
        heartbeat_task.cancel()
        with suppress(asyncio.CancelledError):
            await heartbeat_task
        try:
            nacos.deregister(register_ip, register_port)
        except Exception:  # noqa: BLE001
            logger.exception("nacos deregister failed")
        try:
            scheduler.shutdown(wait=False)
        except Exception:  # noqa: BLE001
            logger.exception("scheduler shutdown failed")
        try:
            await consumer.stop()
        except Exception:  # noqa: BLE001
            logger.exception("consumer stop failed")
        try:
            redis_sync.close()
        except Exception:  # noqa: BLE001
            logger.exception("redis_sync close failed")
        try:
            await redis.aclose()
        except Exception:  # noqa: BLE001
            logger.exception("redis close failed")
        try:
            engine.dispose()
        except Exception:  # noqa: BLE001
            logger.exception("engine dispose failed")


def create_app() -> FastAPI:
    app = FastAPI(
        title="ShopSphere Recommendation",
        version="0.1.0",
        lifespan=lifespan,
        # 关闭 OpenAPI 默认 docs 鉴权透传 —— 推荐服务对外接口 Gateway 转发
        docs_url="/api/recommend/docs",
        redoc_url=None,
        openapi_url="/api/recommend/openapi.json",
    )

    app.add_middleware(UserContextMiddleware)
    install_exception_handlers(app)

    app.include_router(health.router)
    app.include_router(recommend.router)
    return app


app = create_app()
