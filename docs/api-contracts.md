# ShopSphere — API 契约 & 跨服务约定

> 本文件是 CLAUDE.md 指定的**强制必读**文档，与 `docs/architecture.md` 配套。
> 任何新增接口 / Feign 客户端 / 跨服务交互，必须先符合本文件约定。
> 标注 ⚠️**待拍板** 的条目是从 architecture.md 推导出的设计决策，开工前需确认。

---

## 一、统一响应包装 `Result<T>`

CLAUDE.md 强制："所有外部接口返回 Result<T> 统一包装"。**包括 Python 推荐服务**，须返回同构 JSON。

### 1.1 结构

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "traceId": "a1b2c3d4e5",
  "timestamp": "2026-05-19T10:30:00"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | `0` = 成功；非 0 = 业务/系统错误，见错误码表 |
| `message` | string | 面向调用方的可读信息，不含堆栈 |
| `data` | T \| null | 成功时为业务数据，失败时为 `null` |
| `traceId` | string | 链路追踪 ID，由 Gateway 生成并透传（见 §3） |
| `timestamp` | string | **`OffsetDateTime`，UTC，ISO-8601 带偏移**（如 `2026-05-19T10:30:00Z`）。禁止 `new Date()`；跨服务/对外字段一律 UTC OffsetDateTime，纯进程内无歧义场景方可 LocalDateTime |

> ✅ **M3 已拍板**：CLAUDE.md「禁止事项」该条已同步修订为「禁止 `new Date()`，统一 `java.time`；对外/跨服务用 `OffsetDateTime`(UTC)，纯进程内无歧义场景方可 `LocalDateTime`」。本节为权威细则，CLAUDE.md 反向引用本节。

- 成功统一 `Result.ok(data)`；失败统一由 `BusinessException` + 全局 `@RestControllerAdvice` 转换，**Controller 不得手工拼 Result 错误体**。
- HTTP 状态码：业务错误一律返回 **HTTP 200**，错误信息在 `code` 中体现；仅网络/网关/未捕获异常用 4xx/5xx。

### 1.2 分页结构

列表接口 `data` 统一用 `PageResult<T>`（对齐 MyBatis-Plus 分页）：

```json
{
  "code": 0,
  "data": {
    "records": [],
    "total": 128,
    "page": 1,
    "size": 20
  }
}
```

请求分页参数统一：`?page=1&size=20`（`page` 从 1 起；`size` 上限 100，超出截断）。

---

## 二、错误码规范

格式：`{服务段}{三位序号}`，`0` 保留为成功。按服务划分号段，避免冲突：

| 号段 | 归属 | 示例 |
|---|---|---|
| `0` | 成功 | `0 ok` |
| `1xxx` | 通用 / common | `1000` 参数校验失败、`1001` 未认证、`1003` 限流(Sentinel)、`1004` 资源不存在、`1500` 系统内部错误 |
| `2xxx` | User 服务 | `2001` 用户名已存在、`2002` 密码错误、`2003` 用户不存在 |
| `3xxx` | Product 服务 | `3001` 商品不存在、`3002` 库存不足、`3003` 库存预扣失败 |
| `4xxx` | Order 服务 | `4001` 订单不存在、`4002` 订单状态非法、`4003` 全局事务回滚 |
| `5xxx` | Recommendation 服务 | `5001` 用户无行为数据(冷启动)、`5002` 模型未就绪 |

- 错误码常量集中定义在 `shopsphere-common`（如 `ErrorCode` 枚举），所有 Java 服务引用，禁止散落魔法数字。
- Python 服务维护一份对齐的 `error_code.py`，号段 `5xxx`，复用通用 `1xxx`。
- **M5 已拍板 · 通用码 vs 服务段码边界**：业务语义明确的"资源不存在"**一律用服务段码**（用户不存在 `2003`、商品不存在 `3001`、订单不存在 `4001`）；通用 `1004` **仅用于 Gateway 路由层未匹配路由 / 未归类资源**，业务服务内禁止返回 `1004`。
- **`1003` 由 Sentinel 触发（T1.5 已落地）**：网关层按 API 分组、业务层按 URI 双层限流；规则在 Nacos `shopsphere-{gateway,user}-flow-rules.json` 热加载；响应固定 `HTTP 200 + body.code=1003 + traceId`。详见 `docs/sentinel-rules.md`。

---

## 三、Gateway → 下游 用户上下文透传契约 ✅**已拍板**

CLAUDE.md 铁律："禁止在业务服务中校验 JWT（网关已处理）"。Gateway 解析 JWT 后，通过 **请求头** 透传上下文，下游服务直接信任：

| Header | 类型 | 说明 |
|---|---|---|
| `X-User-Id` | long | 用户 ID，未登录接口不带此头 |
| `X-User-Name` | string | 用户名（URL-encoded，可选） |
| `X-Trace-Id` | string | 链路 ID，全链路透传，进 `Result.traceId` 与日志 MDC |

约定（已拍板，三头名固定，不再变更）：
- 下游用统一 `UserContextInterceptor`（放 `shopsphere-common`）从 header 读取，封装到 `ThreadLocal` UserContext，**禁止业务代码直接读 header**。
- **安全**：Gateway 必须在路由前主动**剥离外部传入的 `X-User-*` 与 `X-Trace-Id` 头**，再由网关重新注入，防伪造。
- `X-Trace-Id`：Gateway 全局过滤器生成（UUID 去横线，32 位 hex），全链路透传，写入 `Result.traceId` 与日志 MDC（key 约定 `traceId`）；**仅内部链路，不回写客户端响应头**。
- `X-User-Name`：Gateway 以 `application/x-www-form-urlencoded`（UTF-8，`URLEncoder.encode`）编码后写头；下游 `UserContextInterceptor` 必须 `URLDecoder.decode(UTF-8)` **对称解码**（解码失败回退原值，不抛异常）。
- 需要登录的接口若无 `X-User-Id` → 返回 `1001` 未认证（由 common 拦截器统一处理）。

### 3.1 Gateway 公开路径白名单 ✅**M1 已拍板**

- **载体**：Nacos Config，`dataId=shopsphere-gateway.yaml`，路径 `security.whitelist`（数组），Gateway 启动加载并支持热更新。
  > 实现说明（T1.2 拍板）：热更新由 `@ConfigurationProperties` + Spring Cloud `ConfigurationPropertiesRebinder` 自动重绑实现（**非 `@RefreshScope`**——后者惰性重建，无访问者不触发，不适用于过滤器持有的单例）。白名单字段以 `volatile` 不可变拷贝整体替换，读写无锁一致。
- **匹配**：Ant 风格路径匹配；命中白名单 → 跳过 JWT 校验直接放行（不注入 `X-User-*`）；未命中 → 强制 JWT，失败返回 `1001`。
  > 版本前缀归一（T1.2 拍板，对齐 §10）：白名单按**去版本**对外路径配置（即 `/api/product/**`，不写 `/api/v1/product/**`）。Gateway 鉴权判定前将 `/api/v1/<x>` 归一为 `/api/<x>` 再做 Ant 匹配，与下游 `RewritePath`（剥离 `v1`）语义一致，使 `/api/**` 与 `/api/v1/**` 鉴权决策恒等。新增版本段时归一规则需同步扩展。
- **`/internal/**` 不在白名单也不放行**，由 §4.1 路由层显式拒绝（优先级高于白名单）。
- **CORS 预检放行**：跨域 `OPTIONS` 预检不带 `Authorization`，由 Nacos `spring.cloud.gateway.globalcors` 在网关 handler 映射阶段短路（先于 GlobalFilter 链）；`JwtAuthFilter` 对 `OPTIONS` 额外显式放行作机制无关的防御纵深，预检不计入鉴权。（无独立 `CorsWebFilter` bean。）
- 初始白名单（开工基线，可经 Nacos 增改）：

```yaml
security:
  whitelist:
    - /api/user/register
    - /api/user/login
    - /api/product/**          # 商品查询公开（GET）
    - /api/recommend/similar/**
    - /api/recommend/health
```

> 注：`/api/recommend/user/**` 需登录（A），不在白名单。受保护路径即便公开服务也强制 JWT。

---

## 四、内部接口（Feign）约定

### 4.1 路径与暴露

- 内部接口统一前缀 `/internal/**`，对外接口前缀 `/api/**`。
- **Gateway 路由白名单只放行 `/api/**`，显式拒绝 `/internal/**`**（防外部直调内部接口）。
- 内部接口同样返回 `Result<T>`。
- **C2 已拍板 · 服务间调用不经 Gateway**：所有服务间 Feign 调用走 **Nacos 服务发现直连**（`spring-cloud-loadbalancer`），**不经过 Gateway**。Gateway 只负责对外流量。架构图 §1.1 原标注「Feign(Java→Py via Gateway)」据此修正。
- 服务间调用无用户 JWT；下游内部接口**不做 JWT 校验**（CLAUDE.md 铁律），仅以 `/internal/**` 不暴露于 Gateway + 网络隔离保证安全。如需用户上下文，由调用方在 Feign 请求头显式透传 `X-User-Id`（`RequestInterceptor` 从当前 `UserContext` 注入）。

### 4.2 Feign 客户端清单

接口定义集中在 `shopsphere-api`，命名遵循 CLAUDE.md：`XxxFeignClient`，必须配 Sentinel `fallback`。

| Feign 接口 | 模块 | 提供方 | 用途 |
|---|---|---|---|
| `UserFeignClient` | `user-api` | User | `GET /internal/user/{id}` 查用户 |
| `ProductFeignClient` | `product-api` | Product | `GET /internal/product/{id}` 查详情 + 库存 TCC `/internal/product/stock/{try,confirm,cancel}`（见 §4.3）。**T2.4 已落地契约 + 骨架,完整 Seata TCC T3.3** |
| `OrderFeignClient` | `order-api` | Order | （预留，按需）|

- **C2 已拍板 · 推荐服务无 Java Feign 客户端**：推荐接口由**前端经 Gateway 直连 Python**（`/api/recommend/**`，带用户 JWT，Gateway 校验）。原 architecture.md T4.4「Java 经 Feign 调推荐」**取消**（无明确业务调用方，徒增鉴权矛盾）。若未来 Java 侧确需聚合推荐，再走 `/internal/recommend/**` + Nacos 直连，并在 `recommendation-api` 补 `RecommendationFeignClient`（fallback 返回热门兜底，fallback 在 Java 调用方侧实现，Python 无 Sentinel）。

### 4.3 库存 TCC 与幂等约定 ✅**S1/S2/S3 已拍板（库存+事务重设计）**

**模式变更**：库存分支由 **Seata AT 改为 Seata TCC**。理由：(a) AT 在 `t_product_stock` 持全局行锁，热点商品下单串行化，与"高并发抗超卖"冲突（S2）；(b) AT 只回滚 DB，回滚不了 Redis 预扣，导致 Redis/DB 库存漂移少卖（S1）。TCC 由业务在 `Cancel` 阶段显式回补 Redis，彻底解决。

库存接口（`ProductFeignClient`，幂等键统一 **`(orderId, productId)`**，`xid` 仅作 TCC 事务关联日志，**不再作幂等键** — 解决 S3）：

| 阶段 | 接口 | DB 动作 | Redis 动作 |
|---|---|---|---|
| Try | `POST /internal/product/stock/try` | `stock-=q, locked_stock+=q WHERE stock>=q`（条件更新，不足返回 `3002`） | Lua 原子预扣 `stock:product:{id} -= q` |
| Confirm | `POST /internal/product/stock/confirm` | `locked_stock-=q`（真实出库，支付成功时调用） | 无（Try 已扣，不重复） |
| Cancel | `POST /internal/product/stock/cancel` | `stock+=q, locked_stock-=q` | **显式回补 `stock:product:{id} += q`** |

幂等与 TCC 异常控制（落幂等表 `t_stock_tcc_log(order_id, product_id, phase, state)`）：
- 同一 `(orderId, productId)` 的 Try/Confirm/Cancel 各自重复调用只生效一次。
- **空回滚**：Cancel 先于 Try 到达（Try 失败/超时）→ 记录空回滚标记，直接成功返回，**不执行库存回补**。
- **防悬挂**：Try 检测到该 `(orderId, productId)` 已有 Cancel 记录 → 拒绝执行 Try（返回 `3003`）。
- Cancel 以"未扣减"为安全态（未 Try 不报错）。

```
POST /internal/product/stock/try
{ "xid": "...", "orderId": 123, "items": [ { "productId": 1, "quantity": 2 } ] }
POST /internal/product/stock/confirm   { "xid": "...", "orderId": 123 }
POST /internal/product/stock/cancel    { "xid": "...", "orderId": 123 }
```

> ✅ **T3.3 已落地**：库存 TCC 三段以**业务 TCC**实现（显式幂等端点，非 Seata
> `@TwoPhaseBusinessAction` —— Confirm 须延迟到 `/pay`，Seata 二阶段回调无法跨请求）。
> `t_product_stock` 条件更新（Try `stock-=q,locked+=q` / Confirm `locked-=q` /
> Cancel `stock+=q,locked-=q`）与 `t_stock_tcc_log` 同一本地事务；幂等、空回滚（订单级标记
> `productId=0`）、防悬挂、Cancel 显式回补 Redis 均落地。`stock` 列语义明确为**可售库存池**
> （Redis 镜像之，真实总量 = `stock + locked_stock`）。失败演练见 `docs/tcc-rollback-report.md`。

---

## 五、服务注册名 & 端口分配 ✅**已拍板**

Gateway 路由、`docker-compose`、Nacos 注册统一以此为准：

| 服务 | Nacos serviceName | 端口 | 路由前缀 | 库名 |
|---|---|---|---|---|
| Gateway | `shopsphere-gateway` | 8080 | — | — |
| User | `shopsphere-user` | 8081 | `/api/user/**` | `shopsphere_user` |
| Product | `shopsphere-product` | 8082 | `/api/product/**` | `shopsphere_product` |
| Order | `shopsphere-order` | 8083 | `/api/order/**` | `shopsphere_order` |
| Recommendation | `shopsphere-recommendation` | 8000 | `/api/recommend/**` | `shopsphere_reco`（自有，见 §7） |

基础设施：Nacos `8848`、MySQL `3306`、Redis `6379`、RabbitMQ `5672/15672`、Seata `8091`、Sentinel Dashboard `8858`。

> 注（T0.3 补）：`docker-compose.infra.yml` 另发布若干运维必需端口，非业务契约，不冲突：Nacos `9848/9849`（Nacos 2.x 客户端 gRPC，**必需**，否则服务注册失败）、Seata `7091`（Server 控制台/健康）。

---

## 六、对外接口契约（按服务）

> 以 architecture.md §2 为基准，此处固化路径、鉴权、出入参要点。`A` = 需登录（带 `X-User-Id`），`P` = 公开。

### 6.1 User `/api/user`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/user/register` | P | body: username/password/email/phone；冲突 `2001` |
| POST | `/api/user/login` | P | 返回 `{ token, expiresIn(秒) }`；用户不存在 `2003`、密码错 `2002`；命中防爆破锁定（§10）返 `2002` + `message="账号已临时锁定，请稍后再试"`（与限流复用同号，仅 message 区分，避免账号枚举） |
| GET | `/api/user/me` | A | 当前用户信息，响应**不含** `passwordHash` |
| POST | `/api/user/behavior` | A | body: `{itemId, actionType: view\|cart\|order, extra?}`；同步落 `t_user_behavior` 后 `@TransactionalEventListener(AFTER_COMMIT)` 异步发 MQ `shopsphere.behavior / user.behavior`（🟡 轻量，§8）；DB 失败 `1500`；actionType 非法 `1000` |
| GET | `/internal/user/{id}` | 内部 | Feign，签名 `UserFeignClient.getById`；不存在 `2003` |

### 6.2 Product `/api/product`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/product/{id}` | P | T2.1 已落地（基础查询）；T2.2 接入 Cache-Aside（防穿透空值缓存 / 防雪崩 TTL 随机）。`data.stock = stock - locked_stock`（可售量，§4.3） |
| GET | `/api/product/list?categoryId=&keyword=&page=&size=` | P | T2.1 已落地。`PageResult`；`size` 上限 100 超出截断；`keyword` 走 name LIKE 模糊匹配 |
| GET | `/api/product/category/tree` | P | T2.1 已落地（实际挂在 `/api/product/category/tree`，复用现有 `/api/product/**` 路由 + 白名单，零 Gateway 改动） |
| GET | `/internal/product/{id}` | 内部 | Feign `ProductFeignClient.getDetail`，返回 `ProductDetailDTO`；与公开 `/api/product/{id}` 数据同源。**T2.4 落地** |
| POST | `/internal/product/stock/try` | 内部 | 库存 TCC-Try：预留(locked)，幂等（§4.3）。**T2.4 落地接口骨架（幂等表 + Redis 预扣），完整 Seata TCC T3.3** |
| POST | `/internal/product/stock/confirm` | 内部 | 库存 TCC-Confirm：真实出库（支付成功），幂等。**T2.4 骨架，完整 TCC T3.3** |
| POST | `/internal/product/stock/cancel` | 内部 | 库存 TCC-Cancel：释放并回补 Redis，幂等。**T2.4 骨架，完整 TCC T3.3** |

### 6.3 Order `/api/order`

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/order/create` | A | `@GlobalTransactional` 发起方（TCC-Try 预留）；成功后发 `order.created`。**幂等：必填 header `X-Request-Id`** |
| POST | `/api/order/{id}/pay` | A | 支付成功回调/确认 → 触发库存 Confirm，状态 `CREATED→PAID` |
| GET | `/api/order/{id}` | A | 仅本人可见（用 `X-User-Id` 校验归属；不存在/非本人均 `4001`）|
| GET | `/api/order/list?status=&page=&size=` | A | 当前用户订单（强制 `user_id` 过滤）；`Result<PageResult<OrderVO>>` |
| POST | `/api/order/{id}/cancel` | A | 取消并回补库存（调 `/internal/product/stock/cancel`），幂等 |

**M2 已拍板 · `/api/order/create` 出入参固化**（`userId` 取自 `X-User-Id`，不在 body）：

```
POST /api/order/create
Header: X-Request-Id: <客户端生成 UUID, 必填>     # S5 防重复下单
Body:   { "items": [ { "productId": 1, "quantity": 2 } ], "addressId": 1001, "remark": "可选" }
Resp:   data: { "orderId": 123, "status": "CREATED", "totalAmount": 199.00,
                 "payExpireAt": "2026-05-19T11:00:00Z" }
```
- **S5 幂等**：服务端按 `(userId, X-Request-Id)` 去重，TTL 24h；重复请求返回**首次结果**（非报错）。缺 `X-Request-Id` → `1000` 参数校验失败。

订单状态机 ✅**已拍板（定稿，S4 含超时）**：`CREATED → PAID → SHIPPED → COMPLETED`，旁支 `CREATED/PAID → CANCELLED`。
- **下单语义重定义**：`create` 仅做库存 **TCC-Try 预留**（`locked_stock`），订单 `CREATED`；**支付成功（`/pay`）才 TCC-Confirm 真实扣减** → `PAID`。
- **S4 未支付超时**：`create` 成功即投递 RabbitMQ 延迟消息 `order.payment.timeout`（**默认 30 分钟**，`payExpireAt` 返回前端）。到期仍 `CREATED` → 自动 `CANCELLED` + 调 `stock/cancel` 释放 locked 库存（幂等）。
- **`SHIPPED` 之后不可取消**（仅 `CREATED`、`PAID` 可转 `CANCELLED`）；`PAID` 取消需先 Confirm 已扣，取消按"逆向补偿"调 `stock/cancel` 回补。
- 非法状态流转 → `4002` 订单状态非法。
- 取消/超时的库存释放幂等键 = `(orderId, productId)`（§4.3），与下单 Try/Confirm 同键，天然防重。

> ✅ **T3.1 已落地（地基）**：Order 库事务相关表经 Flyway 建好 —— `t_order` / `t_order_item` /
> `t_order_request`（S5 幂等，`uk_user_req`）/ `t_local_message`（C3 本地消息表，`status`
> 0=PENDING/1=SENT/2=CONFIRMED/3=FAILED）/ `undo_log`（Seata AT）。表结构见 `docs/architecture.md §2.3`。
> Order/Product/User 三服务接入 Seata 客户端（`tx-service-group=shopsphere-tx-group`），
> XID 透传验证清单见 `docs/seata-verify.md`。下单/支付/取消业务逻辑见 T3.2，库存 TCC 完整语义见 T3.3。

> ✅ **T3.2 已落地**：`POST /api/order/create` 实现 —— `@GlobalTransactional` 发起，Feign 校验商品 +
> 库存 TCC-Try，本地建单并按 C3 写 `t_local_message`（`order.created` + `order.payment.timeout` 均
> PENDING）。S5 幂等（`X-Request-Id` + `t_order_request`，TTL 24h 定时清理）。
> 库存 TCC-Confirm/Cancel、`t_local_message` 的 outbox 中继投递见 T3.3 及后续。

> ✅ **T3.3 已落地**：`POST /api/order/{id}/pay` 实现 —— `@GlobalTransactional` 校验
> （订单存在 / 归属 / 状态 `CREATED`）→ 本地 `CREATED→PAID`（条件更新防并发）→ Feign
> `stockConfirm` 触发库存 TCC-Confirm。库存 TCC 三段完整语义（幂等 / 空回滚 / 防悬挂 /
> Redis 回补）见 §4.3 注与 `docs/tcc-rollback-report.md`。`/create` 已重排（stockTry 置于本地
> 落库之后），闭合 T3.2 库存泄漏缺口。

> ✅ **T3.5 已落地**：补全订单查询 / 取消 / 状态机 / 超时延迟消费 ——
> `GET /api/order/{id}`（含明细，归属校验不暴露存在性）、`GET /api/order/list`（强制 `user_id`
> 过滤 + 分页 `PageResult`）、`POST /api/order/{id}/cancel`（`@GlobalTransactional`：状态机校验 →
> 条件 UPDATE 取消 → Feign `stockCancel` 回补；Redis `cancel:lock:{orderId}` 防并发）。
> 状态机统一由 `OrderStatusTransitionValidator` 管理（合法迁移：`CREATED→PAID/CANCELLED`、
> `PAID→SHIPPED/CANCELLED`、`SHIPPED→COMPLETED`），`pay`/`cancel` 均经其校验。
> **PAID 取消逆向补偿已落地**：Product `cancelStock` 对已 Confirm 的商品按 `stock+=q` 退回可售池。
> 未支付超时：`order.payment.timeout` 经 `q.order.timeout.wait`（TTL+DLX）延迟 30min 死信进
> `q.order.timeout`，`OrderTimeoutConsumer` 触发系统取消（仅取消仍 `CREATED` 的订单）。
> 拓扑见 `docs/mq-topology.md`。

### 6.4 Recommendation `/api/recommend`（FastAPI，返回同构 `Result`）

**路径以本表为唯一标准**（解决 architecture.md 中 `/recommend/{user_id}` 与 `/api/recommend/user/{user_id}` 的不一致）：

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/recommend/user/{userId}?topk=10` | A | 个性化召回；冷启动回退热门（见 C1） |
| GET | `/api/recommend/similar/{itemId}?topk=10` | P | i2i 相似商品 |
| POST | `/internal/recommend/train` | 内部 | 手动触发离线训练（改为 `/internal`，不对外） |
| GET | `/api/recommend/health` | P | 健康检查（Nacos 心跳由 nacos-sdk-python 主动上报维护，本端点供探活/就绪） |

**C1 已拍板 · 冷启动语义**：用户无行为数据时**视为成功**，回退热门 Top-N：

```json
{ "code": 0, "message": "cold-start fallback",
  "data": { "items": [ ... ], "fallback": true }, "traceId": "...", "timestamp": "..." }
```
- `code` 恒为 `0`（与 §1.1「非 0=失败、data=null」一致）；用 `data.fallback=true` 标识回退。
- `5001` **不进 `Result.code`**，仅作监控埋点 / 日志维度（统计冷启动占比），`5002 模型未就绪`同理（未就绪时也回退热门 + `fallback=true`）。
- Python 侧需实现与 Java `UserContextInterceptor` 等价的中间件：从 `X-Trace-Id` 读取并回写 `Result.traceId`、`X-User-Id` 入上下文。

> ✅ **T4.1 已落地（骨架）**：FastAPI 项目搭起 —— `UserContextMiddleware`（X-User-Id/X-Trace-Id →
> contextvar；受保护前缀 `/api/recommend/user/**` 缺 `X-User-Id` → `1001`）+ 全局异常 handler
> （Result.fail 同构、业务错误 HTTP 200）+ `Result` dataclass（`timestamp` 用 `datetime.now(timezone.utc).isoformat()`，与 Java
> `OffsetDateTime` UTC 同构）。错误码 `5001/5002` 仅监控埋点不进 `code`（C1）。Nacos 注册 + 配置拉取（`shopsphere-recommendation.yaml` 公共 +
> `shopsphere-recommendation-{profile}.yaml` 机密；nacos-sdk-python 同步 SDK，启动期一次拉取，运行时回调线程托管）。
> 自有库 `shopsphere_reco` 经 Alembic 迁移建表（`behavior_event` / `t_train_log`）。
> MQ 行为消费者 `q.reco.behavior` 双绑 `shopsphere.behavior:user.behavior` + `shopsphere.order:order.created`
> （后者展开为多条 `BehaviorEvent`，`eventId = order-{orderId}-{productId}`），手动 ack，幂等键 `INSERT ... ON DUPLICATE KEY UPDATE`，
> 失败 `nack(requeue=False)` → `shopsphere.reco.dlx` (fanout) → `q.reco.behavior.dlq`；同时 ZADD
> `user:behavior:{userId}` 供后续在线召回。

> ✅ **T4.2 已落地（离线训练）**：`service/itemcf.py` 实现 ItemCF —— 流式分批读
> `behavior_event`（pandas server-side cursor + chunksize 10000）→ 隐式反馈打分（view=1/cart=3/order=5）
> → `scipy.sparse.csr_matrix` 用户-物品矩阵 → L2 列归一 → `sim = X^T @ X` → 热门惩罚
> `sim[i,j] /= log(1 + popularity[j])` → 每 item Top-50 邻居 → Redis ZSET `sim:item:{itemId}`（TTL 25h，
> 先 DEL 再 ZADD 避免旧邻居残留）。冷启动热门：近 7 天 `sum(weight)` Top-100 → ZSET `hot:items:global`（TTL 25h）。
> 训练成功置 `reco:model:ready=1`（TTL 26h，**health.py 读此 key**——T4.1 旧 `model:sim:ready` 已修正）。
> 调度：`AsyncIOScheduler(timezone=utc)` cron `0 2 * * *` 每日 02:00 UTC 全量训练；
> `POST /internal/recommend/train` **异步 fire-and-forget**（Redis NX 锁 `reco:training` EX 3600 + 立返
> `{triggered, runId}`，executor 跑训练，**任何异常落 `t_train_log.FAILED + error`，不抛崩 FastAPI**）。
> Cron 默认从 `0 3 * * *` 更新为 `0 2 * * *`（与任务文一致；`docs/nacos/shopsphere-recommendation.yaml` 同步）。

> ✅ **T4.3 已落地（在线推荐接口）**：`service/recall.py` 实现两条召回路径 ——
> `GET /api/recommend/user/{userId}`：先查结果缓存 `rec:user:{userId}:topk:{topk}`（TTL 10min；模型未就绪时短 TTL 60s）；
> 未命中则 `EXISTS reco:model:ready` 检查模型就绪 → `ZREVRANGE user:behavior:{userId} 0 19` 取最近 20 个种子 item →
> pipeline 一次 `ZREVRANGE sim:item:{i} 0 49 WITHSCORES` → `score[nb] += (1/(1+idx)) * sim_score` 衰减加权聚合 →
> 过滤已下单（缓存 `reco:purchased:user:{userId}` SET TTL 30min，miss 时一次 `SELECT DISTINCT item_id FROM behavior_event
> WHERE user_id=? AND action_type='order' AND ts >= NOW()-INTERVAL 90 DAY`）→ 按 score 降序、score 同则 itemId 升序稳定排序 → Top-K。
> `GET /api/recommend/similar/{itemId}`：直读 `sim:item:{itemId}` Top-K。**冷启动 / 模型未就绪 / 邻居全空一律回退
> `hot:items:global` + `fallback=true`（C1）**；`5001/5002` 仅作结构化日志事件
> （`reco.cold_start` / `reco.model_not_ready` / `reco.empty_neighbors`），不进 `Result.code`。
> **越权**：path `userId` ≠ `X-User-Id` → `1001`（中间件已拦截缺头情况；handler 补做一致性比对）。
> 返回 schema：`{userId|itemId, topk, items: [{itemId, score}, ...], fallback}`，**不含商品详情**（前端按需走 Product 服务自取）。
> `topk` 范围 `[1, 50]`，默认 10（超界由全局 `RequestValidationError` handler 转 `code=1000`）。
> 性能：全 Redis 命中路径,200 并发下 P99 < 100ms（locust 脚本 `perf/locust-recommend.py`）。
> **未做**：T4.4 Gateway 直连 Python 路由（C2）；Prometheus 训练耗时 / 召回延时 metric；
> 消费 order 时主动 DEL `reco:purchased:user:{userId}` 以提升一致性（当前 best-effort 30min 内可能仍有已下单 item 出现）。

---

## 七、行为数据管道 ✅**已拍板：事件驱动 + 推荐自有库 `shopsphere_reco`**

**原矛盾**：`t_user_behavior` 属 User 库；architecture.md §2.4 原写 `feature_loader.py 从 MySQL 读行为`，
但 CLAUDE.md 铁律"禁止跨服务直接访问对方数据库"。Python 推荐服务直连 User 库 = 违规。

**拍板方案（事件驱动，推荐服务自持数据）**：

```
浏览/加购:  POST /api/user/behavior ──┐
下单:       order.created 事件 ───────┤──► MQ (behavior.* / order.created)
                                      │
                          ┌───────────▼──────────────┐
                          │ 消费者C (推荐服务侧消费者) │
                          └───────────┬──────────────┘
                                      ▼
                   推荐服务自有存储 behavior_event 表 / Redis
                   (库归 Recommendation，不碰 User 库)
```

要点：
1. User 服务仍写自己的 `t_user_behavior`（自用/审计），**同时**发 MQ 事件。
2. 推荐服务订阅 MQ，落到**自己的库**（建议 `shopsphere_reco`，与 architecture.md §1.1"每服务一库"一致）；`feature_loader.py` 只读自有库 + Redis。
3. 离线训练源从"读 User 库"改为"读推荐服务自有 `behavior_event`"。
4. ✅ 已同步修订 `docs/architecture.md`：§1.2 ADR 增加"推荐服务数据"决策行、§2.4 `feature_loader.py` 注释/核心算法/接口块/模块树。

> 备选方案 ❌**已否决**：User 暴露 `/internal/user/behavior/export?since=` 内部接口，推荐服务经 Gateway 拉取。否决理由：训练拉全量数据走 HTTP 成本高、耦合 User 服务可用性。**最终采用上面的事件驱动 + 推荐自有库方案。**

---

## 八、异步消息契约（RabbitMQ）

| Exchange (type) / Routing Key | 生产者 | 消费者(独立 queue) | Payload 关键字段 | 可靠性等级 |
|---|---|---|---|---|
| `shopsphere.order` (topic) / `order.created` | Order | `q.points`(积分·User) / `q.notify`(通知) / `q.reco.behavior`(行为·Reco) | `orderId, userId, items[], totalAmount, ts` | 🔴 强可靠 |
| `shopsphere.order` (topic) / `order.payment.timeout` | Order | `q.order.timeout`(Order 自消费) | `orderId, payExpireAt` | 🔴 强可靠（延迟） |
| `shopsphere.behavior` (topic) / `user.behavior` | User | `q.reco.behavior`(行为·Reco) | `userId, itemId, actionType(view/cart/order), ts` | 🟡 至少一次(可丢) |

- **每消费者独立 queue**（topic exchange 多 binding），互不影响；各自按 `orderId`/事件 ID 幂等去重。
- **C3 已拍板 · 本地消息表原子性**：`order.created` / `order.payment.timeout` 用 `t_local_message`，建于 **Order 库**，**INSERT 与订单创建在同一本地 DB 事务内**（即 Order 的 Seata 分支事务），全局事务提交后由「定时补偿任务 + `publisher-confirm` 回调」投递并标记。确保订单与消息原子，杜绝丢消息。
- **M4 已拍板 · 可靠性分级**：
  - 🔴 **强可靠（order.*）**：本地消息表 + confirm + 消费者幂等，不丢不重。
  - 🟡 **轻量（user.behavior）**：行为埋点量大且可容忍丢失，User 服务**直接发 MQ**（`mandatory` + confirm 异步回调仅记录失败，**不落本地消息表**），消费侧幂等去重。避免为海量埋点付重型一致性成本。
- `order.payment.timeout` 用 RabbitMQ 延迟（TTL+DLX 或 `rabbitmq-delayed-message-exchange` 插件），延迟 = 支付超时窗口（默认 30min，见 §6.3 / S4）。

> ✅ **T3.4 已落地**：`order.*` 强可靠链路打通 —— Order 侧 `LocalMessagePublisher`（`@Scheduled`
> 每 5s 扫 `t_local_message`，`FOR UPDATE SKIP LOCKED` + Redisson 锁防多实例重投）+ publisher-confirm
> 回调（ack→CONFIRMED；nack→指数退避重试，超 5 次转 FAILED 告警）；死信交换机 `shopsphere.order.dlx`
> (fanout) → `q.order.dlq`。User 侧 `PointsConsumer`（`q.points`，幂等键 `t_points_log.order_id`，
> 与积分累加同事务）+ `NotificationConsumer`（`q.notify`，Redis `notify:sent:{orderNo}` 幂等，本期仅日志）；
> 消费者手动 ack，失败有界重试 3 次后转 DLX。`order.created` payload 为共享 `OrderCreatedEvent`
> （`order-api`，较本表多 `orderNo` 字段，供通知用）。拓扑全量见 `docs/mq-topology.md`。
> **未做**：`order.payment.timeout` 真实 30min 延迟投递与超时自动取消消费者（`q.order.timeout` 已声明）；
> `q.order.dlq` 自动补偿。

---

## 九、开工前 Checklist

- [x] §3 透传 header 名最终确认（`X-User-Id` / `X-User-Name` / `X-Trace-Id`，三头固定）
- [x] §5 端口 / serviceName / 库名最终确认（Reco 库 = `shopsphere_reco`）
- [x] §7 行为数据管道方案拍板（事件驱动 + 推荐自有库 `shopsphere_reco`，备选已否决）
- [x] 据 §7 回写修订 `docs/architecture.md` §1.2 / §2.4
- [x] §6.4 推荐服务路径统一，回写 architecture.md §2.4 接口块与模块注释
- [x] §6.3 订单状态机定稿（SHIPPED 后不可取消）
- [x] **S1/S2/S3/S4** 库存+事务重设计：库存改 Seata **TCC**，Try/Confirm/Cancel + Redis 业务补偿 + 未支付超时（§4.3/§6.3）
- [x] **S5** 下单幂等：`X-Request-Id`（§6.3）
- [x] **C1** 推荐冷启动 `code=0 + fallback`（§6.4）
- [x] **C2** 服务间 Feign 走 Nacos 直连不经 Gateway；取消 Java 调推荐（§4.1/§4.2）
- [x] **C3** 本地消息表与订单创建同事务（§8）
- [x] **M1** Gateway 公开路径白名单（Nacos 载体，§3.1）
- [x] **M2** `/api/order/create` 出入参固化（§6.3）
- [x] **M4** 异步消息可靠性分级（§8）
- [x] **M5** 通用码 vs 服务段码边界（§2）
- [x] 据库存重设计回写 `docs/architecture.md` §1.2 / §2.2 / §2.3
- [x] **M3** `OffsetDateTime`(UTC) 口径已拍板，CLAUDE.md 时间条款已同步修订
- [ ] `shopsphere-common` 中落地 `Result<T>` / `ErrorCode` / `UserContext` 拦截器（对应 T0.1，属编码任务，非本次拍板范围）

---

## 十、增强项决策（🟢 非阻塞，定调备查）

| 项 | 决策 | 落地阶段 |
|---|---|---|
| 接口版本 | 路由前缀引入 `/api/v1/**`（Gateway 重写，下游不感知）；本契约现有路径视为 v1 | Phase 1（Gateway 路由时一并） |
| 链路追踪 | Micrometer Tracing + OTel，`traceId` 复用 `X-Trace-Id`；Python 用 opentelemetry-sdk 串联 | Phase 5 |
| JWT 密钥 | 非对称 RS256，私钥仅 User 签发、公钥经 Nacos 下发 Gateway 校验；公钥 `dataId=shopsphere-jwt-public-key.pem`（裸 PEM，Gateway 经 Nacos 监听热更新，**零重启**轮换）；claims 契约固定 `userId`(long) / `userName`(string)，T1.2↔T1.3 据此对齐 | Phase 1（T1.2/T1.3） |
| JWT 密钥轮换过渡 | **T1.2 为单公钥**：切换瞬间旧私钥签发的有效 token 立即失效，不达"零 token 失效"。多公钥并存（按 `kid` 新旧并行一个过渡窗口，或"先发公钥后切私钥"的有序流程）列为后续增强，由 T1.3（签发侧）协同落地 | Phase 1+ / 后续治理 |
| Nacos 敏感配置 | DB/MQ 口令用 Nacos 配置 + Jasypt 加密；密钥经环境变量注入，不入库 | Phase 0（T0.2） |
| 注册登录防爆破 | 登录失败计数（Redis），同账号 5 次/10min 锁定 30min；锁定返 `2002` + 自定义 message。注册加图形/滑块验证码 ⏳ 后续 | Phase 1（T1.3 ✅ 失败计数/锁定已落地于 `LoginAttemptService`；验证码后续） |
| `t_user_behavior` 量级 | 按月分表 + 仅保留近 90 天热数据，冷数据归档；推荐侧 `behavior_event` 同策略 | Phase 4 / 后续治理 |
