# ADR-001: 服务拆分边界

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-19(commit `dfaf008` Phase 0)
- 关联: [architecture.md §1.2 / §2](../architecture.md)

## 背景

ShopSphere 作为练手电商,在拆分微服务时面临两类边界选择:
- **按技术职责拆**(API/逻辑/数据)— 简单但与 DDD 子域错位,跨服务事务多
- **按业务子域拆**(User/Product/Order/Recommendation)— 服务数控,贴近康威定律,但需谨慎处理跨域调用与异构语言栈

约束:个人项目,服务数应 ≤ 5;推荐模块希望用 Python(机器学习生态)。

## 候选方案

- **A. 单体 + 模块化** — 启动快、事务简单;无法验收"微服务"主题
- **B. 极简两服务**(用户中心 + 业务大杂烩) — 服务数少;Order/Product/Reco 强耦,无法演练分布式事务/异构集成
- **C. 按子域拆 5 个服务**(User、Product、Order、Reco、Gateway) — 贴 DDD,可演练 Seata/MQ/异构;运维稍重

## 决策

选 **C**。

- 4 Java 服务:`Gateway`(入口)、`User`(账号)、`Product`(商品+库存)、`Order`(交易+TCC 发起)
- 1 Python 服务:`Recommendation`(FastAPI),拥有独立数据库 `shopsphere_reco`,与 Java 侧零库共享
- 推荐侧通过 RabbitMQ 消费行为/订单事件(详见 [ADR-005](ADR-005-recsys-own-db.md))

## 后果

**正面**
- 子域内聚,演练空间足:网关鉴权(ADR-002)、跨服务事务(ADR-003)、异构集成(ADR-005)、消息可靠性(ADR-008)
- Python 选型不污染 Java 栈;推荐迭代独立

**负面 / 代价**
- 运维复杂度上升:5 服务 + Nacos/Seata/Sentinel/MQ/Redis/MySQL → docker-compose 必备
- 跨域调用需 Feign + Fallback(详见 [ADR-006](ADR-006-no-java-py-feign.md))
- 数据库 4 套(user/product/order/reco)+ Nacos 自带 1 套,需在 `01-init-mysql.sql` 统一建库

**后续动作**
- 任何"业务跨 2 个以上服务"的新功能,先确认是否需要新增子域(回看本 ADR);避免随手在 Order 里塞用户/推荐逻辑
