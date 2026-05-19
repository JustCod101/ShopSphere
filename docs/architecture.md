# ShopSphere 微服务电商平台 — 架构设计

---

## 一、架构总览

### 1.1 分层视图

```
┌────────────────────────────────────────────────────────────────┐
│  Client (Web / App)                                            │
└────────────────────────┬───────────────────────────────────────┘
                         │ HTTPS
┌────────────────────────▼───────────────────────────────────────┐
│  Spring Cloud Gateway （统一入口）                              │
│  • JWT 全局过滤器  • 动态路由  • CORS  • Sentinel 网关限流      │
└──────┬──────────┬──────────┬──────────┬────────────────────────┘
       │          │          │          │
   ┌───▼───┐  ┌───▼────┐ ┌───▼───┐  ┌──▼──────────────┐
   │ User  │  │Product │ │ Order │  │ Recommendation  │
   │ (Java)│  │ (Java) │ │(Java) │  │  (Python)       │
   └───┬───┘  └───┬────┘ └───┬───┘  └───┬─────────────┘
       │ Feign(Nacos 直连，服务间不经 Gateway)        │
       └──────────┴────┬─────┴──────────┘
   (推荐：前端经 Gateway 直连 Python /api/recommend/**，无 Java Feign，见 api-contracts §4.2/C2)
                       │
   ┌───────────────────▼────────────────────────────────────┐
   │  Nacos（注册中心 + 配置中心） • Sentinel Dashboard     │
   │  Seata Server（TC 协调器）                              │
   └────────────────────────────────────────────────────────┘
                       │
   ┌───────────────────▼────────────────────────────────────┐
   │  MySQL（每服务一库） • Redis • RabbitMQ                 │
   └────────────────────────────────────────────────────────┘
```

### 1.2 关键架构决策（ADR 摘要）

| 决策点 | 方案 | 理由 |
|---|---|---|
| 服务边界 | DDD 业务域拆分：User / Product / Order / Recommendation | 单一职责，独立部署演进 |
| 服务通信 | 同步：OpenFeign + LoadBalancer；异步：RabbitMQ | 强一致走 Feign，弱一致解耦走 MQ |
| 鉴权位置 | Gateway 统一 JWT 校验，下游通过 Header 透传用户上下文 | 下沉认证，业务服务零侵入 |
| 分布式事务 | 下单链路：库存分支用 Seata **TCC**（非 AT），订单本地写 + 本地消息表同事务；积分/通知用 MQ 最终一致 | AT 持热点行全局锁且回滚不了 Redis（评审 S1/S2），改 TCC 由业务二阶段补偿；详见 api-contracts §4.3 |
| 库存防超卖 | Redis Lua 原子预扣（TCC-Try）+ DB `t_product_stock` 条件更新 + `locked_stock` 预留态；Cancel 阶段业务显式回补 Redis | 高并发抗超卖，无 AT 全局锁；Redis/DB 一致由 TCC 三段保证 |
| 未支付超时 | 下单仅 Try 预留，支付成功才 Confirm 真扣；CREATED 投 RabbitMQ 延迟消息，30min 未付自动取消+释放 | 解决评审 S4 库存永久泄漏 |
| Python 服务接入 | 注册到 Nacos（nacos-sdk-python） + 经 Gateway 路由 | 与 Java 服务统一治理 |
| 推荐服务数据 | 自有库 `shopsphere_reco`，行为数据经 MQ 事件驱动同步，**不直连 User 库** | 遵守"每服务一库"与"禁止跨服务访问对方数据库"（详见 api-contracts.md §7） |
| 配置管理 | Nacos Config，按 profile 隔离（dev/test/prod） | 集中管理，热更新 |
| 数据迁移 | Flyway 每服务独立 migration | 版本化、可回溯 |

---

## 二、服务拆分与领域模型

### 2.1 User 用户服务

**职责**：注册、登录、用户信息、行为埋点上报

**核心表**：
- `t_user`（id, username, password_hash, email, phone, status, created_at）
- `t_user_profile`（user_id, nickname, avatar, gender, ...）
- `t_user_behavior`（id, user_id, item_id, action_type[view/cart/order], created_at）— 推荐服务消费的源数据

**关键接口**：
```
POST /api/user/register
POST /api/user/login        → 返回 JWT
GET  /api/user/me           → 当前用户信息
POST /api/user/behavior     → 行为埋点（内部调用）
GET  /internal/user/{id}    → Feign 内部接口
```

### 2.2 Product 商品服务

**职责**：商品 CRUD、类目、库存

**核心表**：
- `t_product`（id, name, category_id, price, stock, status, ...）
- `t_category`
- `t_product_stock`（product_id, stock, locked_stock, version）— 单独拆出便于事务
- `t_stock_tcc_log`（order_id, product_id, phase, state）— TCC 幂等/空回滚/防悬挂

**关键设计（库存 TCC，见 api-contracts §4.3）**：
- 热点商品（详情页）→ Redis Cache-Aside，TTL 随机 + 空值缓存 + 主动失效（防穿透/雪崩）
- 库存真实源 = `t_product_stock`，热点读经 Redis `stock:product:{id}`
- **库存分支为 Seata TCC**：
  - **Try**：Redis Lua 原子预扣 + DB `stock-=q, locked_stock+=q WHERE stock>=q`（不足 `3002`）
  - **Confirm**（支付成功）：DB `locked_stock-=q`（真实出库）
  - **Cancel**：DB `stock+=q, locked_stock-=q` 且 **显式回补 Redis `stock+=q`**
  - 幂等键 `(orderId, productId)`；空回滚/防悬挂由 `t_stock_tcc_log` 控制

**接口**：
```
GET  /api/product/{id}
GET  /api/product/list?categoryId=&page=
POST /internal/product/stock/try      → TCC-Try 预留（Seata 分支）
POST /internal/product/stock/confirm  → TCC-Confirm 真实出库
POST /internal/product/stock/cancel   → TCC-Cancel 释放 + 回补 Redis
```

### 2.3 Order 订单服务

**职责**：下单、查单、订单状态机、分布式事务发起方

**核心表**：
- `t_order`（id, user_id, total_amount, status, created_at）
- `t_order_item`（order_id, product_id, quantity, price）
- `t_local_message`（本地消息表，与建单同事务，见 api-contracts §8/C3）
- `t_order_request`（user_id, request_id, order_id）— 下单幂等（§6.3 S5）
- `undo_log`（Seata 必需；TCC 模式下 Order 本地分支仍可用 AT，故保留）

**下单链路（关键流程，TCC 重设计）**：

```
1. Gateway 校验 JWT → 转发到 Order Service（X-User-Id / X-Request-Id 透传）
2. 幂等校验：(userId, X-Request-Id) 命中 → 直接返回首次结果
3. @GlobalTransactional 开启全局事务
4. TCC-Try：Feign 调 Product.stock/try 预留库存（Nacos 直连，非经 Gateway）
5. Order 本地建单(status=CREATED) + INSERT t_local_message  ← 同一本地事务
6. 全局提交（Try 成功即提交；订单处于 CREATED/库存 locked）
7. 投递 RabbitMQ 延迟消息 order.payment.timeout（30min）
8. 支付成功 → /api/order/{id}/pay → TCC-Confirm（Product.stock/confirm）→ PAID
   超时/取消 → TCC-Cancel（Product.stock/cancel，释放并回补 Redis）→ CANCELLED
9. 定时任务扫 t_local_message 投递 order.created（publisher-confirm 后标记）
   → 消费者A：发放积分（User）  B：推送通知  C：行为日志（Reco 自有库）
```

**接口**：
```
POST /api/order/create        # 必填 X-Request-Id，仅 TCC-Try
POST /api/order/{id}/pay      # 支付确认 → TCC-Confirm → PAID
GET  /api/order/{id}
GET  /api/order/list
POST /api/order/{id}/cancel   # → TCC-Cancel 释放库存
```

**状态机**：`CREATED → PAID → SHIPPED → COMPLETED`；旁支 `CREATED/PAID → CANCELLED`（SHIPPED 后不可取消）。详见 api-contracts §6.3。

### 2.4 Recommendation 推荐服务（Python / FastAPI）

**职责**：基于行为数据的 Item-CF 推荐

**模块**：
```
recommendation/
├── app/
│   ├── main.py                # FastAPI 入口
│   ├── api/
│   │   ├── recommend.py       # /api/recommend/user|similar
│   │   └── health.py
│   ├── core/
│   │   ├── config.py          # Pydantic Settings
│   │   ├── nacos_client.py    # 注册 + 配置拉取
│   │   └── redis_client.py
│   ├── service/
│   │   ├── itemcf.py          # 离线相似度计算
│   │   ├── recall.py          # 在线召回 Top-N
│   │   └── feature_loader.py  # 从推荐自有库 shopsphere_reco.behavior_event / Redis 读行为
│   ├── consumer/
│   │   └── behavior_consumer.py # 订阅 MQ(user.behavior/order.created)，落自有库
│   └── tasks/
│       └── train_job.py       # 定时离线训练（APScheduler / cron）
├── tests/
├── requirements.txt
└── Dockerfile
```

**数据来源（见 api-contracts.md §7，已拍板）**：推荐服务**不直连 User 库**。User 写 `t_user_behavior` 的同时发 MQ 事件（`user.behavior` / `order.created`），推荐服务 `behavior_consumer.py` 消费并落入**自有库 `shopsphere_reco.behavior_event`**，遵守"每服务一库"与"禁止跨服务访问对方数据库"。

**核心算法流程**：
1. **离线**：每日凌晨从推荐自有库 `shopsphere_reco.behavior_event`（由 MQ 消费写入）拉取近 30 天数据 → 构建 user-item 矩阵 → 计算 item-item 余弦相似度 → 存入 Redis `sim:item:{id}` (zset, top 50)
2. **在线**：用户请求时 → 取该用户最近行为 N 个 item → 查相似 item 加权聚合 → 过滤已购 → 返回 Top-K

**接口**（以 api-contracts.md §6.4 为唯一标准，已拍板统一）：
```
GET  /api/recommend/user/{userId}?topk=10      # 个性化召回，冷启动回退热门
GET  /api/recommend/similar/{itemId}?topk=10   # i2i 相似商品
POST /internal/recommend/train                 # 手动触发训练（内部，不对外）
GET  /api/recommend/health                     # 健康检查 / Nacos 心跳
```

---

## 三、工程结构

```
shopsphere/
├── shopsphere-common/           # 公共：DTO、异常、JWT 工具、Result 包装
├── shopsphere-api/              # Feign 客户端接口集合
│   ├── user-api/
│   ├── product-api/
│   └── order-api/
├── shopsphere-gateway/
├── shopsphere-user/
│   ├── src/main/java/...
│   ├── src/main/resources/
│   │   ├── bootstrap.yml        # Nacos 配置
│   │   └── db/migration/        # Flyway
│   └── Dockerfile
├── shopsphere-product/
├── shopsphere-order/
├── shopsphere-recommendation/   # Python 服务
├── docker-compose.yml
├── docker-compose.infra.yml     # 仅基础设施
├── pom.xml                       # 父 POM
├── CLAUDE.md                     # 项目级 Claude Code 指令
└── .claude/
    ├── commands/                 # 自定义 slash commands
    └── agents/                   # 子 agent 配置
```

---

## 四、任务拆解与执行路线图

将整个项目拆为 **6 个 Phase / 21 个 Task**，每个 Task 都是 Claude Code 单次会话可完成的粒度（建议 1-3 小时上下文）。

### Phase 0：脚手架（1 天）

| # | 任务 | 验收 |
|---|---|---|
| T0.1 | 初始化 Maven 父项目 + common/api 模块 | `mvn clean install` 通过 |
| T0.2 | 编写 `docker-compose.infra.yml`（Nacos+MySQL+Redis+RabbitMQ+Seata） | `docker compose up` 全部健康 |
| T0.3 | 编写根 `CLAUDE.md` + `.claude/commands/` | Claude Code 能识别 slash 命令 |

**Claude Code 用法示例**：
```
> 阅读 docs/architecture.md，按照 T0.1 创建 Maven 父 POM 和 common、api 子模块骨架。
> common 模块需包含 Result<T>、BusinessException、JwtUtil、统一异常处理器。
```

### Phase 1：网关 + 用户服务（2-3 天）

| # | 任务 | 关键点 |
|---|---|---|
| T1.1 | Gateway 基础路由 + Nacos 接入 | 动态路由从 Nacos 配置读取 |
| T1.2 | Gateway JWT 全局过滤器 | 解析 token，user_id 写入请求 header |
| T1.3 | User 服务：注册/登录/查询 | BCrypt 密码、JWT 签发 |
| T1.4 | User 服务：行为埋点接口 | 异步写库（RabbitMQ） |
| T1.5 | Sentinel 接入 + 限流规则 | Dashboard 可视化 |

### Phase 2：商品服务（2 天）

| # | 任务 | 关键点 |
|---|---|---|
| T2.1 | Product CRUD + 类目 | MyBatis-Plus 分页 |
| T2.2 | 商品详情 Redis Cache | Cache-Aside + 防穿透/雪崩 |
| T2.3 | 库存预扣 Lua 脚本 | 原子性、key 设计 |
| T2.4 | Feign 内部扣减/回滚接口 | 幂等设计 |

### Phase 3：订单服务 + 分布式事务（3 天，最复杂）

| # | 任务 | 关键点 |
|---|---|---|
| T3.1 | Seata Server 部署 + Order `undo_log` + Product `t_stock_tcc_log` | nacos 注册模式；库存走 TCC |
| T3.2 | Order 下单主流程 + `@GlobalTransactional`（库存 TCC-Try）+ 下单幂等 | `X-Request-Id` 去重；本地消息表同事务 |
| T3.3 | 库存 TCC Confirm/Cancel + 空回滚/防悬挂 + 失败演练 | 主动抛异常验证 Cancel 回补 Redis |
| T3.4 | RabbitMQ 异步：积分、通知（强可靠）+ 行为埋点（轻量） | 可靠性分级（api-contracts §8/M4） |
| T3.5 | 订单查询、取消、状态机 + 未支付超时延迟消息 | 取消/超时调 stock/cancel 释放库存 |

### Phase 4：Python 推荐服务（2-3 天）

| # | 任务 | 关键点 |
|---|---|---|
| T4.1 | FastAPI 项目 + Nacos 注册 | 心跳保活 |
| T4.2 | ItemCF 离线训练脚本 | 余弦相似度 + 热门惩罚 |
| T4.3 | 在线召回接口 + Redis 缓存 | 冷启动兜底（热门 Top-N） |
| T4.4 | 前端经 Gateway 直连推荐服务（`/api/recommend/**`，带用户 JWT） | Gateway 路由 + 白名单；**取消 Java→Py Feign**（见 api-contracts C2） |

### Phase 5：联调、部署、压测（2 天）

| # | 任务 | 验收 |
|---|---|---|
| T5.1 | 全链路 docker-compose 编排 | 一键起所有服务 |
| T5.2 | 端到端测试：注册→浏览→下单→推荐 | E2E 用例通过 |
| T5.3 | JMeter 下单压测 + 调优 | 验证无超卖 |
| T5.4 | README + 架构图 + ADR 文档 | 可交付 |