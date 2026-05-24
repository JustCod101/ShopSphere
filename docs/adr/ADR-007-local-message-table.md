# ADR-007: 本地消息表 + 同事务发件箱

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-22(commit `beaf0ad` Phase 3)
- 关联: [api-contracts §8 / C3](../api-contracts.md) / `shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java` / `shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql:42`

## 背景

订单创建成功后需要投 3 类 MQ 消息:
- `order.created` → 触发延迟取消(30 min)
- `order.paid` → 积分增、库存 Confirm、通知发送
- `behavior.order` → 推荐侧消费(ADR-005)

简单 `rabbitTemplate.convertAndSend()` 的问题:
- Send 在 commit 前调 → 业务回滚但消息已投递(脏消息)
- Send 在 commit 后调 → commit 成功但 MQ 不可达,消息丢失
- 即使加 publisher-confirm,**业务 DB 事务和 MQ 投递无法两阶段提交**

## 候选方案

- **A. RocketMQ 事务消息** — 协议级支持半提交;切换 MQ 成本太大
- **B. Seata SAGA** — 适合长事务编排;对纯消息投递过重
- **C. 本地消息表(outbox pattern)+ 定时扫描器 + publisher-confirm** — 业内成熟;DB 事务保证 outbox 记录与业务同生死,扫描器异步投 MQ

## 决策

选 **C**。

- **表结构**:`t_local_message(id, biz_key, exchange, routing_key, payload, status, retry_count, next_retry_at, created_at)`
  - `status`:`0=PENDING / 1=SENT / 2=CONFIRMED / 3=FAILED`
  - 索引:`idx_status_next(status, next_retry_at)`(扫描器优化)
- **业务写入**:`OrderService.create()` 在 `@Transactional` 内同时 INSERT t_order + t_order_item + t_local_message(3 行) — DB 事务保证全部成功或全部回滚
- **扫描器**:`LocalMessagePublisher.scan()` @Scheduled(`order.outbox.scan-interval-ms` 默认 5000 ms)
  - 取 PENDING + `next_retry_at <= now()` → 限流并发投 MQ
  - publisher-confirm 回调:成功 → `status=2`;失败 → `retry_count++, next_retry_at` 退避(5s/30s/5min/30min)
- **重试上限**:`retry_count > 5` → `status=3 FAILED` + 告警(留待人工 / 死信)

## 后果

**正面**
- 业务 commit 即视为消息"已发出"(即使 MQ 暂时不通,扫描器会补发)
- at-least-once 投递,消费侧需自行幂等(订单 ID / X-Request-Id)
- 单 DB 事务 = 强一致;复杂度集中在 publisher,不污染业务代码

**负面 / 代价**
- 业务 DB 多 1 张表 + 写放大(1 业务事务 → 1 outbox 行 / 消息)
- 5 s 默认扫描间隔 = 平均 2.5 s 投递延迟(已通过 publisher-confirm 异步回调 + 减小间隔可调优)
- 表大小需定期归档(`t_local_message` 已发成功的 7 天前数据可清理 — 留运维任务)

**后续动作**
- 任何新增 MQ 消息投递场景,**必须**走本地消息表,**禁止**直接 `rabbitTemplate.convertAndSend()`(轻量 M4 行为埋点例外,见 [ADR-008](ADR-008-mq-reliability-tiers.md))
- 消费侧幂等:用 `biz_key` 或消息体内的业务 ID 在消费方 Redis/DB 去重
