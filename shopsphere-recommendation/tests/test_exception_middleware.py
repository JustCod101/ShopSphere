"""全局异常处理：Result.fail + HTTP 200。"""
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel
from starlette.responses import JSONResponse

from app.core.error_code import ErrorCode
from app.core.exceptions import BusinessException
from app.core.result import Result
from app.middleware.exception import install_exception_handlers


class _Body(BaseModel):
    n: int


def _build_app() -> FastAPI:
    app = FastAPI()
    install_exception_handlers(app)

    @app.get("/biz")
    async def biz():
        raise BusinessException(ErrorCode.NOT_FOUND, "no such thing")

    @app.get("/boom")
    async def boom():
        raise RuntimeError("oops")

    @app.post("/echo")
    async def echo(b: _Body):
        return JSONResponse(Result.ok({"n": b.n}).to_dict())

    return app


@pytest.mark.asyncio
async def test_business_exception_to_result():
    async with AsyncClient(transport=ASGITransport(app=_build_app()), base_url="http://t") as c:
        r = await c.get("/biz")
        assert r.status_code == 200
        body = r.json()
        assert body["code"] == 1004
        assert body["message"] == "no such thing"
        assert body["data"] is None


@pytest.mark.asyncio
async def test_unknown_exception_to_1500():
    # raise_app_exceptions=False —— 让 httpx ASGITransport 把异常交给 app 的 handler，
    # 而不是再向测试代码抛出（实际 ASGI server 也是经 handler 的）。
    transport = ASGITransport(app=_build_app(), raise_app_exceptions=False)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/boom")
        assert r.status_code == 200
        body = r.json()
        assert body["code"] == 1500


@pytest.mark.asyncio
async def test_validation_error_to_1000():
    async with AsyncClient(transport=ASGITransport(app=_build_app()), base_url="http://t") as c:
        r = await c.post("/echo", json={"n": "not-int"})
        assert r.status_code == 200
        body = r.json()
        assert body["code"] == 1000
