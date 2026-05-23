"""推荐接口(契约 §6.4)。

- `/api/recommend/user/{userId}` (A) —— 个性化召回(T4.3)
- `/api/recommend/similar/{itemId}` (P) —— i2i 相似(T4.3)
- `/internal/recommend/train` (T4.2) —— 异步 fire-and-forget,立即返回 runId

C1 拍板:冷启动 / 模型未就绪 一律 `code=0 + data.fallback=true`,5001/5002 仅监控埋点不进 code。
"""
from __future__ import annotations

from fastapi import APIRouter, Query, Request
from starlette.responses import JSONResponse

from app.core.error_code import ErrorCode
from app.core.result import Result
from app.middleware.context import current_user_id
from app.service.recall import recall_for_user, recall_similar

router = APIRouter()


@router.get("/api/recommend/user/{user_id}")
async def recommend_user(
    user_id: int,
    request: Request,
    topk: int = Query(10, ge=1, le=50),
) -> JSONResponse:
    # 中间件已拦截缺 X-User-Id 的情况;此处再做 path/ctx 一致性,防越权访问他人推荐。
    ctx_uid = current_user_id()
    if ctx_uid is None or ctx_uid != user_id:
        return JSONResponse(Result.fail(ErrorCode.UNAUTHORIZED).to_dict(), status_code=200)

    redis = request.app.state.redis
    engine = request.app.state.db_engine
    result = await recall_for_user(redis, engine, user_id=user_id, topk=topk)
    return JSONResponse(
        Result.ok(
            {
                "userId": user_id,
                "topk": topk,
                "items": result.items,
                "fallback": result.fallback,
            }
        ).to_dict()
    )


@router.get("/api/recommend/similar/{item_id}")
async def recommend_similar(
    item_id: int,
    request: Request,
    topk: int = Query(10, ge=1, le=50),
) -> JSONResponse:
    redis = request.app.state.redis
    result = await recall_similar(redis, item_id=item_id, topk=topk)
    return JSONResponse(
        Result.ok(
            {
                "itemId": item_id,
                "topk": topk,
                "items": result.items,
                "fallback": result.fallback,
            }
        ).to_dict()
    )


@router.post("/internal/recommend/train")
async def trigger_train(request: Request) -> JSONResponse:
    """T4.2:异步 fire-and-forget。trigger 内拿 NX 锁 + 插 RUNNING 行 + executor 跑训练。"""
    train_job = request.app.state.train_job
    info = await train_job.trigger()
    return JSONResponse(Result.ok(info).to_dict())
