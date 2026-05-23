"""ItemCF 离线相似度计算（T4.2）。

数据源:推荐自有库 `shopsphere_reco.behavior_event`(MQ 消费写入,见 §7)。
算法:
  1. 隐式反馈打分 view=1 / cart=3 / order=5
  2. user × item CSR 稀疏矩阵
  3. 列(item)方向 L2 归一化 → sim = X^T @ X
  4. 热门惩罚 sim[i,j] /= log(1 + popularity[j])
  5. 每个 item 取 Top-N 邻居,写 Redis ZSET `sim:item:{itemId}`

冷启动备用:近 7 天 sum(weight) Top 100 → ZSET `hot:items:global`。

入口 `train_itemcf` 是纯同步函数(pandas/scipy/numpy),由 TrainJob 在 executor 线程调用。
失败由调用方捕获,函数本身不 catch —— 异常语义透明,便于 t_train_log.error 记录。
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd
import scipy.sparse as sp
from redis import Redis as SyncRedis
from sklearn.preprocessing import normalize
from sqlalchemy import Engine

from app.service.feature_loader import ACTION_WEIGHTS, load_behavior_window

logger = logging.getLogger(__name__)

# Redis key 常量（与 T4.3 在线召回共享）
KEY_SIM_PREFIX = "sim:item:"
KEY_HOT_GLOBAL = "hot:items:global"
KEY_MODEL_READY = "reco:model:ready"


@dataclass
class TrainResult:
    user_count: int
    item_count: int
    behavior_count: int
    sim_index_size: int  # 实际写入 sim:item:* 键个数
    hot_size: int  # hot:items:global ZSET 元素数
    elapsed_ms: int


# ---------- Part 1：矩阵构建 ----------


def _aggregate_chunks(
    chunks: Iterable[pd.DataFrame],
) -> tuple[pd.DataFrame, int]:
    """逐 chunk groupby(user_id,item_id).sum(weight) → 外层再合并。

    流式聚合避免一次性持有原始 10w 行 DataFrame。返回 (汇总 DF, 原始行计数)。
    汇总 DF 列:[user_id, item_id, weight]。
    """
    parts: list[pd.DataFrame] = []
    behavior_count = 0
    for chunk in chunks:
        behavior_count += len(chunk)
        # action_type 不在 ACTION_WEIGHTS 的 → 0 权重,后续 sum 不影响
        chunk = chunk.assign(weight=chunk["action_type"].map(ACTION_WEIGHTS).fillna(0.0))
        agg = (
            chunk.groupby(["user_id", "item_id"], as_index=False)["weight"]
            .sum()
        )
        # 丢掉 0 权重(只在 chunk 内出现未知 action_type 时才会出现)
        agg = agg[agg["weight"] > 0]
        if not agg.empty:
            parts.append(agg)
    if not parts:
        return pd.DataFrame(columns=["user_id", "item_id", "weight"]), behavior_count
    merged = pd.concat(parts, ignore_index=True)
    merged = merged.groupby(["user_id", "item_id"], as_index=False)["weight"].sum()
    return merged, behavior_count


def build_user_item_matrix(
    chunks: Iterable[pd.DataFrame],
) -> tuple[sp.csr_matrix, np.ndarray, np.ndarray, int]:
    """累加 chunks → user × item CSR。

    Returns:
      csr: shape (n_users, n_items),float64
      user_ids: shape (n_users,) 真实 user_id,sorted
      item_ids: shape (n_items,) 真实 item_id,sorted
      behavior_count: 原始 chunk 累积行数(写 t_train_log.behavior_count)
    """
    agg, behavior_count = _aggregate_chunks(chunks)
    if agg.empty:
        return (
            sp.csr_matrix((0, 0), dtype=np.float64),
            np.array([], dtype=np.int64),
            np.array([], dtype=np.int64),
            behavior_count,
        )

    user_ids = np.sort(agg["user_id"].unique().astype(np.int64))
    item_ids = np.sort(agg["item_id"].unique().astype(np.int64))

    row = np.searchsorted(user_ids, agg["user_id"].to_numpy(dtype=np.int64))
    col = np.searchsorted(item_ids, agg["item_id"].to_numpy(dtype=np.int64))
    data = agg["weight"].to_numpy(dtype=np.float64)

    csr = sp.coo_matrix(
        (data, (row, col)), shape=(len(user_ids), len(item_ids)), dtype=np.float64
    ).tocsr()
    return csr, user_ids, item_ids, behavior_count


# ---------- Part 2：相似度 ----------


def compute_item_sim(
    ui: sp.csr_matrix,
    *,
    sim_topn: int,
    pop_log_floor: float = 1.0,
    row_block_size: int = 1000,
) -> tuple[sp.csr_matrix, np.ndarray]:
    """对列做 L2 归一化 → sim = X^T @ X → 对角清零 → 热门惩罚 → 行 Top-N。

    Returns:
      sim_topn: CSR (n_items, n_items),每行至多 sim_topn 个非零,对角清零。
      popularity: shape (n_items,) item 的活跃用户数(>0 权重的用户数)。
    """
    n_items = ui.shape[1]
    if n_items == 0:
        return sp.csr_matrix((0, 0), dtype=np.float64), np.array([], dtype=np.int64)

    # L2 列归一化(sklearn 对稀疏安全;零向量保持为零)
    ui_norm = normalize(ui, norm="l2", axis=0, copy=False)

    # sim = X^T @ X(item×item),CSR
    sim = (ui_norm.T @ ui_norm).tocsr()

    # 对角清零(item 自相似永远 1,不推回自己)
    sim.setdiag(0.0)
    sim.eliminate_zeros()

    # 热门惩罚:popularity = 每个 item 的活跃用户数(>0)
    popularity = np.asarray((ui > 0).sum(axis=0), dtype=np.float64).ravel()
    penalty = np.log(1.0 + np.maximum(popularity, pop_log_floor))
    # 按列(j)除惩罚:sim[i,j] /= log(1+popularity[j])
    inv_penalty = 1.0 / penalty
    # 列方向缩放:用对角矩阵右乘
    sim = sim @ sp.diags(inv_penalty)
    sim = sim.tocsr()

    # 行 Top-N:分块处理,每块转 dense → argpartition → 重建 CSR
    top_rows_indptr: list[int] = [0]
    top_rows_indices: list[np.ndarray] = []
    top_rows_data: list[np.ndarray] = []
    total_nnz = 0

    for start in range(0, n_items, row_block_size):
        end = min(start + row_block_size, n_items)
        block = sim[start:end].toarray()  # shape (block_size, n_items)
        for r in range(block.shape[0]):
            scores = block[r]
            # 非零下标(skip 全 0 行,常见于稀少 item)
            nz = np.where(scores > 0)[0]
            if nz.size == 0:
                top_rows_indptr.append(total_nnz)
                continue
            if nz.size > sim_topn:
                # 在非零里取 Top-N
                nz_scores = scores[nz]
                top_part = np.argpartition(-nz_scores, sim_topn - 1)[:sim_topn]
                sel = nz[top_part]
                sel_scores = scores[sel]
            else:
                sel = nz
                sel_scores = scores[sel]
            # 按 score 降序排,便于落 ZSET / 后续 trimming
            order = np.argsort(-sel_scores)
            sel = sel[order]
            sel_scores = sel_scores[order]
            top_rows_indices.append(sel.astype(np.int32))
            top_rows_data.append(sel_scores.astype(np.float64))
            total_nnz += sel.size
            top_rows_indptr.append(total_nnz)

    if not top_rows_indices:
        return sp.csr_matrix((n_items, n_items), dtype=np.float64), popularity

    sim_topn_csr = sp.csr_matrix(
        (
            np.concatenate(top_rows_data),
            np.concatenate(top_rows_indices),
            np.array(top_rows_indptr, dtype=np.int64),
        ),
        shape=(n_items, n_items),
        dtype=np.float64,
    )
    return sim_topn_csr, popularity


# ---------- Part 3：写 Redis 索引 ----------


def write_sim_index(
    redis: SyncRedis,
    item_ids: np.ndarray,
    sim_csr: sp.csr_matrix,
    *,
    ttl_seconds: int,
    batch_size: int = 200,
) -> int:
    """写 sim:item:{itemId} ZSET(score=sim, member=neighbor_itemId)。

    每 key 内 DEL → ZADD → EXPIRE 三步;先 DEL 避免旧邻居残留与新结果并集化。
    pipeline 非事务模式,200 个 key 一次 flush,降 RTT。
    """
    indptr = sim_csr.indptr
    indices = sim_csr.indices
    data = sim_csr.data

    written = 0
    pipe = redis.pipeline(transaction=False)
    for row, item_id in enumerate(item_ids):
        start, end = int(indptr[row]), int(indptr[row + 1])
        if start == end:
            continue
        neighbors = indices[start:end]
        scores = data[start:end]
        mapping = {
            str(int(item_ids[j])): float(s)
            for j, s in zip(neighbors, scores, strict=True)
        }
        key = f"{KEY_SIM_PREFIX}{int(item_id)}"
        pipe.delete(key)
        pipe.zadd(key, mapping)
        pipe.expire(key, ttl_seconds)
        written += 1
        if written % batch_size == 0:
            pipe.execute()
            pipe = redis.pipeline(transaction=False)
    # flush 残留
    pipe.execute()
    return written


def write_hot_global(
    engine: Engine,
    redis: SyncRedis,
    *,
    days: int,
    topn: int,
    ttl_seconds: int,
    chunksize: int = 10_000,
) -> int:
    """近 days 天 behavior_event 按 (item_id) 聚合 sum(weight) Top N → ZSET hot:items:global。"""
    totals: dict[int, float] = {}
    for chunk in load_behavior_window(engine, days=days, chunksize=chunksize):
        weights = chunk["action_type"].map(ACTION_WEIGHTS).fillna(0.0)
        per_item = (
            chunk.assign(w=weights).groupby("item_id")["w"].sum().to_dict()
        )
        for k, v in per_item.items():
            totals[int(k)] = totals.get(int(k), 0.0) + float(v)

    if not totals:
        # 没数据 → 删除旧 key 避免 stale 兜底
        redis.delete(KEY_HOT_GLOBAL)
        return 0

    top = sorted(totals.items(), key=lambda kv: kv[1], reverse=True)[:topn]
    mapping = {str(k): float(v) for k, v in top}
    pipe = redis.pipeline(transaction=False)
    pipe.delete(KEY_HOT_GLOBAL)
    pipe.zadd(KEY_HOT_GLOBAL, mapping)
    pipe.expire(KEY_HOT_GLOBAL, ttl_seconds)
    pipe.execute()
    return len(mapping)


# ---------- 入口 ----------


def train_itemcf(
    engine: Engine,
    redis: SyncRedis,
    *,
    window_days: int = 30,
    chunksize: int = 10_000,
    sim_topn: int = 50,
    hot_topn: int = 100,
    hot_window_days: int = 7,
    ttl_seconds: int = 25 * 3600,
    ready_ttl_seconds: int = 26 * 3600,
) -> TrainResult:
    """ItemCF 训练入口(同步)。所有异常向上抛,由 TrainJob 落 t_train_log.FAILED。"""
    t0 = time.perf_counter()

    logger.info("itemcf: load window_days=%d chunksize=%d", window_days, chunksize)
    chunks = load_behavior_window(engine, days=window_days, chunksize=chunksize)
    ui, user_ids, item_ids, behavior_count = build_user_item_matrix(chunks)
    logger.info(
        "itemcf: matrix shape=(%d,%d) nnz=%d behavior_count=%d",
        ui.shape[0],
        ui.shape[1],
        ui.nnz,
        behavior_count,
    )

    if ui.shape[1] == 0:
        # 无数据,仅写 hot(也将为 0) + 不置 ready 标志
        hot_size = write_hot_global(
            engine, redis, days=hot_window_days, topn=hot_topn, ttl_seconds=ttl_seconds
        )
        return TrainResult(
            user_count=0,
            item_count=0,
            behavior_count=behavior_count,
            sim_index_size=0,
            hot_size=hot_size,
            elapsed_ms=int((time.perf_counter() - t0) * 1000),
        )

    sim_csr, _popularity = compute_item_sim(ui, sim_topn=sim_topn)
    logger.info("itemcf: sim nnz=%d (avg %.1f per item)", sim_csr.nnz, sim_csr.nnz / max(len(item_ids), 1))

    sim_size = write_sim_index(redis, item_ids, sim_csr, ttl_seconds=ttl_seconds)
    hot_size = write_hot_global(
        engine, redis, days=hot_window_days, topn=hot_topn, ttl_seconds=ttl_seconds
    )

    # 全部就绪才置 ready(失败由上层抛出,不会跑到这里)
    redis.set(KEY_MODEL_READY, "1", ex=ready_ttl_seconds)

    elapsed_ms = int((time.perf_counter() - t0) * 1000)
    logger.info(
        "itemcf: done users=%d items=%d sim_keys=%d hot=%d elapsed_ms=%d",
        len(user_ids),
        len(item_ids),
        sim_size,
        hot_size,
        elapsed_ms,
    )
    return TrainResult(
        user_count=int(len(user_ids)),
        item_count=int(len(item_ids)),
        behavior_count=behavior_count,
        sim_index_size=sim_size,
        hot_size=hot_size,
        elapsed_ms=elapsed_ms,
    )
