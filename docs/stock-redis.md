# Redis 库存原子预扣设计(T2.3)

> 对应任务 T2.3:库存 TCC 的 Redis 子动作。
> 契约依据 `docs/api-contracts.md §4.3`(库存 TCC:Try Lua 预扣 / Cancel 回补)。

---

## 一、背景

库存扣减是电商最易超卖的环节。纯 DB 方案靠 `t_product_stock` 行锁串行化,热点商品下单
会把扣减全部排队,与"高并发抗超卖"冲突。T2.3 引入 Redis 计数器 + Lua 脚本:
**GET + 判断 + DECRBY 三步在脚本内原子执行**,单 Redis 实例天然串行化脚本,既防超卖又不锁 DB 行。

本期只交付库存的 **Redis 子动作**(`StockRedisService`);完整 TCC 编排(DB 条件更新 +
`t_stock_tcc_log` 幂等/空回滚/防悬挂 + `/internal/product/stock/*` 端点)是 T2.4。

---

## 二、key 设计

| key | 内容 | 生命周期 |
|---|---|---|
| `stock:product:{id}` | 当前可售库存(纯数字字符串) | **常驻无 TTL**;启动 `StockWarmupRunner` 从 DB 预热,之后只由 Lua 维护 |

**与 T2.2 详情缓存的关系**:商品详情页的 `stock` 字段就读这个计数器(`StockRedisService.getAvailable`)。
T2.2 的 `ProductCacheService` 已重构:不再自带 TTL 缓存 stock,stock key 所有权移交
`StockRedisService` —— 详情缓存(`product:detail:{id}`)只存静态字段,库存独立常驻。

**为什么无 TTL**:计数器是库存真相的运行时载体,TTL 到期消失会导致 `preDeduct` 返回 -2、
下单失败。常驻 + 启动预热 + 对账任务,三者保证它始终存在且与 DB 一致。

---

## 三、Lua 脚本

脚本在 `shopsphere-product/src/main/resources/scripts/`,`DefaultRedisScript` +
`ClassPathResource` 加载,启动 `SCRIPT LOAD` 预加载(首次业务调用即走 EVALSHA)。

### 3.1 `stock_prededuct.lua` —— 原子预扣(TCC-Try)

```lua
local cur = tonumber(redis.call('GET', KEYS[1]))
if cur == nil then return -2 end
if cur < tonumber(ARGV[1]) then return -1 end
return redis.call('DECRBY', KEYS[1], ARGV[1])
```

### 3.2 `stock_restore.lua` —— 原子回补(TCC-Cancel)

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
    return redis.call('INCRBY', KEYS[1], ARGV[1])
else
    redis.call('SET', KEYS[1], ARGV[1])
    return tonumber(ARGV[1])
end
```

### 3.3 返回码语义

| 操作 | 返回值 | 含义 |
|---|---|---|
| `preDeduct` | `>= 0` | 成功,值为扣减后剩余库存 |
| `preDeduct` | `-1` | 库存不足,未扣减 |
| `preDeduct` | `-2` | key 不存在 —— **不自动初始化**(避免雪崩重建错误库存),让 TCC-Try 失败 |
| `restore` | `>= 回补量` | 回补后的库存值;key 缺失时 SET 兜底 = 回补量 |

> **两个"key 不存在"语义不同,无矛盾**:`preDeduct` 遵守"不自动初始化"返回 -2;`restore`
> 是 Cancel 补偿,回补量是先前已扣的量,SET 兜底恢复部分库存优于丢失。两者都在 Lua 内原子执行。

**为何必须 Lua**:`GET → 判断够不够 → DECRBY` 若拆成 Java 端三次调用,并发下两个线程可能
同时 GET 到 100、各自判断够、各自 DECRBY 50,结果超卖。Lua 脚本在 Redis 单线程内整体执行,
杜绝交错。**禁止 Java 端 GET + SET 组合**。

---

## 四、启动预热

`StockWarmupRunner`(`CommandLineRunner`)启动时:

```
读 t_product_stock 全量 → 对每行 SET stock:product:{id} = max(0, stock - locked_stock)
```

**SET 覆盖**(非 SETNX):每次启动强制以 DB 为准重同步。契约 §4.3 下 TCC-Try 同步改 DB+Redis,
DB 是权威值,重启重同步是特性,不是 bug。负值(`locked > stock`,数据异常)兜底 0。

---

## 五、对账任务

`StockReconciliationTask`(`@Scheduled`,默认每 5min,`product.stock.reconcile-interval-ms` 可配):

```
遍历 t_product_stock → 比较 Redis getAvailable vs DB(stock - locked_stock)
  差异 → ERROR 日志(productId / redis / db 三值)
```

**不自动修复**:库存漂移涉及资金与超卖风险,须人工介入定位根因(是 TCC 中途失败?Redis 丢数据?
代码 bug?)。自动改值可能掩盖问题甚至放大损失。日志样例:

```
ERROR ... stock reconcile MISMATCH: productId=2001, redis=5, db=100
ERROR ... stock reconcile finished: 1/20 mismatches need manual intervention
```

---

## 六、并发安全验证

`StockRedisServiceTest#concurrentPreDeduct_neverOversells`(Testcontainers 真 Redis):

- 初始库存 100,50 线程 × 20 次 = **1000 次** `preDeduct(1)` 尝试
- 断言:**成功扣减次数正好 = 100**(既不超卖 ≤100,也不少卖 ≥100)
- 断言:最终 `getAvailable` = 0

1000 次尝试压 100 库存,Lua 原子性保证恰好 100 次返回 `>=0`、900 次返回 `-1`。这是
"GET+判断+DECRBY 不可分割"的端到端证明。

运行:`mvn -pl shopsphere-product test -Dtest=StockRedisServiceTest`(**需 Docker**)。

---

## 七、失效边界

| 场景 | 行为 |
|---|---|
| Redis 宕 | `preDeduct`/`getAvailable` 抛异常,全局兜底转 1500;TCC-Try 失败。生产需 Redis 高可用 |
| stock key 缺失 | `preDeduct` 返回 -2(不自动初始化);详情页 `getAvailable` 返回 -2 → 只读兜底 DB sellable 展示 |
| 预热窗口期 | `CommandLineRunner` 在 web server 监听后才跑,极短窗口内 key 未就位 → 同"缺失"。生产可改 `SmartLifecycle` 早于 server |
| 多实例对账 | 每实例都跑对账(只读 + 日志,无副作用),最多日志重复。Phase 5 用 Redisson 锁选主 |
| Redis 与 DB 漂移 | 对账任务每 5min 检出 + ERROR 告警;重启时 `StockWarmupRunner` SET 覆盖重同步 |

---

## 八、运行环境注意

`StockRedisServiceTest` 用 Testcontainers 拉 `redis:7-alpine`,**需本地 Docker**。

新版 Docker 守护进程(Docker 25+,`MinAPIVersion=1.44`)会拒绝 testcontainers 1.19.7 内置
docker-java 默认请求的 API 1.43(HTTP 400)。已在 `shopsphere-product/pom.xml` 的
`maven-surefire-plugin` 固定系统属性 `api.version=1.44`,对 Docker 25~29+ 守护进程均兼容。

---

## 九、后续(T2.4)

- `InternalProductController` 三段(`/try` `/confirm` `/cancel`)接入 `StockRedisService` + DB 条件更新
- `t_stock_tcc_log` 幂等表:同 `(orderId, productId)` 的 Try/Confirm/Cancel 各只生效一次
- 空回滚 / 防悬挂控制(契约 §4.3)
- Seata TCC `@TwoPhaseBusinessAction` 注解接入
