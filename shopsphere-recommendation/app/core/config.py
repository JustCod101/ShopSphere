"""应用配置 —— 由 Nacos 拉到的 YAML 装配到 Pydantic 模型。

由 lifespan 调 `nacos_bootstrap.load_yaml_config(...)` 拉取后 `AppSettings.model_validate(dict)` 装配，
而非通过 env / .env（Java 侧靠 Nacos 集中管理，Python 沿用统一来源）。
"""
from __future__ import annotations

from typing import Optional
from urllib.parse import quote

from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings


def _q(s: str) -> str:
    """URL userinfo / path 段编码 —— 防止口令含 @ : / # % 等字符破坏 URL 解析。"""
    return quote(s, safe="")


class MysqlSettings(BaseModel):
    host: str
    port: int = 3306
    user: str
    password: str
    db: str = "shopsphere_reco"

    def jdbc_url(self) -> str:
        return (
            f"mysql+pymysql://{_q(self.user)}:{_q(self.password)}"
            f"@{self.host}:{self.port}/{self.db}?charset=utf8mb4"
        )


class RedisSettings(BaseModel):
    host: str
    port: int = 6379
    password: Optional[str] = None
    db: int = 0


class RabbitMQSettings(BaseModel):
    host: str
    port: int = 5672
    user: str
    password: str
    vhost: str = "shopsphere"

    def amqp_url(self) -> str:
        return (
            f"amqp://{_q(self.user)}:{_q(self.password)}"
            f"@{self.host}:{self.port}/{_q(self.vhost)}"
        )


class ModelSettings(BaseModel):
    topk_default: int = 10
    sim_topn: int = 50
    train_cron: str = "0 2 * * *"  # 02:00 UTC 全量训练（T4.2）


class AppSettings(BaseSettings):
    app_name: str = "shopsphere-recommendation"
    profile: str = "dev"
    server_port: int = 8000

    mysql: MysqlSettings
    redis: RedisSettings
    rabbitmq: RabbitMQSettings
    model: ModelSettings = Field(default_factory=ModelSettings)


def build_settings(yaml_dict: dict) -> AppSettings:
    """从 Nacos YAML dict 构造 AppSettings；profile 由 env APP_PROFILE 注入，不要求 YAML 提供。"""
    import os

    profile = os.getenv("APP_PROFILE", "dev")
    payload = {**yaml_dict, "profile": profile}
    return AppSettings.model_validate(payload)
