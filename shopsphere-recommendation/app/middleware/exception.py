"""全局异常处理器 —— Result.fail + HTTP 200（契约 §1.1）。

- BusinessException → Result.fail(code, msg)
- RequestValidationError（Pydantic）→ PARAM_INVALID 1000
- 其他 Exception → SERVER_ERROR 1500（带堆栈日志）
- HTTPException 不接管 —— 让 4xx/5xx 保持原状（任务约束「HTTP 4xx/5xx 仅给未捕获」）
"""
from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.core.error_code import ErrorCode
from app.core.exceptions import BusinessException
from app.core.result import Result

logger = logging.getLogger(__name__)


def install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(BusinessException)
    async def biz_handler(request: Request, exc: BusinessException) -> JSONResponse:
        return JSONResponse(Result.fail(exc.code, exc.message).to_dict(), status_code=200)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        # 报文校验失败：1000；具体字段错误进日志便于排查，不暴露给客户端
        logger.warning("request validation failed: path=%s errors=%s", request.url.path, exc.errors())
        return JSONResponse(Result.fail(ErrorCode.PARAM_INVALID).to_dict(), status_code=200)

    @app.exception_handler(Exception)
    async def unknown_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("uncaught exception path=%s", request.url.path)
        return JSONResponse(Result.fail(ErrorCode.SERVER_ERROR).to_dict(), status_code=200)
