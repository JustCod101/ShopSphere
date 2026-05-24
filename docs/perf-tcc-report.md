# 下单链路压测报告(T5.3)

> 2026-05-24 实测;**性能未达基线但一致性全绿,以 conditional release 收尾**。
> 完整发布核验见 [release-checklist.md](release-checklist.md) §15。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 主机 | macOS(Apple Silicon)+ Docker Desktop |
| Docker 服务版本 | git HEAD = `47e1f03`(T5.3 commit) |
| MySQL | 8.0.36(单实例) |
| Redis | 7.2-alpine(单实例) |
| RabbitMQ | 3.13 + delayed-message-exchange |
| Nacos / Seata / Sentinel | standalone |
| Java 服务 | 单实例,JVM 默认参数(无 `-Xmx` 显式) |
| JMeter | 5.6.3(docker `justb4/jmeter`,通过 `host.docker.internal:8080` 打 Gateway) |

## 2. 压测参数

- 用户:**实测 200**(目标 1000,JMeter 参数未透传 — 见 §6 假设 1)
- 库存:500(`INIT_STOCK`)
- 商品:2001(`PRODUCT_ID`)
- rampup:1s
- 每用户购买:1 件
- X-Request-Id:每请求新 UUID(JMeter `${__UUID()}`)
- Sentinel:`/api/order/create` 当前无 QPS 限流规则(未撞)
- Seata 模式:TCC(库存)+ 本地消息表(订单事件)

## 3. 三阶段执行结果

| 阶段 | 样本 | 成功 | QPS | Avg(ms) | P95(ms) | P99(ms) | Err% |
|---|---:|---:|---:|---:|---:|---:|---:|
| Create | 200 | 200 | **21.57** | 470 | 1061 | **1280** | 0.0 |
| Pay    | 200 | 200 | — | — | — | — | 0 |
| Cancel | 100 | 100 | — | — | — | — | 0 |

> Create 阶段数据来源:`perf/results/html-report/statistics.json`;Pay/Cancel 由 `pay-phase.sh` / `cancel-sample.sh` 完成(`xargs -P` 并发),无错误,未单独采集 QPS。

## 4. 一致性证据

```text
=== T5.3 Phase 1 verify (2026-05-24T15:09:13Z) ===
USERS=200 INIT_STOCK=500 PRODUCT_ID=2001
success=200 oversold=0 errors=0
DB.stock=300 DB.locked_stock=200
Redis.stock=300
TCC.TRY(state=1)=200
t_order rows=200

A 不超卖:OK (200 <= 500)
B 账平:OK (stock+locked=500 == 500)
C Redis/DB 一致:OK (redis=300 == DB.stock=300)
D TRY 计数:OK (200 == success 200)
E 失败合规:OK (errors=0; oversold=0 == USERS-success=0)

=== Phase 2 verify (2026-05-24T15:09:17Z) ===
paid=200 success=200
TCC.CONFIRM(state=1)=200
DB.stock=300 DB.locked_stock=0
Redis.stock=300

F CONFIRM 计数:OK (200 == paid 200)
G locked_stock:OK (0 == success-paid=0)
H DB.stock:OK (300 == INIT_STOCK-success=300)
Redis/DB:OK (300 == 300)

=== Phase 3 verify (2026-05-24T15:09:19Z) ===
success=200 paid=200 cancelled=100
TCC.CANCEL(state=1)=100
DB.stock=400 DB.locked_stock=0
Redis.stock=400

I CANCEL 计数:OK (100 == cancelled 100)
J DB.stock 回补:OK (400 == 400)
K locked_stock:OK (0 == 0)
L Redis/DB 一致:OK (400 == 400)
M 抽样取消单 status=4,4,4
M status 全 CANCELLED:OK
```

证明项:
- A 不超卖:成功订单数 200 ≤ 500 **✓**
- B 账平:DB.stock(300) + DB.locked_stock(200) == 500 **✓**
- C Redis/DB 一致:Redis(300) == DB.stock(300) **✓**
- D TCC.TRY 计数(200) == success(200) **✓**
- E 失败码:errors=0 **✓**
- F CONFIRM 计数(200) == paid(200) **✓**
- G locked_stock(0) == success(200) − paid(200) **✓**
- H DB.stock(300) 不变(Confirm 阶段) **✓**
- I CANCEL 计数(100) == cancelled(100) **✓**
- J DB.stock 回补 = INIT − success + cancelled = 500 − 200 + 100 = 400 **✓**
- K locked_stock(0) — paid 订单 Confirm 后已 locked=0,cancel 时 SQL 仅 `stock+=q` **✓**
- L Redis(400) == DB.stock(400) **✓**
- M 抽样取消单 status='CANCELLED'(枚举值 4) **✓**

> **注**:由于 success=200 < INIT_STOCK=500,**抗超卖断言 A 在本次运行中为 vacuous true**(从未发生抢库存竞争);要真正验证抗超卖,需重跑 USERS ≥ 600 以制造竞争,见 §6 假设 1。

## 5. 性能基线

| 指标 | 目标 | 实测 | 达标 |
|---|---|---|---|
| QPS | ≥ 200 | **21.57** | ❌ |
| P99 | < 500ms | **1280ms** | ❌ |
| 错误率(扣 3002 后) | < 0.1% | 0.0% | ✅ |

Grafana 截图(`http://localhost:3000/d/order-perf`):本期未保存,留待 v1.1 性能专项重跑时补。

## 6. 瓶颈分析(3 假设,按概率排序)

### 假设 1:JMeter 参数透传失效,实际只跑 200 并发(P=0.6)

- **观察证据**:`evidence.txt` 顶部 `USERS=200`;`run-all.sh` 默认 `USERS=${USERS:-1000}`,但 docker JMeter 启动行未必透传到 `.jmx` 的 `${__P(users,...)}`
- **验证方法**:`grep -n 'users\|ThreadGroup' perf/order-create.jmx` 看 ThreadGroup 是否硬编码 200;`bash -x perf/run-all.sh 2>&1 | grep -i 'jmeter.*-J'` 看启动行是否含 `-Jusers=1000`
- **优先级**:**最高**;若假设成立,1000 并发跑出的 QPS/P99 才有意义

### 假设 2:Order 服务 HikariCP / Tomcat 线程池触底(P=0.25)

- **观察证据**:QPS=21.57 → 平均 RT ≈ 9.3s(200 / 21.57);TCC 链路单事务持连接时间长(Seata GlobalLock + Redis Lua + outbox + Confirm/Cancel Feign)
- **验证方法**:`grep -rn 'maximum-pool-size\|maximumPoolSize\|server.tomcat.threads' shopsphere-order docs/nacos/shopsphere-order*.yaml*`;Grafana HikariCP panel 看 `pending` 峰值
- **优先级**:中;若 `maximumPoolSize` 默认 10,扩到 50 + Tomcat threads 200 应有明显提升

### 假设 3:Seata TC 单点(file 存储模式)成为热点(P=0.15)

- **观察证据**:每笔 TCC 走 TC 注册 + 全局锁 + 分支提交;200 并发下 file 存储 fsync 串行化
- **验证方法**:`grep -n 'store.mode\|store.session.mode' seata/` 或 docker-compose env;若 file 模式 → 改 db 模式重测同 200 并发
- **优先级**:低;file 模式 + 单 TC 在 standalone 配置下是已知限制

## 7. 调优记录

| 改动 | 前 QPS / P99 | 后 QPS / P99 | 备注 |
|---|---|---|---|
| — | 21.57 / 1280 | — | 用户拍板:本期不调优,留待 v1.1 性能专项 |

## 8. BLOCKER 处置

`perf/run-all.sh` **未中断**(8 步全部成功,一致性 A-M 全绿)。性能 FAIL 不属于"超卖/不一致 BLOCKER",而是性能基线偏差。用户拍板**接受当前数据,以 conditional release 收尾**。

无需手工处置;§6 三个假设留 v1.1 验证。

## 9. 结论

- 综合 **Conditional Release**(一致性 PASS / 性能 FAIL)
- 一致性证据齐:**Y**(A-M 全绿,但抗超卖 A 在 200 并发下 vacuous)
- 性能基线达标:**N**(QPS −91% / P99 +156%)
- 后续 follow-up:**v1.1 性能专项必含 §6 假设 1+2+3 验证**;重跑 USERS=1000 后更新本报告 + [release-checklist.md §15](release-checklist.md)
