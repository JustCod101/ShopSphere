"""recall.py 召回服务单测 —— 使用 fakeredis(async) + sqlite 内存库,不连真实基础设施。

覆盖:命中缓存短路 / 模型未就绪 fallback / 用户冷启动 fallback / 正常加权聚合 +
过滤已下单 / 邻居全空 fallback / topk 截断 / load_purchased_cached miss → DB。
"""
from __future__ import annotations

import json

import fakeredis
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.service.recall import (
    PURCHASED_CACHE_TTL,
    USER_CACHE_TTL,
    USER_CACHE_TTL_SHORT,
    cache_key,
    load_purchased_cached,
    purchased_key,
    recall_for_user,
    recall_similar,
)


@pytest.fixture
def engine():
    """sqlite 内存库 + StaticPool 让 run_in_executor 线程也看到表;
    手写建表(INTEGER PK AUTOINCREMENT)避开 BigInteger 在 sqlite 上不自增的问题。"""
    eng = create_engine(
        "sqlite:///:memory:",
        future=True,
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with eng.begin() as conn:
        conn.execute(
            text(
                """
                CREATE TABLE behavior_event (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  event_id VARCHAR(64) NOT NULL UNIQUE,
                  user_id BIGINT NOT NULL,
                  item_id BIGINT NOT NULL,
                  action_type VARCHAR(16) NOT NULL,
                  source VARCHAR(16) NOT NULL,
                  ts TIMESTAMP NOT NULL
                )
                """
            )
        )
    yield eng
    eng.dispose()


@pytest.fixture
def redis_async():
    """fakeredis 异步客户端。recall 全异步。"""
    server = fakeredis.FakeServer()
    return fakeredis.aioredis.FakeRedis(server=server, decode_responses=True)


async def _seed_user_behavior(redis, user_id: int, items_with_score: list[tuple[int, float]]):
    """ZADD user:behavior:{uid}。score 越大越「近」(ZREVRANGE 取靠前)。"""
    mapping = {str(i): s for i, s in items_with_score}
    if mapping:
        await redis.zadd(f"user:behavior:{user_id}", mapping)


async def _seed_sim(redis, item_id: int, neighbors: list[tuple[int, float]]):
    mapping = {str(i): s for i, s in neighbors}
    if mapping:
        await redis.zadd(f"sim:item:{item_id}", mapping)


async def _seed_hot(redis, items_with_score: list[tuple[int, float]]):
    mapping = {str(i): s for i, s in items_with_score}
    if mapping:
        await redis.zadd("hot:items:global", mapping)


async def _mark_model_ready(redis):
    await redis.set("reco:model:ready", "1", ex=26 * 3600)


@pytest.mark.asyncio
async def test_recall_user_normal_path(engine, redis_async):
    """有 behavior + sim + 模型就绪 → 返回非空 items + fallback=False。"""
    await _mark_model_ready(redis_async)
    # 用户最近 2 个 item:100(更近, score=200) / 200(score=100)
    await _seed_user_behavior(redis_async, 1, [(100, 200.0), (200, 100.0)])
    # sim:item:100 → {300: 0.9, 400: 0.8}  ; sim:item:200 → {300: 0.5, 500: 0.7}
    await _seed_sim(redis_async, 100, [(300, 0.9), (400, 0.8)])
    await _seed_sim(redis_async, 200, [(300, 0.5), (500, 0.7)])

    result = await recall_for_user(redis_async, engine, user_id=1, topk=10)
    assert result.fallback is False
    assert result.reason is None
    # idx=0 → item 100,权重 1.0;idx=1 → item 200,权重 0.5
    # 300 = 1.0*0.9 + 0.5*0.5 = 1.15
    # 400 = 1.0*0.8 = 0.8
    # 500 = 0.5*0.7 = 0.35
    item_ids = [it["itemId"] for it in result.items]
    assert item_ids == [300, 400, 500]
    assert result.items[0]["score"] == pytest.approx(1.15)
    assert result.items[1]["score"] == pytest.approx(0.8)
    assert result.items[2]["score"] == pytest.approx(0.35)


@pytest.mark.asyncio
async def test_recall_user_no_behavior_falls_back_to_hot(engine, redis_async):
    await _mark_model_ready(redis_async)
    await _seed_hot(redis_async, [(11, 99.0), (22, 88.0), (33, 77.0), (44, 66.0), (55, 55.0)])

    result = await recall_for_user(redis_async, engine, user_id=42, topk=10)
    assert result.fallback is True
    assert result.reason == "cold-start"
    assert [it["itemId"] for it in result.items] == [11, 22, 33, 44, 55]
    # 缓存被写,TTL 应在 USER_CACHE_TTL 量级
    ttl = await redis_async.ttl(cache_key(42, 10))
    assert 0 < ttl <= USER_CACHE_TTL


@pytest.mark.asyncio
async def test_recall_user_model_not_ready_falls_back(engine, redis_async):
    # 不设 reco:model:ready
    await _seed_hot(redis_async, [(11, 9.0), (22, 8.0)])

    result = await recall_for_user(redis_async, engine, user_id=1, topk=10)
    assert result.fallback is True
    assert result.reason == "model-not-ready"
    assert [it["itemId"] for it in result.items] == [11, 22]
    # 模型未就绪时短 TTL
    ttl = await redis_async.ttl(cache_key(1, 10))
    assert 0 < ttl <= USER_CACHE_TTL_SHORT


@pytest.mark.asyncio
async def test_recall_user_filters_purchased(engine, redis_async):
    await _mark_model_ready(redis_async)
    await _seed_user_behavior(redis_async, 1, [(100, 200.0)])
    # sim 邻居里既有 300(未购) 又有 400(已购)
    await _seed_sim(redis_async, 100, [(300, 0.9), (400, 0.8)])
    # 已下单缓存
    await redis_async.sadd(purchased_key(1), "400")

    result = await recall_for_user(redis_async, engine, user_id=1, topk=10)
    item_ids = {it["itemId"] for it in result.items}
    assert 400 not in item_ids
    assert 300 in item_ids


@pytest.mark.asyncio
async def test_recall_user_purchased_cache_miss_loads_from_db(engine, redis_async):
    await _mark_model_ready(redis_async)
    await _seed_user_behavior(redis_async, 1, [(100, 200.0)])
    await _seed_sim(redis_async, 100, [(300, 0.9), (400, 0.8)])

    # 在 behavior_event 表里给 user=1 一条 action_type='order' item=400 的记录
    with engine.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO behavior_event "
                "(event_id, user_id, item_id, action_type, source, ts) "
                "VALUES ('e1', 1, 400, 'order', 'order', :ts)"
            ),
            {"ts": "2026-05-20 10:00:00"},
        )

    # purchased 缓存不存在,recall 会触发 DB 查询并写缓存
    assert await redis_async.exists(purchased_key(1)) == 0
    result = await recall_for_user(redis_async, engine, user_id=1, topk=10)
    item_ids = {it["itemId"] for it in result.items}
    assert 400 not in item_ids
    # 缓存已写入,TTL 在 PURCHASED_CACHE_TTL 量级
    cached = await redis_async.smembers(purchased_key(1))
    assert cached == {"400"}
    ttl = await redis_async.ttl(purchased_key(1))
    assert 0 < ttl <= PURCHASED_CACHE_TTL


@pytest.mark.asyncio
async def test_recall_user_cache_hit_short_circuits(engine, redis_async):
    """命中缓存直返 —— 即使 sim/model 都未设,也不抛错。"""
    payload = json.dumps(
        {"items": [{"itemId": 999, "score": 1.23}], "fallback": False}
    )
    await redis_async.set(cache_key(1, 10), payload, ex=600)

    result = await recall_for_user(redis_async, engine, user_id=1, topk=10)
    assert result.fallback is False
    assert result.items == [{"itemId": 999, "score": 1.23}]


@pytest.mark.asyncio
async def test_recall_user_empty_neighbors_falls_back(engine, redis_async):
    """有 behavior 但 sim 邻居为空 → empty-neighbors fallback。"""
    await _mark_model_ready(redis_async)
    await _seed_user_behavior(redis_async, 1, [(100, 200.0)])
    # 不写 sim:item:100
    await _seed_hot(redis_async, [(99, 1.0)])

    result = await recall_for_user(redis_async, engine, user_id=1, topk=10)
    assert result.fallback is True
    assert result.reason == "empty-neighbors"
    assert [it["itemId"] for it in result.items] == [99]


@pytest.mark.asyncio
async def test_recall_similar_normal_path(redis_async):
    await _seed_sim(redis_async, 42, [(1, 0.9), (2, 0.8), (3, 0.7)])
    result = await recall_similar(redis_async, item_id=42, topk=10)
    assert result.fallback is False
    assert [it["itemId"] for it in result.items] == [1, 2, 3]


@pytest.mark.asyncio
async def test_recall_similar_empty_falls_back_to_hot(redis_async):
    # sim:item:42 不存在
    await _seed_hot(redis_async, [(11, 9.0), (22, 8.0)])
    result = await recall_similar(redis_async, item_id=42, topk=10)
    assert result.fallback is True
    assert result.reason == "empty-neighbors"
    assert [it["itemId"] for it in result.items] == [11, 22]


@pytest.mark.asyncio
async def test_recall_topk_truncates_correctly(engine, redis_async):
    await _mark_model_ready(redis_async)
    await _seed_user_behavior(redis_async, 1, [(100, 1.0)])
    # 5 个邻居,topk=3
    await _seed_sim(
        redis_async, 100, [(201, 0.5), (202, 0.4), (203, 0.3), (204, 0.2), (205, 0.1)]
    )
    result = await recall_for_user(redis_async, engine, user_id=1, topk=3)
    assert len(result.items) == 3
    assert [it["itemId"] for it in result.items] == [201, 202, 203]


@pytest.mark.asyncio
async def test_load_purchased_cached_no_data_returns_empty(engine, redis_async):
    # 既无 Redis 缓存也无 DB 数据
    result = await load_purchased_cached(redis_async, engine, user_id=1)
    assert result == set()
    # 不应写入空缓存(避免占空间)
    assert await redis_async.exists(purchased_key(1)) == 0
