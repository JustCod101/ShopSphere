"""单机离线 benchmark —— sqlite + fakeredis,不依赖 MySQL/Redis。

用法:
  .venv/bin/python scripts/bench_itemcf.py --rows 100000

输出每阶段耗时(load / build_matrix / sim / write_sim / write_hot),RSS 峰值。
仅做算法 CPU+内存压测;真实环境 IO 由 MySQL/Redis 网络决定,数量级一致。
"""
from __future__ import annotations

import argparse
import resource
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 把 app/ 加进来
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import fakeredis  # noqa: E402
import numpy as np  # noqa: E402
from sqlalchemy import create_engine, text  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app.service.itemcf import (  # noqa: E402
    build_user_item_matrix,
    compute_item_sim,
    write_hot_global,
    write_sim_index,
)
from app.service.feature_loader import load_behavior_window  # noqa: E402


def _prepare_db(rows: int, users: int, items: int, days: int, seed: int):
    """sqlite in-memory 建 behavior_event 表 + 灌数据。返回 engine。"""
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
                  ts TIMESTAMP NOT NULL,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
        )
        conn.execute(text("CREATE INDEX idx_user_ts ON behavior_event(user_id, ts)"))
        conn.execute(text("CREATE INDEX idx_item_ts ON behavior_event(item_id, ts)"))

    rng = np.random.default_rng(seed)
    item_idx = np.clip(rng.zipf(a=1.3, size=rows) - 1, 0, items - 1)
    user_idx = rng.integers(0, users, size=rows)
    actions = rng.choice(["view", "cart", "order"], size=rows, p=[0.7, 0.2, 0.1])
    now = datetime.now(timezone.utc)
    seconds_back = rng.integers(0, days * 86_400, size=rows)

    BATCH = 5_000
    with eng.begin() as conn:
        for start in range(0, rows, BATCH):
            end = min(start + BATCH, rows)
            params = [
                {
                    "event_id": uuid.uuid4().hex,
                    "user_id": int(user_idx[i] + 1),
                    "item_id": int(item_idx[i] + 1),
                    "action_type": str(actions[i]),
                    "source": "behavior",
                    "ts": (now - timedelta(seconds=int(seconds_back[i]))).strftime(
                        "%Y-%m-%d %H:%M:%S"
                    ),
                }
                for i in range(start, end)
            ]
            conn.execute(
                text(
                    "INSERT INTO behavior_event "
                    "(event_id, user_id, item_id, action_type, source, ts) "
                    "VALUES (:event_id, :user_id, :item_id, :action_type, :source, :ts)"
                ),
                params,
            )
    return eng


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--rows", type=int, default=100_000)
    p.add_argument("--users", type=int, default=5_000)
    p.add_argument("--items", type=int, default=2_000)
    p.add_argument("--days", type=int, default=30)
    p.add_argument("--chunksize", type=int, default=10_000)
    p.add_argument("--sim-topn", type=int, default=50)
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()

    print(
        f"[CFG] rows={args.rows} users={args.users} items={args.items} "
        f"chunksize={args.chunksize} sim_topn={args.sim_topn}"
    )

    t_prep0 = time.perf_counter()
    engine = _prepare_db(args.rows, args.users, args.items, args.days, args.seed)
    print(f"[PREP] sqlite + {args.rows} rows seeded in {time.perf_counter() - t_prep0:.2f}s")

    redis = fakeredis.FakeRedis(decode_responses=True)

    # Stage 1: load + build_matrix（两阶段合并计时,因为是流式 iterator）
    t0 = time.perf_counter()
    chunks = load_behavior_window(engine, days=args.days, chunksize=args.chunksize)
    csr, user_ids, item_ids, behavior_count = build_user_item_matrix(chunks)
    t1 = time.perf_counter()
    print(
        f"[load+build_matrix] {(t1 - t0)*1000:.0f} ms "
        f"shape=({csr.shape[0]},{csr.shape[1]}) nnz={csr.nnz} behavior_count={behavior_count}"
    )

    # Stage 2: compute_item_sim
    t2 = time.perf_counter()
    sim_csr, _pop = compute_item_sim(csr, sim_topn=args.sim_topn)
    t3 = time.perf_counter()
    print(
        f"[compute_item_sim] {(t3 - t2)*1000:.0f} ms "
        f"sim_nnz={sim_csr.nnz} avg_neighbors={sim_csr.nnz / max(len(item_ids),1):.2f}"
    )

    # Stage 3: write_sim_index
    t4 = time.perf_counter()
    n_keys = write_sim_index(redis, item_ids, sim_csr, ttl_seconds=25 * 3600)
    t5 = time.perf_counter()
    print(f"[write_sim_index] {(t5 - t4)*1000:.0f} ms keys={n_keys}")

    # Stage 4: write_hot_global
    t6 = time.perf_counter()
    hot_size = write_hot_global(
        engine, redis, days=7, topn=100, ttl_seconds=25 * 3600, chunksize=args.chunksize
    )
    t7 = time.perf_counter()
    print(f"[write_hot_global] {(t7 - t6)*1000:.0f} ms hot_size={hot_size}")

    # 总
    elapsed = t7 - t0
    rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # macOS ru_maxrss 是字节,Linux 是 KB
    if sys.platform == "darwin":
        rss_mb = rss_kb / 1024 / 1024
    else:
        rss_mb = rss_kb / 1024
    print(f"\n[TOTAL] {elapsed*1000:.0f} ms ({elapsed:.2f}s)")
    print(f"[RSS_PEAK] {rss_mb:.1f} MB")
    print(f"[PASS_60s] {'YES' if elapsed < 60 else 'NO'}")
    print(f"[PASS_1GB] {'YES' if rss_mb < 1024 else 'NO'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
