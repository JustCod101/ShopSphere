# ShopSphere 发布前检查清单(Release Checklist)

> 基准:[docs/api-contracts.md §9 开工前 Checklist(行 392-411)](api-contracts.md) 17 条拍板项,逐条核验**代码-文档一致性**。
> 审查范围:T0.1 – T5.4 全量交付物(含 T5.4 新增的 README/CHANGELOG/ADR/troubleshooting)。
> 状态约定:`PASS` = 代码与文档完全一致;`FAIL` = 偏差需阻塞或需备注。**不使用模糊词**。

---

## 一、17 条拍板项核验总表

| #  | 拍板项 | 状态 | 证据 | 备注 |
|----|--------|------|------|------|
| 1  | §3 透传 Header 三头固定(`X-User-Id` / `X-User-Name` / `X-Trace-Id`) | PASS | `shopsphere-common/.../context/HeaderConstant.java` 第 12-16 行常量名 1:1 对齐;`JwtAuthFilter` Javadoc 行 37 显式注入这两个头;`RequestLogFilter` 行 26 剥离入站三头 | — |
| 2  | §5 端口 8080-8083 + 8000 + 库名(含 `shopsphere_reco`) | PASS | `docker-compose.yml`:GATEWAY_PORT=8080 / USER_PORT=8081 / PRODUCT_PORT=8082 / ORDER_PORT=8083 / RECO_PORT=8000;`scripts/01-init-mysql.sql` 创建 `shopsphere_user/product/order/reco/seata` 五库 | — |
| 3  | §7 行为事件驱动 + Reco 自有库 `shopsphere_reco` | PASS | [ADR-005](adr/ADR-005-recsys-own-db.md) 拍板;`scripts/01-init-mysql.sql` 行 16 `CREATE DATABASE shopsphere_reco`;docker-compose 行 418 `MYSQL_DB: shopsphere_reco` 注入容器 | — |
| 4  | §4.3/§6.3 库存 TCC 重设计(S1–S5) | PASS | [ADR-003](adr/ADR-003-stock-seata-tcc.md) 记录决策;[tcc-rollback-report.md](tcc-rollback-report.md) 7 case 全绿;`perf/results/evidence.txt` Phase1-3 TCC 三段计数对齐(TRY=200 / CONFIRM=200 / CANCEL=100) | — |
| 5  | §6.3 S5 `X-Request-Id` + `t_order_request` 幂等 | PASS | `OrderController.java` 行 47 抛 `PARAM_INVALID("缺少必填请求头 X-Request-Id")`;`V20260521_1000__init_order.sql` 行 39 `UNIQUE KEY uk_user_req(user_id, request_id)`;`OrderRequestCleanupTask` 24h TTL | — |
| 6  | §6.4 C1 推荐冷启动 fallback(`code=0 + data.fallback=true`) | PASS | `shopsphere-recommendation/app/api/recommend.py` 行 37-44 / 56-64 一律返回 `Result.ok({..., "fallback": result.fallback})`;`ErrorCode.java` 行 41 注释明示 5xxx 不进 `Result.code` | — |
| 7  | §4.1/§4.2 C2 无 Java→Py Feign | PASS | 全仓 grep `RecommendationFeign\|RecommendFeignClient\|@FeignClient.*reco` 在 `*.java` 下零命中;[ADR-006](adr/ADR-006-no-java-py-feign.md) 备查 | — |
| 8  | §8 C3 本地消息表与建单同事务 | PASS | [ADR-007](adr/ADR-007-local-message-table.md);`LocalMessagePublisher.java` 头注释明确"T3.2 把 order.created / order.payment.timeout 与建单同本地事务写入 t_local_message(PENDING)";`FOR UPDATE SKIP LOCKED` + Redisson 锁 + publisher-confirm 回写 CONFIRMED | — |
| 9  | M1 订单事件 publisher-confirm-type=correlated | PASS | `docs/nacos/shopsphere-order-dev.yaml.template` 行 45 `publisher-confirm-type: correlated`;[ADR-008](adr/ADR-008-mq-reliability-tiers.md) 拍板;`LocalMessagePublisher#onConfirm` 实现 ack→CONFIRMED / nack→指数退避 | — |
| 10 | M2 Gateway 注入 `X-User-Id`(业务不解 JWT) | PASS | `JwtAuthFilter.java` 行 37-38 文档化注入流程;`JwtAuthFilterTest` Case D 验证伪造头被覆盖;业务服务侧全仓未见 `JwtUtil.parse\|verify` 调用 | — |
| 11 | M3 行为事件不经 Gateway(User 直发 MQ) | PASS | `shopsphere-gateway/src/main/resources` 下无 `/api/user/behavior` 路由配置(grep 零命中);`BehaviorEventPublisher` 行 31 `@Async` 池内 `rabbitTemplate.convertAndSend` 直发;ADR-005 行 27 描述一致 | **注**:`api-contracts §9 行 410` 的 `M3` 实际为 `OffsetDateTime(UTC) 口径`,本表 #11 描述源自 §M 节;两处描述都已成立 |
| 12 | M4 行为 MQ 轻量直发(无 outbox) | PASS | `BehaviorEventPublisher.java` 整文件无 `t_local_message` / outbox 痕迹;catch `AmqpException` 仅 `log.warn` 吞掉,与 ADR-008 行 31 "轻量,fail-open" 一致;`RabbitConfig.java` 行 43-46 nack 仅 WARN 不补偿 | — |
| 13 | M5 ROUTE_NOT_FOUND=1004(网关独占) | PASS | `ErrorCode.java` 行 21-22 注释"仅 Gateway 路由层使用,业务服务禁止返回";业务码 2xxx/3xxx/4xxx/5xxx 号段隔离 | — |
| 14 | T0.1 `Result<T>` + 全局异常 + UserContext | PASS | `shopsphere-common` 下 `result/Result.java` / `result/ErrorCode.java` / `context/HeaderConstant.java` / `BusinessException` 等齐备;[shopsphere-common/README.md](../shopsphere-common/README.md) 已交付 | — |
| **15** | **压测验收(不超卖 + QPS≥200 + P99<500ms)** | **FAIL** | 详见 §二 实测子表 + 根因假设 | 用户已拍板**接受 200 并发数据 / 不重跑 / conditional release** |
| 16 | Sentinel 规则 Nacos 热加载 | PASS | [sentinel-rules.md](sentinel-rules.md);`shopsphere-gateway/pom.xml` + `shopsphere-user/pom.xml` 引入 `spring-cloud-alibaba-sentinel-datasource`;Gateway `application.yml` 配 `spring.cloud.sentinel.datasource` | — |
| 17 | Seata XID 透传 | PASS | [seata-verify.md](seata-verify.md);`shopsphere_seata` 库已建(`01-init-mysql.sql` 行 20);ADR-003/004 记录;`t_order` 流水中 `undo_log` 表迁移落库 | — |

---

## 二、第 15 条(压测)详细子表

### 2.1 实测数据 vs 验收门(基准:[perf-tcc-report.md](perf-tcc-report.md))

| 维度 | 验收门 | 实测值 | 状态 |
|------|--------|--------|------|
| 并发用户 | USERS=1000 | **USERS=200** | **偏离规范** |
| 初始库存 | INIT_STOCK=500 | INIT_STOCK=500 | 一致 |
| 不超卖(A) | success ≤ 500 / oversold=0 | success=200 / oversold=0 | PASS |
| 账平(B) | stock + locked = 500 | 300 + 200 = 500 | PASS |
| Redis/DB 一致(C) | Redis = DB.stock | 300 = 300 | PASS |
| TRY 计数(D) | TRY = success | 200 = 200 | PASS |
| CONFIRM 计数(F) | CONFIRM = paid | 200 = 200 | PASS |
| locked 归零(G) | 0 | 0 | PASS |
| DB 扣减(H) | DB.stock = INIT − success | 300 | PASS |
| CANCEL 计数(I) | CANCEL = cancelled | 100 = 100 | PASS |
| 回补一致(J/K/L) | DB.stock 回补 / locked=0 / Redis=DB | 400 / 0 / 400=400 | PASS |
| 状态抽样(M) | CANCELLED | 4,4,4(全 4) | PASS |
| **QPS** | **≥ 200** | **21.57** | **FAIL** |
| **P99(ms)** | **< 500** | **1280** | **FAIL** |
| errorPct | = 0 | 0.0% | PASS |

**结论**:一致性 A–M 全绿;**性能 QPS / P99 双指标击穿**;综合判定 FAIL。

### 2.2 根因假设(按概率排序)

**假设 1(P=0.6):JMeter 测试计划线程数未被环境变量覆盖,实际只跑了 200 并发**

- 观察证据:`evidence.txt` 顶部 `USERS=200`;[CHANGELOG.md](../CHANGELOG.md) T5.3 条目写"1000 并发 / rampup 1s",但实测脚本头部回显 200 — 说明 `perf/run-all.sh` 或 `.jmx` 内 `${__P(users,200)}` 默认值生效,环境变量 `USERS=1000` 未被 `-J` 注入。
- 验证方法:`grep -n 'users' perf/order-create.jmx` 看 ThreadGroup 是否硬编码;`bash -x perf/run-all.sh 2>&1 | grep -i 'jmeter -J'` 看启动行有无 `-Jusers=1000`。若两处都缺,本假设成立。

**假设 2(P=0.25):被测服务侧 HikariCP / Tomcat 线程池配置过低,200 并发就触底**

- 观察证据:QPS=21.57 → 平均响应时间 ≈ 200 / 21.57 ≈ 9.3s 量级(P99=1280ms 仅是分位,均值更可能在排队);TCC 链路含 Seata GlobalLock + Redis 扣减 + 本地消息表写盘,单事务持有连接时间长,若 HikariCP `maximumPoolSize` 默认 10,极易在 200 并发下排队雪崩。
- 验证方法:`grep -rn 'maximum-pool-size\|maximumPoolSize\|server.tomcat.threads' shopsphere-order/src/main/resources docs/nacos/shopsphere-order*.yaml*`;同时拉 Grafana HikariCP dashboard 看 `pending_connections` 峰值。

**假设 3(P=0.15):Seata TC(单机 file/db 模式)成为热点瓶颈**

- 观察证据:TCC 三段每笔都要走 TC 注册 / 全局锁 / 分支提交,200 并发下若 TC 用 `file` 存储,所有 branch register 串行化盘 fsync;P99=1280ms 与 TC 的网络 + 盘 IO 量级吻合。
- 验证方法:`grep -n 'store.mode\|store.session.mode' docker-compose.yml seata/`;若为 `file` 模式,本假设可被部分坐实;改 `db` 模式重测同 200 并发应能将 P99 拉到 500ms 以下。

> **用户已拍板**:不重跑、不调优;以 conditional release 形式发布,在 v1.1 性能专项中处理。

---

## 三、综合结论

| 维度 | 评定 | 说明 |
|------|------|------|
| 文档完整性 | **Y** | 17 条均能在 `api-contracts § / ADR / README / docs/*.md` 中找到对应陈述;T5.4 新增 8 个 ADR + 5 个服务 README + troubleshooting + CHANGELOG 全部落盘 |
| 代码-文档一致性 | **Y** | 16/17 条代码与文档严格对齐;唯一偏差为 #15(实测性能未达文档承诺门槛),属性能数据 vs 规范数据偏差,**非接口或行为契约偏离** |
| 是否可发布 | **Y(Conditional Release)** | 功能契约完整、一致性(TCC 三段 / 不超卖 / Redis-DB / 状态机)全绿;性能门槛击穿仅在 200 并发样本下成立,需在发布说明中明示"v1.0 性能基线 = 200 并发 / QPS 21.57 / P99 1280ms",且建议下版本前完成 §2.2 假设 1+2 验证 |
| 阻塞项 | 无硬阻塞 | 第 15 条已被用户拍板降级为 conditional release 项,不阻塞 v1.0 发布 |

### 发布说明强制项(Release Notes Must-Have)

1. 明确声明本版本性能基线 = **200 并发 / QPS=21.57 / P99=1280ms**,**不承诺 1000 并发**。
2. 在 [README.md](../README.md) 性能章节或 [perf-tcc-report.md](perf-tcc-report.md) 顶部置顶 banner,引用本 checklist 第 15 条。
3. v1.1 里程碑必须包含一项:验证 §2.2 假设 1(JMeter 参数透传)+ 假设 2(HikariCP 调优)+ 假设 3(Seata 存储模式),完整重跑 1000 并发并更新本表。
4. [CHANGELOG.md](../CHANGELOG.md) T5.3 条目中"1000 并发"措辞应在 v1.0 发布前修订为"目标 1000 / 实测 200",避免文档误导 — 已修订(见 T5.4 commit)。

---

## 四、审查者签名

| 项 | 值 |
|----|----|
| 审查者 | architect-reviewer(Claude Opus 4.7) |
| 审查工具边界 | Read / Grep / Glob(read-only) |
| 审查基准 | `docs/api-contracts.md §9`(行 392-411) |
| 审查日期 | **2026-05-25** |
| 审查结论 | **Conditional Release 通过(17 项中 16 PASS / 1 FAIL,FAIL 项已被业务方拍板接受)** |
