"""Nacos 接入封装。

`nacos-sdk-python==0.1.12` 是同步 SDK；在 async 路径上调用其方法必须通过 executor 包装
（本服务仅在启动期 lifespan 同步调用，运行时由 SDK 自带 daemon 线程托管心跳）。

服务命名 / 配置约定（契约 §5）：serviceName=shopsphere-recommendation；
配置 dataId 习惯 `shopsphere-recommendation-{profile}.yaml`（机密）+ `shopsphere-recommendation.yaml`（公共）。
"""
from __future__ import annotations

import logging
import os
import re
import socket
from typing import Callable, Optional

import yaml
from nacos import NacosClient  # type: ignore[import-untyped]

logger = logging.getLogger(__name__)

SERVICE_NAME = "shopsphere-recommendation"
DEFAULT_GROUP = "DEFAULT_GROUP"

# Spring 风格 placeholder: ${VAR} 或 ${VAR:default}
# 与 Java 服务行为对齐;Nacos 同一份 yaml 在 Java / Python 容器内得到一致解析。
_PLACEHOLDER_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")


def expand_env_placeholders(text: str) -> str:
    """展开 ${VAR} / ${VAR:default}。env 缺且无 default 时保留原文(让下游解析时显式炸)。"""
    def _sub(m: re.Match[str]) -> str:
        key, default = m.group(1), m.group(2)
        val = os.environ.get(key)
        if val is not None:
            return val
        if default is not None:
            return default
        return m.group(0)
    return _PLACEHOLDER_RE.sub(_sub, text)


def resolve_register_ip() -> str:
    """注册 Nacos 时上报的 IP。

    优先级：env NACOS_REGISTER_IP > 主机解析 > 127.0.0.1（开发兜底）。
    """
    env_ip = os.getenv("NACOS_REGISTER_IP")
    if env_ip:
        return env_ip
    try:
        return socket.gethostbyname(socket.gethostname())
    except Exception:  # noqa: BLE001
        return "127.0.0.1"


class NacosBootstrap:
    def __init__(
        self,
        server_addresses: str,
        namespace: Optional[str] = None,
        service_name: str = SERVICE_NAME,
    ) -> None:
        self.server_addresses = server_addresses
        self.namespace = namespace
        self.service_name = service_name
        self._client = NacosClient(server_addresses, namespace=namespace or "")

    # ------------- 配置拉取 -------------
    def load_yaml_config(self, data_id: str, group: str = DEFAULT_GROUP) -> dict:
        """同步拉 dataId，YAML 文本 → dict。空配置返回 {}。"""
        content = self._client.get_config(data_id, group)
        if not content:
            logger.warning("Nacos config empty: dataId=%s group=%s", data_id, group)
            return {}
        # 与 Java Spring 行为对齐:先展开 ${VAR:default},再 parse YAML
        content = expand_env_placeholders(content)
        try:
            parsed = yaml.safe_load(content) or {}
            if not isinstance(parsed, dict):
                raise ValueError(f"dataId={data_id} top-level not a mapping")
            return parsed
        except yaml.YAMLError as e:
            raise RuntimeError(f"Nacos config YAML parse error: dataId={data_id}: {e}") from e

    def subscribe(self, data_id: str, callback: Callable[[str], None], group: str = DEFAULT_GROUP) -> None:
        """订阅配置变更（SDK 自带线程回调）。本期仅日志，不触发热更/重启。"""
        def _cb(args):
            content = args.get("content") if isinstance(args, dict) else None
            logger.info("Nacos config changed: dataId=%s len=%s", data_id, len(content or ""))
            try:
                callback(content or "")
            except Exception:  # noqa: BLE001
                logger.exception("Nacos subscribe callback error: dataId=%s", data_id)

        self._client.add_config_watcher(data_id, group, _cb)

    # ------------- 服务注册 -------------
    def register(self, ip: str, port: int, group: str = DEFAULT_GROUP) -> None:
        """注册到 Nacos ephemeral 实例。心跳由 FastAPI lifespan 中的后台任务维护。"""
        ok = self._client.add_naming_instance(
            service_name=self.service_name,
            ip=ip,
            port=port,
            cluster_name="DEFAULT",
            weight=1.0,
            metadata={"language": "python", "framework": "fastapi"},
            enable=True,
            healthy=True,
            ephemeral=True,
            group_name=group,
        )
        if not ok:
            raise RuntimeError(f"Nacos register failed: service={self.service_name} ip={ip} port={port}")
        logger.info("Nacos registered: service=%s ip=%s port=%s", self.service_name, ip, port)

    def send_heartbeat(self, ip: str, port: int, group: str = DEFAULT_GROUP) -> dict:
        """发送 Nacos 临时实例心跳。

        nacos-sdk-python 0.1.12 只提供 send_heartbeat API，不会在 add_naming_instance 后自动续约。
        """
        resp = self._client.send_heartbeat(
            service_name=self.service_name,
            ip=ip,
            port=port,
            cluster_name="DEFAULT",
            weight=1.0,
            metadata={"language": "python", "framework": "fastapi"},
            ephemeral=True,
            group_name=group,
        )
        logger.debug("Nacos heartbeat ok: service=%s ip=%s port=%s resp=%s", self.service_name, ip, port, resp)
        return resp

    def deregister(self, ip: str, port: int, group: str = DEFAULT_GROUP) -> None:
        try:
            self._client.remove_naming_instance(
                service_name=self.service_name,
                ip=ip,
                port=port,
                cluster_name="DEFAULT",
                ephemeral=True,
                group_name=group,
            )
            logger.info("Nacos deregistered: service=%s ip=%s port=%s", self.service_name, ip, port)
        except Exception:  # noqa: BLE001
            logger.exception("Nacos deregister failed (best-effort): ip=%s port=%s", ip, port)
