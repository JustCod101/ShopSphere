"""itemcf 算法纯函数单测 —— 不连真实 DB / Redis。"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
import scipy.sparse as sp

from app.service.feature_loader import ACTION_WEIGHTS
from app.service.itemcf import build_user_item_matrix, compute_item_sim


def _chunk(rows: list[tuple[int, int, str]]) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=["user_id", "item_id", "action_type"])


def test_build_matrix_aggregates_weights_per_user_item():
    """同一 (u,i) 多种 action 应累加权重(view+cart=4),不同 item 各自一行。"""
    chunks = iter(
        [
            _chunk(
                [
                    (1, 100, "view"),  # u1,i100 → 1
                    (1, 100, "cart"),  # u1,i100 → +3 = 4
                    (1, 200, "order"),  # u1,i200 → 5
                    (2, 100, "view"),  # u2,i100 → 1
                ]
            )
        ]
    )
    csr, user_ids, item_ids, n = build_user_item_matrix(chunks)
    assert n == 4
    assert list(user_ids) == [1, 2]
    assert list(item_ids) == [100, 200]
    dense = csr.toarray()
    # rows=user_ids index, cols=item_ids index
    assert dense[0, 0] == pytest.approx(ACTION_WEIGHTS["view"] + ACTION_WEIGHTS["cart"])
    assert dense[0, 1] == pytest.approx(ACTION_WEIGHTS["order"])
    assert dense[1, 0] == pytest.approx(ACTION_WEIGHTS["view"])
    assert dense[1, 1] == 0.0


def test_build_matrix_multi_chunk_merge():
    """跨 chunk 的同 (u,i) 也应合并求和。"""
    chunks = iter(
        [
            _chunk([(1, 100, "view"), (1, 100, "view")]),
            _chunk([(1, 100, "order")]),  # +5
        ]
    )
    csr, _, _, n = build_user_item_matrix(chunks)
    assert n == 3
    assert csr.toarray()[0, 0] == pytest.approx(1.0 + 1.0 + 5.0)


def test_build_matrix_empty_input():
    csr, user_ids, item_ids, n = build_user_item_matrix(iter([]))
    assert n == 0
    assert csr.shape == (0, 0)
    assert user_ids.size == 0
    assert item_ids.size == 0


def test_build_matrix_unknown_action_filtered():
    """未知 action_type 权重 0,聚合后应被过滤。"""
    chunks = iter([_chunk([(1, 100, "unknown_action")])])
    csr, user_ids, item_ids, _ = build_user_item_matrix(chunks)
    # 全 0 行 → 应被过滤,矩阵维度也为空
    assert csr.shape == (0, 0)
    assert user_ids.size == 0
    assert item_ids.size == 0


def test_compute_item_sim_diagonal_zero_and_topn_truncation():
    """N=10 items, sim_topn=3 → 每行非零 ≤ 3, 对角全 0。"""
    rng = np.random.default_rng(42)
    ui = sp.csr_matrix(rng.random((30, 10)) * (rng.random((30, 10)) > 0.6))
    sim, _pop = compute_item_sim(ui, sim_topn=3)
    assert sim.shape == (10, 10)
    # 对角全 0
    assert np.allclose(sim.diagonal(), 0.0)
    # 每行非零 ≤ 3
    indptr = sim.indptr
    row_nnz = np.diff(indptr)
    assert (row_nnz <= 3).all()


def test_compute_item_sim_popularity_penalty():
    """构造对称 ui:item0 仅 user0 用,item1 全部 user 用。
    cos(0,1)/log(1+1) > cos(0,1)/log(1+N) → item1 列方向被强惩罚,sim[0,1] 更小。
    """
    # 4 users; item0 单热(user0), item1 全热(user0..3)
    ui = sp.csr_matrix(
        np.array(
            [
                [1.0, 1.0],
                [0.0, 1.0],
                [0.0, 1.0],
                [0.0, 1.0],
            ]
        )
    )
    sim, popularity = compute_item_sim(ui, sim_topn=5)
    assert popularity[0] == 1.0
    assert popularity[1] == 4.0
    # item0 → item1 的边经过 item1 的列惩罚 log(1+4)
    s_01 = sim[0, 1]
    s_10 = sim[1, 0]
    # 不对称:s[0,1] /= log(1+4), s[1,0] /= log(1+1)
    assert s_10 > s_01
    # 对角清零
    assert sim[0, 0] == 0.0 and sim[1, 1] == 0.0


def test_compute_item_sim_handles_empty_matrix():
    sim, pop = compute_item_sim(sp.csr_matrix((0, 0), dtype=np.float64), sim_topn=5)
    assert sim.shape == (0, 0)
    assert pop.size == 0
