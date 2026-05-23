"""推荐接口骨架（契约 §6.4）。

- `/api/recommend/user/{userId}` (A) —— 个性化召回；T4.3 实现，本期冷启动 fallback 空列表
- `/api/recommend/similar/{itemId}` (P) —— i2i 相似；T4.3 实现，本期空列表
- `/internal/recommend/train` (T4.2) —— 异步 fire-and-forget,立即返回 runId

C1 拍板：冷启动 / 模型未就绪 一律 `code=0 + data.fallback=true`，5001/5002 仅监控埋点不进 code。
"""
from __future__ import annotations

from fastapi import APIRouter, Query, Request
from starlette.responses import JSONResponse

from app.core.result import Result

router = APIRouter()


@router.get("/api/recommend/user/{user_id}")
async def recommend_user(user_id: int, topk: int = Query(10, ge=1, le=100)) -> JSONResponse:
    # T4.1：模型未就绪 → 冷启动 fallback；T4.3 接 recall service。
    return JSONResponse(
        Result.ok(
            {
                "userId": user_id,
                "topk": topk,
                "items": [],
                "fallback": True,
                "reason": "cold-start",
            }
        ).to_dict()
    )


@router.get("/api/recommend/similar/{item_id}")
async def recommend_similar(item_id: int, topk: int = Query(10, ge=1, le=100)) -> JSONResponse:
    return JSONResponse(
        Result.ok({"itemId": item_id, "topk": topk, "items": []}).to_dict()
    )


@router.post("/internal/recommend/train")
async def trigger_train(request: Request) -> JSONResponse:
    """T4.2：异步 fire-and-forget。trigger 内拿 NX 锁 + 插 RUNNING 行 + executor 跑训练。"""
    train_job = request.app.state.train_job
    info = await train_job.trigger()
    return JSONResponse(Result.ok(info).to_dict())
