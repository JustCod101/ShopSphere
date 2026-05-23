"""统一响应包装 Result<T> —— 与 Java 同构（契约 §1.1）。

字段：code / message / data / traceId / timestamp(OffsetDateTime UTC ISO-8601)。
业务错误一律 HTTP 200 + Result.code != 0；HTTP 4xx/5xx 仅给未捕获异常。
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional

from app.core.error_code import ErrorCode, default_message
from app.middleware.context import current_trace_id


def _utc_now_iso() -> str:
    # 形如 "2026-05-23T10:30:00+00:00"，与 Java OffsetDateTime UTC 同构
    return datetime.now(timezone.utc).isoformat()


@dataclass
class Result:
    code: int
    message: str
    data: Any
    traceId: Optional[str] = None
    timestamp: str = field(default_factory=_utc_now_iso)

    @classmethod
    def ok(cls, data: Any = None, message: str = "ok") -> "Result":
        return cls(
            code=ErrorCode.OK.value,
            message=message,
            data=data,
            traceId=current_trace_id(),
        )

    @classmethod
    def fail(cls, error_code: ErrorCode, message: Optional[str] = None) -> "Result":
        return cls(
            code=int(error_code),
            message=message if message is not None else default_message(error_code),
            data=None,
            traceId=current_trace_id(),
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
