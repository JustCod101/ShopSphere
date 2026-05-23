"""错误码（契约 §2）。

Python 推荐服务维护与 Java 侧 ErrorCode 同构的一份号段：

- 通用 1xxx 复用
- 5xxx 推荐服务自有：5001 COLD_START、5002 MODEL_NOT_READY

注意（C1 拍板）：5001/5002 **不进 Result.code**，仅作监控埋点 / 日志维度；
冷启动 / 模型未就绪接口仍返回 code=0、data.fallback=true。
"""
from __future__ import annotations

from enum import IntEnum


class ErrorCode(IntEnum):
    OK = 0

    # 通用（与 shopsphere-common ErrorCode 对齐）
    PARAM_INVALID = 1000
    UNAUTHORIZED = 1001
    NOT_FOUND = 1004
    SERVER_ERROR = 1500

    # 推荐服务 5xxx —— 仅监控埋点用
    COLD_START = 5001
    MODEL_NOT_READY = 5002


# 默认 message —— 与 Java 侧 ErrorCode#message 同义
DEFAULT_MESSAGES: dict[ErrorCode, str] = {
    ErrorCode.OK: "ok",
    ErrorCode.PARAM_INVALID: "参数校验失败",
    ErrorCode.UNAUTHORIZED: "未认证",
    ErrorCode.NOT_FOUND: "资源不存在",
    ErrorCode.SERVER_ERROR: "系统内部错误",
    ErrorCode.COLD_START: "冷启动 / 无行为数据",
    ErrorCode.MODEL_NOT_READY: "模型未就绪",
}


def default_message(code: ErrorCode) -> str:
    return DEFAULT_MESSAGES.get(code, "")
