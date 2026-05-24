# 架构决策记录(ADR)

> 决策实际形成于 T0~T5 各阶段;本目录于 2026-05-24(T5.4)统一补录,日期按 git log 各 Phase 实际 commit 推断。
> 格式:Michael Nygard 经典 ADR(背景 / 候选 / 决策 / 后果)。

| # | 标题 | Phase | 状态 | 一句话 |
|---|---|---|---|---|
| [ADR-001](ADR-001-service-boundary.md) | 服务拆分边界 | T0 | Accepted | 4 Java(Gateway/User/Product/Order)+ 1 Py(Reco),按 DDD 子域;Reco 自有库不跨服务读 |
| [ADR-002](ADR-002-jwt-at-gateway.md) | 网关统一 JWT(RS256) | T1.2 | Accepted | 网关验签 + 公钥下发;业务服务零鉴权代码,只读 `X-User-Id` 等头 |
| [ADR-003](ADR-003-stock-seata-tcc.md) | 库存改 Seata TCC(替代 AT) | T2.4/T3.1 | Accepted | AT 在热点库存上 undo_log 不可控;改为显式三段 + `t_stock_tcc_log` 幂等表 + Redis 镜像 |
| [ADR-004](ADR-004-order-tcc-lifecycle.md) | 下单语义 Try/Pay-Confirm/Timeout-Cancel | T3 | Accepted | create=Try(已扣);pay → Confirm;30 min MQ 延迟未付 → Cancel |
| [ADR-005](ADR-005-recsys-own-db.md) | 推荐自有库 + 事件驱动 | T4 | Accepted | Py 不跨库读;MQ 投递行为/订单事件;离线训练 + 在线召回 |
| [ADR-006](ADR-006-no-java-py-feign.md) | 取消 Java→Py Feign | T4 | Accepted | Java 不依赖 Py 接口;前端走 Gateway 路由 `/api/recommend/**` 直达 |
| [ADR-007](ADR-007-local-message-table.md) | 本地消息表 + 同事务 | T3 | Accepted | publisher-confirm 不足以保业务事务一致;outbox + 扫描器达到 at-least-once |
| [ADR-008](ADR-008-mq-reliability-tiers.md) | MQ 可靠性分级 | T3.4 | Accepted | M1/M2 强可靠(confirm+持久化+死信);M4 轻量(行为埋点)直发可丢 |

## 编写新 ADR

新决策应在拍板的同一 commit 创建 ADR(文件名 `ADR-NNN-kebab-title.md`);超期补录请在状态栏注明 `Accepted(补录)`。
