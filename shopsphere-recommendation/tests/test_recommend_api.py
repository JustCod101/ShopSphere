"""recommend 路由端到端 —— 走 UserContextMiddleware + 全局异常 handler + Result 序列化。

不连 Nacos / 真实 Redis / 真实 MySQL,用最小 FastAPI app 装中间件 + 路由,
app.state.redis / db_engine 用 fakeredis + sqlite 内存库注入。
"""
from __future__ import annotations

import fakeredis
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.api.recommend import router as recommend_router
from app.middleware.context import UserContextMiddleware
from app.middleware.exception import install_exception_handlers


@pytest.fixture
def fake_engine():
    eng = create_engine(
        "sqlite:///:memory:",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with eng.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE behavior_event (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  event_id VARCHAR(64) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  item_id BIGINT NOT NULL,
                  action_type VARCHAR(16) NOT NULL,
                  source VARCHAR(16) NOT NULL,
                  ts TIMESTAMP NOT NULL
                )
                """
            )
        )
    yield eng
    eng.dispose()


@pytest.fixture
def app_with_state(fake_engine):
    app = FastAPI()
    app.add_middleware(UserContextMiddleware)
    install_exception_handlers(app)
    app.include_router(recommend_router)
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    app.state.redis = redis
    app.state.db_engine = fake_engine
    return app, redis


async def _seed_hot(redis, items):
    await redis.zadd("hot:items:global", {str(i): s for i, s in items})


async def _seed_user_behavior(redis, uid, items):
    await redis.zadd(f"user:behavior:{uid}", {str(i): s for i, s in items})


async def _seed_sim(redis, item_id, neighbors):
    await redis.zadd(f"sim:item:{item_id}", {str(i): s for i, s in neighbors})


@pytest.mark.asyncio
async def test_user_endpoint_unauthorized_no_header(app_with_state):
    app, _ = app_with_state
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/user/1?topk=10")
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 1001
    assert body["data"] is None


@pytest.mark.asyncio
async def test_user_endpoint_unauthorized_mismatch(app_with_state):
    app, _ = app_with_state
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get(
            "/api/recommend/user/2?topk=10", headers={"X-User-Id": "1"}
        )
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 1001


@pytest.mark.asyncio
async def test_user_endpoint_cold_start_returns_fallback(app_with_state):
    app, redis = app_with_state
    await redis.set("reco:model:ready", "1", ex=3600)
    await _seed_hot(redis, [(11, 9.0), (22, 8.0), (33, 7.0)])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get(
            "/api/recommend/user/1?topk=10", headers={"X-User-Id": "1"}
        )
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["fallback"] is True
    assert body["data"]["userId"] == 1
    assert body["data"]["topk"] == 10
    assert [it["itemId"] for it in body["data"]["items"]] == [11, 22, 33]


@pytest.mark.asyncio
async def test_user_endpoint_topk_validation_upper(app_with_state):
    """全局 RequestValidationError handler 把 422 转成 HTTP 200 + code=1000(契约 §1.1)。"""
    app, _ = app_with_state
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get(
            "/api/recommend/user/1?topk=51", headers={"X-User-Id": "1"}
        )
    assert resp.status_code == 200
    assert resp.json()["code"] == 1000


@pytest.mark.asyncio
async def test_user_endpoint_topk_validation_lower(app_with_state):
    app, _ = app_with_state
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get(
            "/api/recommend/user/1?topk=0", headers={"X-User-Id": "1"}
        )
    assert resp.status_code == 200
    assert resp.json()["code"] == 1000


@pytest.mark.asyncio
async def test_user_endpoint_normal_with_recall(app_with_state):
    app, redis = app_with_state
    await redis.set("reco:model:ready", "1", ex=3600)
    await _seed_user_behavior(redis, 1, [(100, 200.0)])
    await _seed_sim(redis, 100, [(300, 0.9), (400, 0.8)])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get(
            "/api/recommend/user/1?topk=5", headers={"X-User-Id": "1"}
        )
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["fallback"] is False
    assert [it["itemId"] for it in body["data"]["items"]] == [300, 400]


@pytest.mark.asyncio
async def test_similar_endpoint_normal(app_with_state):
    app, redis = app_with_state
    await _seed_sim(redis, 42, [(1, 0.9), (2, 0.8)])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/similar/42?topk=5")
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["itemId"] == 42
    assert body["data"]["fallback"] is False
    assert [it["itemId"] for it in body["data"]["items"]] == [1, 2]


@pytest.mark.asyncio
async def test_similar_endpoint_fallback_to_hot(app_with_state):
    app, redis = app_with_state
    await _seed_hot(redis, [(11, 9.0)])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/similar/999?topk=5")
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["fallback"] is True
    assert [it["itemId"] for it in body["data"]["items"]] == [11]


@pytest.mark.asyncio
async def test_similar_endpoint_public_no_auth_required(app_with_state):
    """/similar 是 P 公开,无 X-User-Id 也能调通(中间件不拦截非保护前缀)。"""
    app, redis = app_with_state
    await _seed_sim(redis, 1, [(2, 0.5)])

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get("/api/recommend/similar/1?topk=5")
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["fallback"] is False


@pytest.mark.asyncio
async def test_user_endpoint_traceid_round_trip(app_with_state):
    """X-Trace-Id 应回填 Result.traceId(契约 §3)。"""
    app, redis = app_with_state
    await _seed_hot(redis, [(11, 9.0)])
    await redis.set("reco:model:ready", "1", ex=3600)

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://t") as ac:
        resp = await ac.get(
            "/api/recommend/user/1?topk=10",
            headers={"X-User-Id": "1", "X-Trace-Id": "trace-abc-123"},
        )
    body = resp.json()
    assert body["traceId"] == "trace-abc-123"
