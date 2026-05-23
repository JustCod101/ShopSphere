# 推荐系统 ItemCF 离线训练性能报告（T4.2）

## 验收结论

10w 与 50w 行为规模下，端到端训练总耗时与峰值内存均通过验收门槛（<60s & <1GB）：

| 规模      | TOTAL  | RSS_PEAK  | 验收门槛       | 结果   |
| --------- | ------ | --------- | -------------- | ------ |
| 10w 行为  | 0.50s  | 225.2 MB  | <60s & <1GB    | PASS   |
| 50w 行为  | 2.14s  | 448.1 MB  | <60s & <1GB    | PASS   |

两档均在 3 秒内完成，距离 60s 红线还有 25x 以上余量；RSS 峰值 50w 档约 448 MB，距离 1GB 红线 2x 以上余量。

## 测试环境

- 平台：macOS arm64 (Darwin 25.5.0)
- 芯片：Apple Silicon，10 cores
- 运行时：Python 3.10.20
- 依赖：pandas / scipy.sparse / scikit-learn(normalize, BLAS 加速) / numpy / fakeredis
- 存储替身：sqlite（内存库）替代 MySQL；fakeredis（进程内）替代 Redis
- 目的：纯算法 CPU + 内存压测，不包含真实 MySQL 网络 IO 与 Redis 网络 RTT

## 数据规模与生成方式

| 规模      | users  | items  | 行为条数 |
| --------- | ------ | ------ | -------- |
| 10w       | 5000   | 2000   | 100,000  |
| 50w       | 20000  | 5000   | 500,000  |

行为生成（脚本 scripts/bench_itemcf.py）：

- item 分布：zipf a=1.3，模拟头部偏好，少量爆品 + 长尾
- action 分布：view 70% / cart 20% / order 10%
- ts：当前时间向前回溯 30 天均匀洒点
- 落库：sqlite 单表 behavior_event(id auto, user_id, item_id, action_type, ts)

实际 user × item 稀疏矩阵 shape 与 nnz：

- 10w：shape=(5000, 1833) nnz=61840（实际命中 user ≈ 5000，命中 item ≈ 1833，因 zipf 头部聚集 + 同 (u,i) 多 action 合并）
- 50w：shape=(20000, 4845) nnz=300728

## 各阶段耗时

| 阶段                  | 10w (ms) | 50w (ms) | 说明                                       |
| --------------------- | -------- | -------- | ------------------------------------------ |
| load + build_matrix   | 132      | 837      | 流式 read_sql + groupby sum + searchsorted |
| compute_item_sim      | 18       | 72       | L2 列归一化 → X^T@X → 惩罚 → 行 Top-N      |
| write_sim_index       | 321      | 1033     | DEL+ZADD+EXPIRE，pipeline batch=200        |
| write_hot_global      | 24       | 194      | 近 7 天 sum(weight) Top 100 → ZSET         |
| TOTAL                 | 495      | 2136     |                                            |

相似矩阵规模：

- 10w：sim_nnz=61356，avg_neighbors=33.47（sim_topn=50 上限内）
- 50w：sim_nnz=198420，avg_neighbors=40.95

## 内存峰值

| 规模 | RSS_PEAK |
| ---- | -------- |
| 10w  | 225.2 MB |
| 50w  | 448.1 MB |

50w 档峰值主要来自：

- user × item CSR（float64，nnz≈30w，约 7 MB 实际数据 + indptr/indices）
- L2 归一化中间态 + sim = X^T@X 的中间稀疏矩阵
- 行 Top-N 分块 toarray() 时单块 dense（row_block_size=1000, n_items=4845 → 每块约 38 MB float64）
- pandas DataFrame 与 sqlite 驱动的副本开销

两档均远低于 1GB 红线。

## 性能要点解读

为什么 50w 行为只需 2.14s：

1. 稀疏矩阵 nnz 远小于全行数。zipf 头部聚集 + (u,i) 聚合后，nnz 约为原始行数的 60%，且 user × item 稠密度 < 0.4%，所有矩阵运算都跑在 CSR 上。
2. sklearn normalize 走 BLAS。L2 列归一化对 CSR 是一次扫 nnz 的列范数计算 + 一次原地除法，O(nnz)，且向量化路径走 BLAS / SIMD。
3. sim = X^T @ X 在稀疏下复杂度约 nnz² / n_users。10w 档约 61840² / 5000 ≈ 7.6e5 次乘加，50w 档约 300728² / 20000 ≈ 4.5e6 次，scipy CSR 矩阵乘法直接吃满单核 BLAS。
4. 行 Top-N 用 argpartition O(n_items) 而非全排序 O(n_items log n_items)，且仅对非零下标排，进一步压缩。
5. write_sim_index 用 pipeline transaction=False + batch=200 摊薄 RTT。在 fakeredis 下虽无网络，仍能看到 batch 写比逐条 ZADD 显著快；真实 Redis 部署下 RTT 摊薄收益更大。
6. write_hot_global 在 dict 内做内存聚合后一次 pipeline 落 ZSET（DEL+ZADD+EXPIRE），Top 100 排序 O(n_items log n_items)，量级可忽略。

## 真实环境差异说明

本次压测使用 sqlite + fakeredis，与生产 MySQL + Redis 的差异：

- MySQL 网络 IO：server-side cursor + chunksize=10000，单 chunk RT 估 5-20ms，50w 行 50 个 chunk 累积 250ms-1s 量级，远小于训练计算耗时。
- Redis 网络 RTT：每个 pipeline batch 内同机房 RTT 约 0.3-0.5ms，batch=200 下 50w 档 4845 keys ≈ 25 个 batch，叠加耗时 < 100ms。
- pandas read_sql 在真实 PyMySQL 驱动下比 sqlite 略慢（解码/类型转换），10w 档预估额外 +200-500ms。

综合估算：真实环境下 10w 档总耗时约 1-2s，50w 档约 3-5s，仍远低于 60s 红线。生产监控应关注的是 MySQL 慢查询（behavior_event 表 ts 索引必须存在）与 Redis pipeline 是否被网络抖动打散。

## 后续优化方向

按规模触发节点排列：

1. n_items 10k+ 时切 sparse blocked Top-N。当前 compute_item_sim 在行 Top-N 阶段对每个 row_block 调 toarray()，单块 dense 内存 = row_block_size * n_items * 8 字节。n_items 到 10k 时单块 80MB、20k 时 160MB，应改为纯稀疏路径：sim 已是 CSR，按行切片直接拿 indices/data 做 argpartition，省掉 toarray。
2. Redis pipeline batch 调优。当前 batch_size=200 是经验值，真实部署下应结合网卡 MTU 与单 ZADD 元素数（≤sim_topn=50）实测。批太大会撑 Redis output buffer，批太小 RTT 摊不薄。建议加 Prometheus 指标 reco_train_redis_batch_ms 后线上调参。
3. Alembic 加 idx_ts 单列索引。当前 behavior_event 上 ts 字段如果无索引，load_behavior_window 的 WHERE ts >= :since 会全表扫描；ORDER BY id 在 InnoDB 主键扫描下顺路出，但过滤代价仍在 ts。建议补 idx_ts(ts) 或复合 idx(ts, id)。
4. compute_item_sim 多核化。scipy 稀疏矩阵乘默认单核，n_items 上量 + 多核机器下可考虑 implicit / lightfm 替代，或自己按行分块并行计算 X[:, block].T @ X。
5. ttl_seconds 与 train_cron 联动校验。当前 sim:item:* 默认 TTL=25h，train_cron 默认 daily 02:00 UTC，留 1h 缓冲；若 cron 改为更稀疏（例如每 6h），需同步调小 TTL 或反过来；建议在 build_settings 时做断言。

## 复测脚本路径

- /Users/numbbot/IdeaProjects/ShopSphere/shopsphere-recommendation/scripts/bench_itemcf.py
