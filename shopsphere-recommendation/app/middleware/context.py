"""请求上下文中间件 —— 与 Java UserContextInterceptor 等价（契约 §3）。

- X-User-Id → contextvar（int），缺失则 None
- X-Trace-Id → contextvar，写入 Result.traceId 与日志
- 受保护路径前缀（/api/recommend/user/）若无 X-User-Id → 1001
- traceId 不回写客户端响应头（契约 §3「仅内部链路，不回写」）
"""
from __future__ import annotations

from contextvars import ContextVar
from typing import Optional

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

USER_ID_CTX: ContextVar[Optional[int]] = ContextVar("shopsphere_user_id", default=None)
TRACE_ID_CTX: ContextVar[Optional[str]] = ContextVar("shopsphere_trace_id", default=None)

PROTECTED_PREFIXES: tuple[str, ...] = ("/api/recommend/user/",)


def current_user_id() -> Optional[int]:
    return USER_ID_CTX.get()


def current_trace_id() -> Optional[str]:
    return TRACE_ID_CTX.get()


def _parse_user_id(raw: Optional[str]) -> Optional[int]:
    if not raw:
        return None
    s = raw.strip()
    # 允许负号是为了与 int 解析一致；Gateway 注入只会给正整数
    if s.lstrip("-").isdigit():
        try:
            return int(s)
        except ValueError:  # 理论不可达
            return None
    return None


class UserContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next) -> Response:
        trace_token = TRACE_ID_CTX.set(request.headers.get("X-Trace-Id"))
        user_token = USER_ID_CTX.set(_parse_user_id(request.headers.get("X-User-Id")))
        try:
            if (
                any(request.url.path.startswith(p) for p in PROTECTED_PREFIXES)
                and current_user_id() is None
            ):
                # 延迟导入避免循环依赖（result → context.current_trace_id）
                from app.core.error_code import ErrorCode
                from app.core.result import Result

                return JSONResponse(Result.fail(ErrorCode.UNAUTHORIZED).to_dict(), status_code=200)
            return await call_next(request)
        finally:
            USER_ID_CTX.reset(user_token)
            TRACE_ID_CTX.reset(trace_token)
