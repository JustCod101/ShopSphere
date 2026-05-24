# ADR-005: 推荐自有库 + 事件驱动管道

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-23(commit `d059843` T4.1)
- 关联: [api-contracts §7](../api-contracts.md) / [integration-recommend.md](../integration-recommend.md) / `shopsphere-recommendation/`

## 背景

推荐服务需要消费**用户行为**(浏览/点击/加购/下单)和**商品快照**才能训练 ItemCF。两个数据来源都在 Java 侧。常见集成:

- 直接读 User/Order 的 MySQL — 跨服务读库,违反 CLAUDE.md "禁止跨服务直接访问对方数据库"
- Java 侧暴露查询接口给 Py 拉取 — Py 强耦 Java 接口,推荐慢则拖累 Java(熔断/超时)
- 事件总线(MQ)+ Py 自有库 — 解耦最强;需保证事件不丢

## 候选方案

- **A. 共享数据库 view** — 最快;违反子域独立、跨语言连接池调优痛
- **B. Java 暴露 read-only API,Py 定时 pull** — 一致性可控;Py 强依赖 Java SLA
- **C. MQ 事件驱动 + Py 自有库 + APScheduler 离线训练** — 完全解耦;Py 离线/在线都可独立扩容,但需保证 at-least-once

## 决策

选 **C**。

- **数据库**:`shopsphere_reco`(独立 schema,见 `01-init-mysql.sql`),Py 通过 SQLAlchemy/asyncpg 访问
- **事件源**:
  - 用户行为 → `behavior.exchange` → 推荐消费(M4 轻量直发,见 [ADR-008](ADR-008-mq-reliability-tiers.md))
  - 订单已支付 → `order.exchange` → 推荐消费(M1 强可靠 + publisher-confirm)
- **离线训练**:APScheduler 定时跑 ItemCF,产物落 `reco_item_similarity` 表,加 TTL/版本
- **在线召回**:`/api/recommend/personal?userId=&n=10` 走 Gateway,Py 直接返,**冷启动 fallback**(用户/商品无数据时返热门 top-N)
- **去 Java→Py Feign**:见 [ADR-006](ADR-006-no-java-py-feign.md)

## 后果

**正面**
- Py 增减 / 推荐算法迭代不影响 Java 服务部署节奏
- 数据完整性可演练 MQ 可靠性投递(ADR-008)
- Reco 失联时 Java 业务零影响;前端通过 Gateway fallback 拿热门推荐

**负面 / 代价**
- 数据一致性是最终一致(MQ 延迟 +消费失败重试 = 秒~分钟级)
- Reco 自有库的数据是事件投递得来的视图,不可作为业务源(只读 / 训练用)
- MQ 拓扑复杂度上升:behavior + order 两个 exchange,各自有重试/死信(见 [mq-topology.md](../mq-topology.md))

**后续动作**
- 任何新增需要历史业务数据的 ML 任务,先评估能否走 MQ 事件;**禁止给 Py 服务任何 Java 库连接**
