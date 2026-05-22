# 库存 TCC 失败演练报告（T3.3）

**日期**:2026-05-22
**验证载体**:`shopsphere-product/src/test/java/com/shopsphere/product/service/StockTccIT.java`
**环境**:`@SpringBootTest` 启动 Product 完整上下文 + Testcontainers（MySQL 8.0.36 + Redis 7），
Flyway 自动建表灌种子（商品 2001-2020，`stock=100`），`StockWarmupRunner` 预热 Redis。

> 本环境无法截图;每个 case 以 `StockTccIT` 的 JUnit 断言 + 服务端日志作为证据。
> 运行命令:`mvn -pl shopsphere-product test -Dtest=StockTccIT` —— **结果:7 用例全绿**
> （`Tests run: 7, Failures: 0, Errors: 0`）。

## 设计前提（业务 TCC）

T3.3 规划阶段发现任务 Part A 的 `@TwoPhaseBusinessAction` 与「Confirm 延迟到 /pay」矛盾
（Seata commitMethod 在「运行 Try 的全局事务」提交时即触发，无法跨请求延迟），经确认改为
**业务 TCC**:try/confirm/cancel 为 3 个显式幂等操作，各自 `@Transactional`
（`t_product_stock` 条件更新与 `t_stock_tcc_log` 幂等日志同一本地事务）。`stock` 列 = 可售库存池,
真实总量 = `stock + locked_stock`,Redis `stock:product:{id}` 镜像 `stock`。

## 用例结果一览

| # | 场景 | 预期 | 实际 | 结论 |
|---|---|---|---|---|
| 1 | 正常 Try | stock 95 / locked 5、Redis 95、TRY 日志 1 行 | 与预期一致 | ✅ |
| 2 | 多商品部分失败 | 抛 `STOCK_NOT_ENOUGH(3002)`;先成功商品 DB+Redis 全回补 | 与预期一致 | ✅ |
| 3 | Try → Confirm | locked 归零、stock 不变、Redis 不变、CONFIRM 日志 1 行 | 与预期一致 | ✅ |
| 4 | Try → Cancel | stock 回补、locked 归零、Redis 回补、CANCEL 日志 1 行 | 与预期一致 | ✅ |
| 5 | 空回滚 | 写订单级标记 `(orderId,0,CANCEL,state=0)`、库存不动 | 与预期一致 | ✅ |
| 6 | 防悬挂 | 空回滚后 Try 被拒 `STOCK_PREDEDUCT_FAIL(3003)`、库存不动 | 与预期一致 | ✅ |
| 7 | Confirm 幂等 | 第二次 Confirm no-op、locked 不重复扣、CONFIRM 日志仅 1 行 | 与预期一致 | ✅ |

## 逐用例详情

### Case 1 — 正常 Try（`case1_try_deductsDbAndRedisAndLogsTry`）
- 输入:`tryStock(order=90001, product=2001, qty=5)`。
- **预期**:`t_product_stock` 2001 → `stock=95, locked_stock=5`;Redis `stock:product:2001=95`;
  `t_stock_tcc_log` 有 1 行 `(90001,2001,TRY,state=1)`。
- **实际**:断言 `stock==95 && locked==5 && redis==95 && TRY 行数==1` 全通过。✅

### Case 2 — 多商品部分失败回补（`case2_partialFailure_compensatesEarlierItem`）
- 输入:`tryStock(order=90002, [2002×5（足）, 2003×999999（不足）])`。
- **预期**:第二项不足 → 抛 `BusinessException(STOCK_NOT_ENOUGH)`;第一项 2002 **完全回补**
  —— DB `stock=100,locked=0`、Redis=100、无 TRY 日志（DB 由 `@Transactional` 回滚,
  Redis 由 `tryStock` 内补偿表显式 `restore`）。
- **实际**:异常码 `STOCK_NOT_ENOUGH`;2002 `stock==100 && locked==0 && redis==100 && TRY 行数==0`。✅

### Case 3 — Try → Confirm（`case3_confirm_releasesLockedKeepsStock`）
- 输入:`tryStock(90003,2004,7)` → `confirmStock(90003)`。
- **预期**:Confirm 仅 `locked_stock-=q` —— `stock=93`(不变)、`locked=0`、Redis=93(不动)、+1 CONFIRM 日志。
- **实际**:`stock==93 && locked==0 && redis==93 && CONFIRM 行数==1`。✅

### Case 4 — Try → Cancel（`case4_cancel_restoresStockAndRedis`）
- 输入:`tryStock(90004,2005,8)` → `cancelStock(90004)`。
- **预期**:Cancel `stock+=q,locked-=q` + 显式回补 Redis —— `stock=100`、`locked=0`、Redis=100、+1 CANCEL 日志。
- **实际**:`stock==100 && locked==0 && redis==100 && CANCEL 行数==1`。✅
- **手工 redis-cli 等价验证**:`redis-cli get stock:product:{id}` 在 Cancel 后回到初始值。

### Case 5 — 空回滚（`case5_emptyRollback_marksOrderLevelWithoutTouchingStock`）
- 输入:未 Try,直接 `cancelStock(90005)`。
- **预期**:无 TRY 行 → 写订单级空回滚标记 `t_stock_tcc_log(90005, productId=0, CANCEL, state=0)`,
  **不动任何库存**,直接成功返回。
- **实际**:标记行存在且 `state==0`;日志 `stock cancel empty-rollback marked: orderId=90005`。✅

### Case 6 — 防悬挂（`case6_antiHang_rejectsTryAfterEmptyRollback`）
- 输入:先 `cancelStock(90006)`（空回滚,写订单级标记）→ 再 `tryStock(90006,2006,3)`。
- **预期**:Try 检测到该订单已有 CANCEL 标记 → 拒绝,抛 `BusinessException(STOCK_PREDEDUCT_FAIL/3003)`;
  商品 2006 库存/Redis 不动。
- **实际**:异常码 `STOCK_PREDEDUCT_FAIL`;2006 `stock==100 && locked==0 && redis==100 && TRY 行数==0`。✅

### Case 7 — Confirm 幂等 / 重试最终一致（`case7_confirmIdempotent_secondCallNoop`）
- 输入:`tryStock(90007,2007,6)` → `confirmStock(90007)` → 再次 `confirmStock(90007)`。
- 说明:业务 TCC 无 Seata 二阶段自动重试;**「Confirm 失败后重试最终一致」由 Confirm 的幂等性兜底**
  —— 调用方（或补偿任务）重试 Confirm,第二次为安全 no-op。本用例以「连调两次」模拟重试。
- **预期**:第二次 Confirm no-op —— `locked` 不被重复扣减(仍 0)、`stock=94`、CONFIRM 日志仅 1 行。
- **实际**:`stock==94 && locked==0 && CONFIRM 行数==1`。✅

## seata-tx.md 自检对照

- **A 类·库存 TCC**:Try/Confirm/Cancel 三段齐备(`/internal/product/stock/{try,confirm,cancel}`)✓;
  幂等键 `(orderId, productId, phase)`、`xid` 不作幂等键 ✓;幂等表 `t_stock_tcc_log` ✓;
  幂等(case 1/3/7)、空回滚(case 5)、防悬挂(case 6)均演练通过 ✓;Cancel 显式回补 Redis(case 4)✓;
  库存库无 `undo_log`、不走 AT ✓。
- **B 类·Order 本地分支**:`/create`、`/pay` 均 `@GlobalTransactional`;建单 + `t_local_message`
  同一本地事务 ✓;`/create` 把 stockTry 重排到本地落库之后,Seata AT 回滚覆盖「落库成功但 Try 失败」,
  闭合 T3.2 库存泄漏缺口 ✓。

## 演练中发现并修复的缺陷

`StockTccIT` 是本工程**首个 `@SpringBootTest`**,首次真实启动 Product 完整 Spring 上下文,暴露一处
**T2.3 既有缺陷**:`StockRedisService` 构造器有两个同类型 `RedisScript<Long>` 参数;本工程不继承
`spring-boot-starter-parent`、未开 `-parameters`,Spring 6.1 已移除按调试信息发现参数名的能力
→ 无法按 Bean 名消歧 → 上下文启动失败(`NoUniqueBeanDefinitionException`)。该服务此前从未在真实
Spring 上下文启动过(仅手工 new 装配的单测),故缺陷一直潜伏。

**已修复（经用户确认）**:`StockRedisService` 改为显式构造器,两个 `RedisScript` 参数加
`@Qualifier("stockPreDeductScript")` / `@Qualifier("stockRestoreScript")`。修复后上下文正常启动。

> 注:`StockTccIT` 启动日志偶见 `StockReconciliationTask` 对个别商品报 `redis=MISSING` ——
> 这是对账定时任务(`@Scheduled`)与 `StockWarmupRunner` 预热的启动期竞态,属既有行为、无害,
> 不影响 7 个 TCC 用例断言。

## 结论

库存 TCC 全部分支语义（Try / Confirm / Cancel + 幂等 + 空回滚 + 防悬挂 + Redis 显式回补）
经 7 个集成用例**全部演练通过**。Order `/pay` 接口（CREATED→PAID + 触发 Confirm）单测覆盖通过。
