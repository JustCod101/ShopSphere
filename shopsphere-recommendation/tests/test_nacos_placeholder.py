"""T5.1 — nacos_client.expand_env_placeholders 单测。

与 Java Spring `${VAR}` / `${VAR:default}` 行为对齐:
- env 有值 → 用 env
- env 无,有 default → 用 default
- env 无,无 default → 保留原文(让下游解析时显式炸)
"""
from __future__ import annotations

import pytest

from app.core.nacos_client import expand_env_placeholders


def test_env_value_wins(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MYSQL_HOST", "mysql-container")
    assert expand_env_placeholders("host: ${MYSQL_HOST:localhost}") == "host: mysql-container"


def test_default_used_when_env_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("UNSET_VAR_X", raising=False)
    assert expand_env_placeholders("v: ${UNSET_VAR_X:fallback}") == "v: fallback"


def test_no_env_no_default_preserves_original(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("TOTALLY_MISSING_VAR", raising=False)
    assert (
        expand_env_placeholders("v: ${TOTALLY_MISSING_VAR}")
        == "v: ${TOTALLY_MISSING_VAR}"
    )


def test_multi_placeholders(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MYSQL_HOST", "mysql")
    monkeypatch.setenv("REDIS_HOST", "redis")
    monkeypatch.delenv("RABBITMQ_HOST", raising=False)
    text = "mysql: ${MYSQL_HOST:lh}\nredis: ${REDIS_HOST}\nrabbit: ${RABBITMQ_HOST:lh}"
    assert (
        expand_env_placeholders(text)
        == "mysql: mysql\nredis: redis\nrabbit: lh"
    )


def test_default_with_colon_inside(monkeypatch: pytest.MonkeyPatch) -> None:
    """default 含 `:` (URL/port) 不会被截断 —— regex 用 [^}]* 贪婪到右花括号。"""
    monkeypatch.delenv("FAKE_URL", raising=False)
    assert (
        expand_env_placeholders("url: ${FAKE_URL:http://localhost:3306/db}")
        == "url: http://localhost:3306/db"
    )


def test_no_placeholder_returns_as_is() -> None:
    assert expand_env_placeholders("plain text") == "plain text"
    assert expand_env_placeholders("") == ""


def test_malformed_placeholders_untouched() -> None:
    """残缺 placeholder 不应被替换。"""
    assert expand_env_placeholders("${unclosed") == "${unclosed"
    assert expand_env_placeholders("$VAR") == "$VAR"
