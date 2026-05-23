"""健康检查接口（契约 §6.4）。

`model_ready` = `model:sim:ready` Redis key 是否存在 —— T4.2 训练完成后置位。
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Request
from starlette.responses import JSONResponse

from app.core.result import Result

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/api/recommend/health")
async def health(request: Request) -> JSONResponse:
    redis = getattr(request.app.state, "redis", None)
    model_ready = False
    if redis is not None:
        try:
            model_ready = bool(await redis.exists("model:sim:ready"))
        except Exception:  # noqa: BLE001
            logger.warning("health: redis exists check failed", exc_info=True)
            model_ready = False
    return JSONResponse(Result.ok({"status": "UP", "model_ready": model_ready}).to_dict())
