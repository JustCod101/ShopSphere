# ADR-003: 库存改 Seata TCC(替代 AT)

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-21(commit `0be99bb` T2.4 拍板 / `beaf0ad` T3 落地)
- 关联: [api-contracts §4.3 / §6.3 S1-S5](../api-contracts.md) / [seata-verify.md](../seata-verify.md) / [tcc-rollback-report.md](../tcc-rollback-report.md) / [stock-redis.md](../stock-redis.md)

## 背景

T2 阶段初版用 Seata **AT** 模式做库存扣减:
- 行级 undo_log 自动管理,业务无侵入
- 但库存是热点行,1000 并发同一商品 → undo 链长、回滚代价大
- Redis 镜像层与 DB 通过 AT 不可直接联动,需额外组件
- AT 不支持业务可见的"占用态"(只有最终态)

S1~S5 验收要求(api-contracts §6.3):防超卖、显式幂等、防空回滚、防悬挂、X-Request-Id 幂等。AT 无法干净覆盖 S3/S4。

## 候选方案

- **A. AT 模式 + 业务补偿表** — 入侵小;复杂度向应用层泄漏,undo + 自定义补偿两套机制冗余
- **B. SAGA** — 适合长事务;但库存是高频短事务,SAGA 编排过重
- **C. Seata TCC(显式 Try/Confirm/Cancel)+ `t_stock_tcc_log` 幂等表 + Redis 镜像同步** — 业务可见三段;天然支持占用态、显式幂等、空回滚识别

## 决策

选 **C**。

- 库存表:`t_product_stock(stock, locked_stock, version)`
  - Try:`UPDATE ... SET stock-=q, locked_stock+=q WHERE product_id=? AND stock>=q`(条件更新,影响 0 行 → 抛 `STOCK_NOT_ENOUGH=3002`)
  - Confirm:`UPDATE ... SET locked_stock-=q`(真出库)
  - Cancel:`UPDATE ... SET stock+=q, locked_stock-=q`(回滚占用)
- 幂等表:`t_stock_tcc_log(order_id, product_id, phase, state, quantity)`,UK `(order_id, product_id, phase)`
  - Try 时 INSERT 一条 `phase=TRY, state=1`
  - **防悬挂**:Cancel 比 Try 先到时,INSERT 一条 `phase=CANCEL, state=1` 占位;后续 Try 检测占位 → 拒绝
  - **空回滚识别**:Cancel 时若无 Try 记录 → 仅 INSERT CANCEL 占位,**不执行库存回滚**
- Redis 镜像:`stock:product:{id}` = `DB.stock - DB.locked_stock`(可售量),Try/Cancel 时同步刷新

## 后果

**正面**
- S1 防超卖:Try 的条件更新行级原子保证(SQL 层)
- S2 显式幂等:UK 冲突 = 重复请求
- S3 空回滚 / S4 防悬挂:`t_stock_tcc_log` 三段插入语义覆盖
- 可观测:`SELECT phase, state, COUNT(*) FROM t_stock_tcc_log GROUP BY phase, state` 直观看三段计数
- Redis Lua 子动作可独立加速(详见 [stock-redis.md](../stock-redis.md))

**负面 / 代价**
- 业务侵入大:product 服务必须实现 3 个内部接口(`/internal/product/stock/{try,confirm,cancel}`)
- 单库存事务多 1 张表写(`t_stock_tcc_log`)+ Redis 写 1 次,RT 增长
- 不能用 `@TwoPhaseBusinessAction`(Seata 注解只代理无状态调用,本项目用业务 TCC 自行管理三段)

**后续动作**
- 任何新增需要库存的业务(预售/秒杀)必须复用 TCC 接口,**禁止直接 UPDATE 库存表**
- TCC 失败回归见 [tcc-rollback-report.md](../tcc-rollback-report.md)(7 个 case 全绿)
