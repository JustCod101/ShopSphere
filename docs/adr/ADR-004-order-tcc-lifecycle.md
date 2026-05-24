# ADR-004: 下单生命周期 Try/Pay-Confirm/Timeout-Cancel

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-22(commit `beaf0ad` Phase 3)
- 关联: [api-contracts §6.3 S4](../api-contracts.md) / [mq-topology.md](../mq-topology.md) / [ADR-003](ADR-003-stock-seata-tcc.md)

## 背景

ADR-003 定下库存三段语义,但订单维度的"哪个动作触发 Try / Confirm / Cancel"是独立设计。常见三种映射:

- 下单 = Try(预占),付款 = Confirm(真扣)— 用户体验强,库存即时占用
- 下单 = 仅写订单,付款 = Try+Confirm — 简化;但下单时库存不锁定,易超卖
- 下单 = Try+Confirm(直接扣)— 取消必须二次补偿,体验差(取消≠回滚)

需要一个**未付订单超时自动释放**的机制(避免恶意下单锁库存)。

## 候选方案

- **A. 下单即扣(Confirm),取消走补偿** — 简单;但"取消 ≠ TCC.Cancel",语义割裂
- **B. 下单 Try / 付款 Confirm / 超时 Cancel,超时用扫表轮询** — 语义干净;扫表开销大、间隔粗
- **C. 下单 Try / 付款 Confirm / 超时 Cancel,超时用 RabbitMQ 延迟消息** — 语义干净;依赖延迟插件,但精度高、零轮询

## 决策

选 **C**。

- **`POST /api/order/create`**(`OrderController.java:42`):
  - 写订单 + Try 库存(Feign `product/stock/try`)+ 投本地消息表(包含 30 min 延迟 cancel 消息)
  - 全部在 `@GlobalTransactional`(Seata XID 串起 order 库 + product 库)
  - 返回 `Result<OrderCreateVO>{orderId, status=CREATED}`
- **`POST /api/order/{id}/pay`**(`OrderController.java:61`):
  - 状态机:`CREATED → PAID`
  - Feign `product/stock/confirm`(库存真出库)
  - 投积分 / 通知 MQ 事件(M1 强可靠)
- **`POST /api/order/{id}/cancel`**(`OrderController.java:93`):
  - 用户主动取消(`CREATED → CANCELLED`)或延迟消息触发(30 min 未付)
  - Feign `product/stock/cancel`(库存回滚 + Redis 回补)
- **超时机制**:
  - exchange:`order.delay.exchange`(x-delayed-message)
  - 延迟 TTL:`order.payment.timeout-ms`(默认 30 min,可配)
  - 消费方:order 服务自己消费 `order.payment.timeout` → 调用 cancel 逻辑(状态机检查,已付/已取消则空操作)

## 后果

**正面**
- 用户下单即锁库存,购物车不会"我看着有货,付钱时没了"
- 取消语义统一为 TCC.Cancel,源码无补偿分支
- 超时零轮询:RabbitMQ 延迟队列由 broker 维护
- 状态机收敛:`CREATED → PAID/CANCELLED`,无中间态(`PAYING` 等不存在)

**负面 / 代价**
- 依赖 `rabbitmq-delayed-message-exchange` 插件(已预装在 `rabbitmq/Dockerfile`)
- 30 min 内积压未付订单 → 库存占用;高并发场景下相当于"准库存"被消耗
- 状态机变更必须经 `OrderStatusMachine`(若新增状态需更新本 ADR)

**后续动作**
- 业务方提"先到先得抢购"/"待审核订单" → 需新增状态;先评估是否破坏 TCC 三段映射
