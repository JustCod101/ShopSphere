"""FastAPI 入口 + lifespan。

启动顺序（plan §「启动顺序」）：
  日志 → Nacos.load_config → AppSettings → DB Engine → Redis → BehaviorConsumer.start → Nacos.register
关停反向：deregister → consumer.stop → redis.aclose → engine.dispose
"""
from __future__ import annotations

import logging
import os
import sys
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI
from pythonjsonlogger import jsonlogger

from app.api import health, recommend
from app.consumer.behavior_consumer import BehaviorConsumer
from app.core.config import AppSettings, build_settings
from app.core.db import make_engine
from app.core.nacos_client import NacosBootstrap, resolve_register_ip
from app.core.redis_client import make_redis
from app.middleware.context import UserContextMiddleware
from app.middleware.exception import install_exception_handlers

logger = logging.getLogger(__name__)


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
    app.state.db_engine = engine
    app.state.redis = redis

    # MQ 消费者
    consumer = BehaviorConsumer(settings, engine, redis)
    app.state.consumer = consumer
    await consumer.start()

    # Nacos 注册（最后做，确保流量进来时资源已就绪）
    register_ip = resolve_register_ip()
    register_port = int(os.getenv("SERVER_PORT", str(settings.server_port)))
    app.state.register_ip = register_ip
    app.state.register_port = register_port
    nacos.register(register_ip, register_port)

    try:
        yield
    finally:
        # 反向关停（best-effort）
        try:
            nacos.deregister(register_ip, register_port)
        except Exception:  # noqa: BLE001
            logger.exception("nacos deregister failed")
        try:
            await consumer.stop()
        except Exception:  # noqa: BLE001
            logger.exception("consumer stop failed")
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
