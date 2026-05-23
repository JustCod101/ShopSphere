"""UserContextMiddleware：受保护路径强制登录、X-Trace-Id 注入。

构造一个最小 Starlette app，不进入 lifespan / Nacos —— 隔离测中间件本身。
"""
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from starlette.responses import JSONResponse

from app.core.result import Result
from app.middleware.context import UserContextMiddleware, current_trace_id, current_user_id


def _build_app() -> FastAPI:
    app = FastAPI()
    app.add_middleware(UserContextMiddleware)

    @app.get("/api/recommend/health")
    async def public():
        return JSONResponse(Result.ok({"u": current_user_id(), "t": current_trace_id()}).to_dict())

    @app.get("/api/recommend/user/{uid}")
    async def protected(uid: int):
        return JSONResponse(Result.ok({"uid": uid, "u": current_user_id()}).to_dict())

    return app


@pytest.mark.asyncio
async def test_public_path_no_user_header_allowed():
    async with AsyncClient(transport=ASGITransport(app=_build_app()), base_url="http://t") as c:
        r = await c.get("/api/recommend/health", headers={"X-Trace-Id": "trace-abc"})
        body = r.json()
        assert r.status_code == 200
        assert body["code"] == 0
        assert body["data"]["u"] is None
        assert body["data"]["t"] == "trace-abc"
        # traceId 应注入到 Result
        assert body["traceId"] == "trace-abc"


@pytest.mark.asyncio
async def test_protected_path_missing_user_id_returns_1001():
    async with AsyncClient(transport=ASGITransport(app=_build_app()), base_url="http://t") as c:
        r = await c.get("/api/recommend/user/123")
        assert r.status_code == 200  # 业务错误用 HTTP 200
        body = r.json()
        assert body["code"] == 1001


@pytest.mark.asyncio
async def test_protected_path_with_user_id_passes():
    async with AsyncClient(transport=ASGITransport(app=_build_app()), base_url="http://t") as c:
        r = await c.get(
            "/api/recommend/user/123",
            headers={"X-User-Id": "456", "X-Trace-Id": "t-1"},
        )
        body = r.json()
        assert r.status_code == 200
        assert body["code"] == 0
        assert body["data"]["u"] == 456
        assert body["traceId"] == "t-1"


@pytest.mark.asyncio
async def test_invalid_user_id_treated_as_anonymous():
    async with AsyncClient(transport=ASGITransport(app=_build_app()), base_url="http://t") as c:
        # 非数字 → 视为缺失 → 受保护路径 1001
        r = await c.get("/api/recommend/user/123", headers={"X-User-Id": "abc"})
        assert r.json()["code"] == 1001
