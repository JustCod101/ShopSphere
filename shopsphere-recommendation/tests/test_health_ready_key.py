"""健康检查应读 reco:model:ready(T4.2 校正,与 T4.1 训练写入对齐)。"""
from __future__ import annotations

import fakeredis
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from app.api.health import router as health_router


@pytest.fixture
def app_with_ready_key():
    app = FastAPI()
    app.include_router(health_router)
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    app.state.redis = redis
    return app, redis


@pytest.mark.asyncio
async def test_health_returns_false_when_ready_key_absent(app_with_ready_key):
    app, _redis = app_with_ready_key
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["status"] == "UP"
    assert body["data"]["model_ready"] is False


@pytest.mark.asyncio
async def test_health_returns_true_when_reco_model_ready_set(app_with_ready_key):
    app, redis = app_with_ready_key
    await redis.set("reco:model:ready", "1", ex=3600)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/health")
    assert resp.json()["data"]["model_ready"] is True


@pytest.mark.asyncio
async def test_health_ignores_legacy_model_sim_ready(app_with_ready_key):
    """旧 key 不应再触发 model_ready=True(防回归 T4.1 期间的 bug)。"""
    app, redis = app_with_ready_key
    await redis.set("model:sim:ready", "1", ex=3600)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/health")
    assert resp.json()["data"]["model_ready"] is False
