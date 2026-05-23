"""TrainJob 锁与状态机单测 —— 用 fakeredis(sync+async) + sqlite 内存库,不连真实基础设施。

要点验证:
1. trigger() 拿锁成功 → INSERT t_train_log RUNNING → 立即返回 triggered=True+runId。
2. 锁已被占 → trigger 立返 already_running,不增 t_train_log。
3. mock train_fn 抛 → _run_sync 捕获 → t_train_log 更新 FAILED + error 非空 + 锁释放。
"""
from __future__ import annotations

import asyncio
import time

import fakeredis
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.core.config import AppSettings, ModelSettings, MysqlSettings, RabbitMQSettings, RedisSettings
from app.service.itemcf import TrainResult
from app.tasks.train_job import LOCK_KEY, TrainJob


@pytest.fixture
def engine():
    """sqlite 内存库;StaticPool + check_same_thread=False 让 executor 线程也能用同一 in-memory DB。

    手写建表 SQL(INTEGER PRIMARY KEY AUTOINCREMENT),绕开 BigInteger 在 sqlite 上不自增的限制 ——
    真实 MySQL 用 BIGINT 自增,本测试只验 TrainJob 行为不验 schema。
    """
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
                CREATE TABLE t_train_log (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  started_at TIMESTAMP NOT NULL,
                  finished_at TIMESTAMP NULL,
                  item_count INTEGER NULL,
                  user_count INTEGER NULL,
                  behavior_count INTEGER NULL,
                  status VARCHAR(16) NOT NULL,
                  error VARCHAR(500) NULL
                )
                """
            )
        )
    yield eng
    eng.dispose()


@pytest.fixture
def fake_redis_pair():
    """fakeredis 同步 + 异步 → 共享 server,模拟同一 Redis 实例。"""
    server = fakeredis.FakeServer()
    sync = fakeredis.FakeRedis(server=server, decode_responses=True)
    async_ = fakeredis.aioredis.FakeRedis(server=server, decode_responses=True)
    yield async_, sync


@pytest.fixture
def settings():
    return AppSettings.model_validate(
        {
            "profile": "test",
            "mysql": MysqlSettings(host="x", user="x", password="x"),
            "redis": RedisSettings(host="x"),
            "rabbitmq": RabbitMQSettings(host="x", user="x", password="x"),
            "model": ModelSettings(),
        }
    )


def _make_job(engine, redis_pair, settings, train_fn):
    async_, sync = redis_pair
    return TrainJob(engine, async_, sync, settings, train_fn=train_fn)


def _ok_train_fn(*_a, **_kw):
    return TrainResult(
        user_count=2, item_count=3, behavior_count=4,
        sim_index_size=3, hot_size=0, elapsed_ms=10,
    )


def _boom_train_fn(*_a, **_kw):
    raise RuntimeError("synthetic failure")


@pytest.mark.asyncio
async def test_trigger_acquires_lock_and_inserts_running(engine, fake_redis_pair, settings):
    """trigger 后立刻应有一行 t_train_log;状态可能是 RUNNING(executor 未跑完)或 SUCCESS(已跑完)。
    本测验插入行为 + 锁占用,不验最终状态(由 success/failed 专项测覆盖)。"""
    # 用一个会阻塞的 train_fn 确保 trigger 返回时尚未跑完
    import threading

    release = threading.Event()

    def _blocking_train(*_a, **_kw):
        release.wait(timeout=2.0)
        return TrainResult(
            user_count=0, item_count=0, behavior_count=0,
            sim_index_size=0, hot_size=0, elapsed_ms=0,
        )

    job = _make_job(engine, fake_redis_pair, settings, _blocking_train)
    info = await job.trigger()
    assert info["triggered"] is True
    assert isinstance(info["runId"], int)
    # 锁已被占
    async_, _sync = fake_redis_pair
    assert await async_.get(LOCK_KEY) == "1"
    # t_train_log 有一行 RUNNING(executor 尚未跑完)
    with engine.connect() as conn:
        rows = conn.execute(
            text("SELECT id, status FROM t_train_log ORDER BY id")
        ).all()
    assert len(rows) == 1
    assert rows[0][1] == "RUNNING"
    # 放行 executor,避免下个测继承挂起线程
    release.set()


@pytest.mark.asyncio
async def test_trigger_blocked_when_lock_held(engine, fake_redis_pair, settings):
    async_, _sync = fake_redis_pair
    # 预先占锁
    await async_.set(LOCK_KEY, "1", ex=3600)

    job = _make_job(engine, fake_redis_pair, settings, _ok_train_fn)
    info = await job.trigger()
    assert info == {"triggered": False, "reason": "already_running"}
    # 不应写 t_train_log
    with engine.connect() as conn:
        count = conn.execute(text("SELECT COUNT(*) FROM t_train_log")).scalar()
    assert count == 0


@pytest.mark.asyncio
async def test_exception_in_training_marks_failed_and_releases_lock(
    engine, fake_redis_pair, settings
):
    job = _make_job(engine, fake_redis_pair, settings, _boom_train_fn)
    info = await job.trigger()
    assert info["triggered"] is True
    run_id = info["runId"]

    # 等 executor 跑完(_run_sync 在 ThreadPoolExecutor 里)
    async_, sync_ = fake_redis_pair
    deadline = time.time() + 15.0
    status = None
    while time.time() < deadline:
        with engine.connect() as conn:
            status = conn.execute(
                text("SELECT status, error FROM t_train_log WHERE id = :i"),
                {"i": run_id},
            ).first()
        if status and status[0] == "FAILED" and sync_.get(LOCK_KEY) is None:
            break
        await asyncio.sleep(0.05)

    assert status is not None
    assert status[0] == "FAILED"
    assert "synthetic failure" in (status[1] or "")
    assert sync_.get(LOCK_KEY) is None


@pytest.mark.asyncio
async def test_successful_training_marks_success_and_releases_lock(
    engine, fake_redis_pair, settings
):
    job = _make_job(engine, fake_redis_pair, settings, _ok_train_fn)
    info = await job.trigger()
    run_id = info["runId"]

    _, sync_ = fake_redis_pair
    # 等 status=SUCCESS **且** 锁已释放（finally 在 _update_success 之后,这两个之间有窗口）
    deadline = time.time() + 15.0
    row = None
    while time.time() < deadline:
        with engine.connect() as conn:
            row = conn.execute(
                text(
                    "SELECT status, user_count, item_count, behavior_count "
                    "FROM t_train_log WHERE id = :i"
                ),
                {"i": run_id},
            ).first()
        if row and row[0] == "SUCCESS" and sync_.get(LOCK_KEY) is None:
            break
        await asyncio.sleep(0.05)

    assert row is not None
    assert row[0] == "SUCCESS"
    assert row[1] == 2
    assert row[2] == 3
    assert row[3] == 4
    assert sync_.get(LOCK_KEY) is None
