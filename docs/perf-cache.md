# 商品详情缓存设计与性能（T2.2）

> 对应任务 T2.2：`GET /api/product/{id}` 接入 Redis Cache-Aside。
> 契约依据 `docs/api-contracts.md §6.2`（Cache-Aside;防穿透/防雪崩）。

---

## 一、背景

商品详情页是电商最高频读路径(经验值占总流量 80~90%)。T2.1 落地时 `getDetail` 每次直查
MySQL,单机 HikariCP 连接池(`maximum-pool-size: 10`)在热点商品下会被打满,QPS 上限被 DB
物理边界锁死。T2.2 把详情**静态字段**上 Redis,使读 QPS 脱离 DB 边界,只受 Redis + 网络限制。

---

## 二、缓存设计

### 2.1 键设计

| key | 内容 | 序列化 | 写入方 |
|---|---|---|---|
| `product:detail:{id}` | 详情静态字段(name/price/description/mainImage/category/status/createdAt),**不含 stock** | `GenericJackson2JsonRedisSerializer`(嵌 `@class`) | 详情 miss 路径 |
| `stock:product:{id}` | 可售量(纯数字字符串) | `StringRedisSerializer` | **T2.3 起由 `StockRedisService` 独占**(常驻无 TTL,启动预热 + Lua 维护,详见 `docs/stock-redis.md`) |
| `lock:product:detail:{id}` | 防击穿互斥锁 | Redisson 内部 | Redisson |

**为什么 stock 不进详情缓存**:库存变动频率远高于商品静态信息。若同存一个 key,每次扣减都要
重建整条详情缓存,或导致缓存与真实库存漂移。拆开后:详情缓存可长 TTL;库存 `stock:product:{id}`
是 **T2.3 起的常驻计数器**(无 TTL,`StockRedisService` 维护)。详情命中后调
`StockRedisService.getAvailable` 拼装返回;计数器缺失则只读兜底 DB sellable,不回填。

### 2.2 设计参数

| 维度 | 取值 | 说明 |
|---|---|---|
| 基准 TTL | 30 分钟 | 静态信息变更低频 |
| TTL 抖动 | `+ [0, 300]s` 随机 | **防雪崩**:`ThreadLocalRandom`,避免大批 key 同刻失效 |
| 空值标记 | `"__NULL__"` | **防穿透**:DB 查不到也缓存 |
| 空值 TTL | 120 秒 | 短 TTL:商品后续可能被创建,不宜久缓存"不存在" |
| 防击穿锁 | Redisson `tryLock(wait=100ms, lease=3s)` | 热点 key 失效瞬间只放一个线程查 DB |
| 抢锁失败重试 | 间隔 100ms × 3 次重读缓存 | 等持锁线程回填;耗尽则降级直查 DB |
| 延迟双删 | 同步 del + 500ms 后 `TaskScheduler` 异步再 del | 消除"删缓存→并发读回填旧值"窗口 |

### 2.3 读流程(Cache-Aside + 三防)

```
GET /api/product/{id}
  │
  ├─ 读 product:detail:{id}
  │    ├─ 命中 "__NULL__"  → null_hit++  → 抛 3001
  │    ├─ 命中对象          → hit++       → 读 stock key 拼装 → 返回
  │    └─ miss              → miss++      → ↓ 防击穿
  │
  └─ Redisson tryLock(lock:product:detail:{id})
       ├─ 拿到锁
       │    ├─ 二次校验缓存(等锁期间可能已回填) → 命中则直接返回
       │    ├─ 查 DB
       │    │    ├─ 不存在 → 写 "__NULL__"(TTL 120s) → 抛 3001
       │    │    └─ 存在   → 写 product:detail(静态,随机 TTL)
       │    │                + 写 stock:product(随机 TTL) → 返回
       │    └─ finally unlock
       └─ 没拿到锁
            ├─ 间隔 100ms 重读缓存 ×3 → 命中则返回
            └─ 仍 miss → db_fallback++ → 直查 DB(不回填,避免与持锁线程写竞争)
```

### 2.4 写失效(延迟双删)

`ProductCacheService.invalidate(id)`:同步 `del` 详情 key → 经 `TaskScheduler` 延迟 500ms
异步再 `del` 一次。第二删消除"第一删后、DB 提交前,有并发读把旧值回填缓存"的窗口。

> **T2.3 起只删 `product:detail:{id}`**:`stock:product:{id}` 是常驻库存计数器,商品静态
> 信息变更不应清空库存,故 `invalidate` 不再触碰 stock key。

> **本期边界**:T2.2 暂无业务写路径(`InternalAdminController` 仍 501),`invalidate` 仅由
> 单元测试覆盖,等 T2.x 后台 update/delete 落地时接入。T2.1 文档里"手工 `UPDATE t_product
> SET status=0`"绕过 Service,不触发失效 —— 已知边界,生产写操作须走 Service。

---

## 三、命中率埋点

`ProductCacheService` 用 Micrometer `Counter` 埋点,全部带 tag `service=shopsphere-product`:

| Counter 名 | 含义 |
|---|---|
| `product.detail.cache.hit` | 详情缓存命中 |
| `product.detail.cache.miss` | 详情缓存未命中 |
| `product.detail.cache.null_hit` | 命中空值标记(防穿透生效) |
| `product.detail.cache.db_fallback` | 抢锁失败 + 重试耗尽,降级直查 DB |
| `product.detail.cache.invalidate` | 主动失效(双删)次数 |

**读取**(`management.endpoints.web.exposure.include` 已含 `metrics`):

```bash
curl localhost:8082/actuator/metrics/product.detail.cache.hit
curl localhost:8082/actuator/metrics/product.detail.cache.miss
```

**命中率算式**:

```
命中率 = hit / (hit + miss + null_hit)
```

> Phase 5 治理:接 `micrometer-registry-prometheus` + Grafana 看板,本期仅 `/actuator/metrics` 拉取。

---

## 四、压测方法

脚本:`scripts/perf/product-detail-cache-load.k6.js`

```bash
brew install k6                                    # 一次性
k6 run scripts/perf/product-detail-cache-load.k6.js
# 自定义地址 / 商品 id：
k6 run -e BASE=http://localhost:8080 -e PID=2001 scripts/perf/product-detail-cache-load.k6.js
```

- 1 VU 预热 3s 填缓存 → 恒定到达率 500 req/s 压 30s
- 阈值断言:`http_req_duration{scenario:main} p(99) < 5ms`、`http_req_failed rate < 1%`、
  `biz_code_err count == 0`

**对照组**(看缓存价值):压测前 `redis-cli flushdb`,或停 Redis 容器,重跑 → p99 退化到
DB 查询量级。

---

## 五、缓存前后对比(实测填表)

> 实测环境:本机 docker(MySQL 8 / Redis 7),product 单实例。运行后回填。

| 指标 | T2.1 无缓存(DB only) | T2.2 有缓存 | 备注 |
|---|---|---|---|
| QPS(达标上限) | _待测_ | _待测_ | 阈值内最大稳定吞吐 |
| p50 延迟 | _待测_ | _待测_ | |
| p99 延迟 | _待测_ | _待测_ | 目标 < 5ms |
| p99.9 延迟 | _待测_ | _待测_ | |
| DB QPS | ≈ 客户端 QPS | ≈ 0(预热后) | 缓存命中后 DB 几乎零压 |
| 缓存命中率 | — | _待测_ | 目标 ≥ 99% |

测法:`k6 run` 输出读 `http_req_duration` 各分位;DB QPS 看 MySQL `SHOW GLOBAL STATUS LIKE
'Questions'` 前后差;命中率读 `/actuator/metrics`。

---

## 六、一致性边界与失效模式

| 场景 | 行为 | 影响 |
|---|---|---|
| Redis 宕 | `RedisTemplate` 操作抛异常,全局兜底转 1500 | 详情接口不可用。生产需 Redis 哨兵/集群 |
| Redisson 宕 | `tryLock` 抛异常 → 转 1500 | 同上。可独立评估降级为"直查 DB" |
| `TaskScheduler` 异常 | 双删第二步丢失 | 首删已生效,可容忍;最坏多一个 TTL 周期的旧值 |
| 缓存 / DB 短暂不一致 | TTL 到期或下次 `invalidate` 自愈 | 静态字段容忍秒级延迟 |
| 缓存雪崩 | TTL 0~300s 抖动 → 失效时刻打散 | 不会出现大批 key 同刻击穿 DB |
| 缓存击穿 | Redisson 锁 → 单线程查 DB 回填 | 热点 key 失效瞬间 DB 不被打爆 |
| 缓存穿透 | 不存在的 id 也缓存 `__NULL__` 120s | 恶意刷不存在 id 不会持续穿透到 DB |

---

## 七、后续演进

- **T2.3 已落地**:`stock:product:{id}` 升级为常驻计数器,由 `StockRedisService` Lua 原子维护
  (启动预热 + 不自动初始化 + 对账)。`ProductCacheService` 已重构为委托读取,详见 `docs/stock-redis.md`。
- **T2.x 后台写**:`update/delete` 落地时调 `ProductCacheService.invalidate(id)` 接入双删。
- **Phase 5**:Prometheus + Grafana 缓存看板;评估二级本地缓存(Caffeine)挡极热点 key。
