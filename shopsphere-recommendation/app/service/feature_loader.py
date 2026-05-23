"""行为数据加载（自有库 shopsphere_reco.behavior_event）。

T4.2:从推荐自有库分批读近 N 天行为,server-side cursor + chunksize 控制内存。
不直连 User 库（CLAUDE.md 铁律 + docs/api-contracts.md §7 拍板）。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Iterator

import pandas as pd
from sqlalchemy import Engine, text

# 隐式反馈打分(任务文规定)
ACTION_WEIGHTS: dict[str, float] = {"view": 1.0, "cart": 3.0, "order": 5.0}


def load_behavior_window(
    engine: Engine, *, days: int, chunksize: int
) -> Iterator[pd.DataFrame]:
    """流式读取近 days 天 behavior_event;每 chunk 列 [user_id, item_id, action_type, ts]。

    `stream_results=True` 让 PyMySQL 走 server-side cursor,避免 fetch-all 撑爆内存。
    `ORDER BY id` 保证多 chunk 之间样本顺序稳定(便于复现 bug)。
    """
    sql = text(
        "SELECT user_id, item_id, action_type, ts FROM behavior_event "
        "WHERE ts >= :since ORDER BY id"
    )
    since = datetime.now(timezone.utc) - timedelta(days=days)
    with engine.connect().execution_options(stream_results=True) as conn:
        for chunk in pd.read_sql(sql, conn, params={"since": since}, chunksize=chunksize):
            yield chunk
