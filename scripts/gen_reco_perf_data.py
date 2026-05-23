"""为 T4.2 性能压测生成合成 behavior_event 数据。

用 zipf 分布产 item 偏好(头部热门 + 长尾),user 随机访问 item;action_type 按概率分布
(view 70% / cart 20% / order 10%)。直接 INSERT 进 shopsphere_reco.behavior_event。

用法:
  python scripts/gen_reco_perf_data.py --rows 100000 \
    --host localhost --user shopsphere --password ... --db shopsphere_reco

调用前确保 Alembic 已 upgrade head(behavior_event 表存在)。
"""
from __future__ import annotations

import argparse
import os
import random
import sys
import uuid
from datetime import datetime, timedelta, timezone

import numpy as np
import pymysql


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--rows", type=int, default=100_000, help="合成行数")
    p.add_argument("--users", type=int, default=5_000)
    p.add_argument("--items", type=int, default=2_000)
    p.add_argument("--days", type=int, default=30, help="时间分布在过去 N 天")
    p.add_argument("--host", default=os.getenv("MYSQL_HOST", "127.0.0.1"))
    p.add_argument("--port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")))
    p.add_argument("--user", default=os.getenv("MYSQL_USER", "shopsphere"))
    p.add_argument("--password", default=os.getenv("MYSQL_PASSWORD", ""))
    p.add_argument("--db", default="shopsphere_reco")
    p.add_argument("--batch-size", type=int, default=2_000)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument(
        "--truncate", action="store_true", help="先清空 behavior_event(谨慎)"
    )
    return p.parse_args()


def main() -> int:
    args = parse_args()
    random.seed(args.seed)
    rng = np.random.default_rng(args.seed)

    # zipf 偏好:item index 0 最热,长尾衰减。clip 到 [0, items-1]
    zipf_raw = rng.zipf(a=1.3, size=args.rows)
    item_idx = np.clip(zipf_raw - 1, 0, args.items - 1)
    user_idx = rng.integers(0, args.users, size=args.rows)
    actions = rng.choice(
        ["view", "cart", "order"], size=args.rows, p=[0.7, 0.2, 0.1]
    )
    # 时间均匀分布在过去 days 天
    now = datetime.now(timezone.utc)
    seconds_back = rng.integers(0, args.days * 86_400, size=args.rows)
    timestamps = [now - timedelta(seconds=int(s)) for s in seconds_back]

    conn = pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.db,
        autocommit=False,
        charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            if args.truncate:
                cur.execute("DELETE FROM behavior_event")
                conn.commit()
                print("[INFO] behavior_event truncated", flush=True)

            sql = (
                "INSERT IGNORE INTO behavior_event "
                "(event_id, user_id, item_id, action_type, source, ts) "
                "VALUES (%s, %s, %s, %s, %s, %s)"
            )
            inserted = 0
            batch: list[tuple] = []
            for i in range(args.rows):
                row = (
                    uuid.uuid4().hex,  # 32hex,与 User 侧 BehaviorEvent.eventId 同形
                    int(user_idx[i] + 1),  # +1 避开 0
                    int(item_idx[i] + 1),
                    str(actions[i]),
                    "behavior",
                    timestamps[i].strftime("%Y-%m-%d %H:%M:%S"),
                )
                batch.append(row)
                if len(batch) >= args.batch_size:
                    cur.executemany(sql, batch)
                    conn.commit()
                    inserted += len(batch)
                    batch.clear()
                    if inserted % 20_000 == 0:
                        print(f"[INFO] inserted {inserted}/{args.rows}", flush=True)
            if batch:
                cur.executemany(sql, batch)
                conn.commit()
                inserted += len(batch)
            print(
                f"[DONE] inserted={inserted} users={args.users} items={args.items} "
                f"days={args.days}",
                flush=True,
            )
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
