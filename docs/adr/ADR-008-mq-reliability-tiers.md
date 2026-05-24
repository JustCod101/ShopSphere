# ADR-008: MQ 可靠性分级

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-22(commit `beaf0ad` T3.4 / 行为埋点 `06430a1` T1.4)
- 关联: [mq-topology.md](../mq-topology.md) / [api-contracts §M1-M5](../api-contracts.md) / [ADR-007](ADR-007-local-message-table.md)

## 背景

所有消息都走"本地消息表 + publisher-confirm + 持久化 + 死信"会带来:
- 业务表写放大(每条消息一行 outbox)
- 扫描器开销
- 高频低价值消息(如行为埋点)同等代价不合理

**不是所有消息丢一两条都会引起业务问题**。需按消息**业务后果**分级。

## 候选方案

- **A. 全部走强可靠**(本地消息表 + confirm + persistent + DLX) — 简单一致;低价值消息开销过大
- **B. 全部走轻量**(直发,不 confirm,不持久化) — 性能最高;丢消息可能影响订单/积分/库存
- **C. 分级分通道** — 强一致路径用 A,可丢路径用 B,文档明确分级边界

## 决策

选 **C**。两档分级(api-contracts § M1-M5):

| 编号 | 通道 | 等级 | 实现 | 用例 |
|---|---|---|---|---|
| **M1** | `order.exchange` | 强可靠 | 本地消息表 + publisher-confirm + persistent + DLX | 订单创建/支付/取消事件;积分扣减;通知 |
| **M2** | (透传) | 契约级 | 网关注入 `X-User-Id`,业务读 header | 鉴权链 |
| **M3** | (路由) | 契约级 | 行为埋点不经 Gateway,服务直发 MQ | 减少网关压力 |
| **M4** | `behavior.exchange` | 轻量 | `rabbitTemplate.convertAndSend`(无 outbox)+ persistent=false | 用户行为埋点(浏览/点击);丢失影响推荐召回精度,不影响业务正确性 |
| **M5** | (路由不存在) | 错误码 | Gateway 1004 ROUTE_NOT_FOUND | 路由治理 |

**强可靠路径**(M1)细则:
- exchange:`x-delayed-message` 或 `topic`,durable=true
- queue:durable=true,绑定死信交换机(消费失败 3 次进 DLX)
- publisher-confirm:开启,回调更新 `t_local_message.status`
- 消费侧:**必须幂等**(订单 ID / X-Request-Id 去重)

**轻量路径**(M4)细则:
- `User.behavior` 接口收到行为 → `rabbitTemplate.convertAndSend("behavior.exchange", "behavior.viewed", payload)`
- 不进 outbox,不 confirm,异步 fire-and-forget
- 消费侧(推荐)失败重试 3 次进死信,不阻塞投递

## 后果

**正面**
- 关键路径不丢消息(M1 加多重保险)
- 高频路径不写放大(M4 直发)
- 决策边界清楚:**消息丢一两条是否会引发资金/库存/订单错误?是 → M1;否 → M4**

**负面 / 代价**
- 两套投递机制 → 代码 + 配置两套
- 推荐侧训练数据有"少量缺失"的预期(M4 丢损 < 0.1% 不影响 ItemCF 召回)
- 文档/审查必须明确每条新增消息的等级(否则默认走 M1 以确保安全)

**后续动作**
- 新增 MQ 消息时,审查者**必须**在 PR 描述里标 M1 还是 M4,并解释理由(放进 PR 模板)
- M1 与 M4 之间的判断有疑义 → 默认 M1(保守);后续如发现 M4 丢消息引起业务问题 → 升级到 M1 并补一个新 ADR
