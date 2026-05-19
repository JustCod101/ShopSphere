检查涉及分布式事务的代码（对齐 docs/api-contracts.md §4.3 拍板：库存分支用 Seata **TCC**，非 AT）。

## 一、区分两类分支

下单链路 = Order 发起 `@GlobalTransactional`，下含两类分支，**不可混用范式**：

### A. 库存分支（Product）—— Seata TCC（S1/S2/S3 拍板）
1. 必须实现 Try / Confirm / Cancel 三段（接口 `/internal/product/stock/{try,confirm,cancel}`）
2. **幂等键 = `(orderId, productId)`**；`xid` 仅作事务关联日志，禁止用作幂等键
3. 幂等表 `t_stock_tcc_log(order_id, product_id, phase, state)` 必须存在；校验：
   - 幂等：Try/Confirm/Cancel 各自重复调用只生效一次
   - 空回滚：Cancel 先于 Try 到达 → 记标记、直接成功、**不回补库存**
   - 防悬挂：Try 检测到已有 Cancel 记录 → 拒绝执行（返回 `3003`）
4. Cancel 必须**显式回补 Redis** `stock:product:{id} += q`（AT 回滚不了 Redis，这是改 TCC 的根因）
5. **禁止**库存库建 `undo_log`、禁止 AT 风格「扣减/回滚」单段接口

### B. Order 本地分支 —— 本地事务（可 AT）
1. 建单 + `t_local_message`（本地消息表）写入 **同一本地事务**（C3）
2. 该分支若用 Seata AT，则仅 **Order 库** 需 `undo_log`（库存库不需要）
3. 下单幂等：`(userId, X-Request-Id)` 去重（S5），`t_order_request`

## 二、通用校验
1. 主事务方法 `@GlobalTransactional`，分支按上面 A/B 区分范式
2. Feign 调用 TX_XID 透传（Seata 拦截器自动处理，需验证生效）
3. 服务间 Feign 走 Nacos 直连、不经 Gateway（C2）
4. 未支付超时：CREATED 投 RabbitMQ 延迟消息 `order.payment.timeout`（30min），到期 → Cancel 释放（S4）
5. 编写失败演练用例：主动抛异常验证 Cancel 回补 Redis、空回滚、防悬挂
