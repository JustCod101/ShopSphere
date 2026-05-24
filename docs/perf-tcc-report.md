# 下单链路压测报告(T5.3)

> 模板。`perf/run-all.sh` 跑完后用 `perf/results/evidence.txt`、
> `perf/results/aggregate.csv`、Grafana 截图、JMeter HTML 报告填充各章节。
> 失败场景请填 §8。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 主机 | (macOS / Linux + CPU/RAM) |
| Docker 版本 | (`docker version`) |
| Compose 服务版本 | (`git rev-parse HEAD`) |
| MySQL | 8.0.36(单实例) |
| Redis | 7.x(单实例) |
| RabbitMQ | 3.x + 延迟插件 |
| Nacos / Seata / Sentinel | standalone |
| Java 服务 JVM | `Xmx=?`、单实例 |

## 2. 压测参数

- 用户:1000(`USERS`)
- 库存:500(`INIT_STOCK`)
- 商品:2001(`PRODUCT_ID`)
- rampup:1s
- 每用户购买:1 件
- X-Request-Id:每请求新 UUID(JMeter `${__UUID()}`)
- Sentinel 规则:(对 `/api/order/create` 是否有 QPS 限流?压测前抓一次 Nacos 配置 `sentinel-rules-order-flow.json`)
- Seata 模式:TCC(库存)+ 本地消息表(订单事件)

## 3. 三阶段执行结果

| 阶段 | 样本 | 成功 | QPS | Avg(ms) | P95(ms) | P99(ms) | Err% |
|---|---:|---:|---:|---:|---:|---:|---:|
| Create | 1000 | ? | ? | ? | ? | ? | ? |
| Pay    | ?    | ? | ? | ? | ? | ? | ? |
| Cancel | 100  | ? | ? | ? | ? | ? | ? |

> Create 阶段从 `perf/results/aggregate.csv`(JMeter 自动产出)填;Pay/Cancel 阶段从脚本 stdout + `time` 包装填。

## 4. 一致性证据

直接粘贴 `perf/results/evidence.txt`(三阶段实测值合集)。

```text
(此处粘贴 evidence.txt)
```

证明项:
- A 不超卖:成功订单数 ≤ 500 ✓ / ✗
- B 账平:DB.stock + DB.locked_stock == 500 ✓ / ✗
- C Redis/DB 一致:Redis == DB.stock ✓ / ✗
- D TCC.TRY 计数 == success ✓ / ✗
- E 失败码:全 3002(errors=0) ✓ / ✗
- F CONFIRM 计数 == paid ✓ / ✗
- G locked_stock == success - paid ✓ / ✗
- H DB.stock 不变(Confirm 阶段) ✓ / ✗
- I CANCEL 计数 == cancelled ✓ / ✗
- J DB.stock 回补 = INIT_STOCK - success + cancelled ✓ / ✗
- K Redis 回补 ✓ / ✗
- M 抽样取消单 status='CANCELLED' ✓ / ✗

## 5. 性能基线

| 指标 | 目标 | 实测 | 达标 |
|---|---|---|---|
| QPS | ≥ 200 | ? | ? |
| P99 | < 500ms | ? | ? |
| 错误率(扣 3002) | < 0.1% | ? | ? |

Grafana 截图(http://localhost:3000/d/order-perf):
- 截 QPS panel
- 截 P99 latency panel
- 截 HikariCP 活跃 / pending(若 pending > 0 → 连接池打满是瓶颈)
- 截 JVM heap(看 GC 抖动)

## 6. 瓶颈分析

如有指标未达标,列 3 个根因假设(概率排序):

### 假设 1:(填写)
- 观察证据:
- 验证方法:
- 优先级:

### 假设 2:(填写)

### 假设 3:(填写)

常见怀疑方向(参考):
- MySQL 连接池打满(HikariCP `connections_pending` > 0)
- Redis Lua 单线程在热点 key `stock:product:2001` 上排队
- Seata TC 单点处理 1000 全局事务的开销
- Sentinel `/api/order/create` 限流命中(看错误码桶)
- Nacos 心跳 / Feign 路由解析在压测期内被压住

## 7. 调优记录

| 改动 | 前 QPS / P99 | 后 QPS / P99 | 备注 |
|---|---|---|---|
| - | - | - | - |

如不调优只复盘,留空。

## 8. BLOCKER 处置(仅失败时填)

如 `perf/run-all.sh` 中断,粘 `perf/results/blocker-snapshot.txt` 关键内容:

```text
(blocker-snapshot.txt 内容)
```

### 根因假设(3 个,排序)

1. **(最可能)** :
   - 证据:
   - 验证方法:
2. :
3. :

### 选项(等拍板)

- A 改源码 `xxx.java:line` …
- B 改配置 …
- C 不修复,记入"已知问题"

## 9. 结论

- 综合 PASS / FAIL:
- 一致性证据齐:Y / N
- 性能基线达标:Y / N
- 后续 follow-up:(若有)
