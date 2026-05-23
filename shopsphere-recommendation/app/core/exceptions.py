"""统一业务异常。Service 层抛此异常，由全局 handler 转 Result.fail + HTTP 200。"""
from __future__ import annotations

from typing import Optional

from app.core.error_code import ErrorCode


class BusinessException(Exception):
    def __init__(self, code: ErrorCode, message: Optional[str] = None) -> None:
        self.code = code
        self.message = message
        super().__init__(message or code.name)
