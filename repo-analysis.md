# ShopSphere 仓库分析（面试版）

> 说明：本文只基于仓库内代码、配置、迁移脚本和文档归纳；无法由仓库证据确认的内容标为“待确认”。引用格式以文件路径为主，必要时补充行号。

## 1. 项目目标：这个项目解决什么问题

ShopSphere 是一个用于展示微服务电商核心能力的项目，目标不是单纯做商品展示，而是覆盖电商交易链路中的用户、商品、订单、库存一致性、消息可靠性、推荐系统、网关鉴权、限流和可观测能力。根 README 明确将其定位为“微服务电商平台”，并强调“1000 并发抗超卖 + TCC 一致性 + 推荐冷启动 fallback”验收门（`README.md:1-19`）。

项目的业务域被拆成 Gateway、User、Product、Order、Recommendation 五个服务：Gateway 承接统一入口和鉴权，User 管注册登录与行为埋点，Product 管商品与库存，Order 管交易与 TCC 发起，Recommendation 用 Python/FastAPI 做推荐召回（`README.md:106-117`，`docs/architecture.md:37-51`，`docs/adr/ADR-001-service-boundary.md:21-28`）。

面试表达可以概括为：这是一个“电商交易一致性 + 推荐闭环”的微服务项目，重点解决高并发库存防超卖、跨服务事务、异步消息可靠投递、用户行为驱动推荐、网关统一鉴权和基础设施一键部署这些问题（`README.md:13-19`，`docs/architecture.md:84-127`）。

## 2. 技术栈：前端、后端、数据库、中间件、部署方式

### 前端

仓库内未发现独立前端工程。未发现 `package.json`、Vite/Next/Angular 配置，也未发现常见 `.tsx/.jsx/.vue/.html` 前端源码文件；架构文档只把客户端抽象为 `Client (Web / App)`（`docs/architecture.md:9-17`）。因此前端技术栈应标记为：待确认 / 仓库未提供。

### 后端

Java 后端使用 Spring Boot 3.2.5、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0、JDK 17、MyBatis-Plus 3.5.7、Seata 1.8.0、JJWT 0.12.5（`README.md:59-75`，`pom.xml:23-38`，`pom.xml:40-126`）。

推荐服务使用 Python 3.11、FastAPI、Uvicorn、APScheduler，并在代码中使用 pandas/scipy/numpy/sklearn 做 ItemCF 训练（`README.md:59-75`，`shopsphere-recommendation/app/main.py:16-29`，`shopsphere-recommendation/app/service/itemcf.py:1-14`）。

### 数据库

业务数据按服务独立建库：`shopsphere_user`、`shopsphere_product`、`shopsphere_order`、`shopsphere_reco`，契约文档列出了服务名、端口、路由前缀和库名（`docs/api-contracts.md:175-187`）。Java 服务使用 Flyway 迁移，推荐服务使用 Alembic 迁移（`docs/architecture.md:49-50`，`shopsphere-recommendation/alembic/versions/20260523_0001_init_behavior_event_and_train_log.py:20-67`）。

### 中间件

中间件包括 Nacos 注册/配置中心、Seata TC、Sentinel、RabbitMQ、Redis、MySQL、Prometheus、Grafana。README 和 Compose 文件都列明了这些组件及版本/用途（`README.md:59-75`，`docker-compose.yml:24-208`，`docker-compose.yml:449-497`）。

Redis 用于库存计数器、商品详情缓存、登录防爆破、推荐召回索引/缓存、分布式锁等场景（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java:17-35`，`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:24-44`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:261-301`，`shopsphere-recommendation/app/service/recall.py:43-59`）。

RabbitMQ 用于行为事件、订单事件、积分/通知消费、推荐行为入库、订单超时取消；MQ 拓扑在文档中列出 exchange、queue、binding（`docs/mq-topology.md:9-48`）。

### 部署方式

本地/CI 主要通过 Docker Compose 启动完整栈，`docker-compose.yml` 含基础设施、4 个 Java 服务、1 个 Python 推荐服务、监控组件，并用 `java` / `python` / `monitoring` profile 控制启动范围（`README.md:78-102`，`docker-compose.yml:1-12`，`docker-compose.yml:216-440`，`docs/deployment.md:1-24`）。

生产部署方式未给出 Kubernetes/云厂商落地配置，只在部署文档中列出生产差异，例如仅暴露 gateway、Nacos 集群鉴权、Seata HA、数据卷改云盘/PVC 等。因此生产部署平台为待确认（`docs/deployment.md:135-150`）。

## 3. 入口文件：应用启动入口、路由入口、核心配置

Java 服务启动入口：

- Gateway：`GatewayApplication.main`，说明统一入口只路由 `/api/**`，拒绝 `/internal/**`（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/GatewayApplication.java:6-18`）。
- User：`UserApplication.main`，启用 Nacos discovery 和 MyBatis Mapper 扫描（`shopsphere-user/src/main/java/com/shopsphere/user/UserApplication.java:16-23`）。
- Product：`ProductApplication.main`，启用 Nacos discovery、定时任务、Mapper 扫描（`shopsphere-product/src/main/java/com/shopsphere/product/ProductApplication.java:17-25`）。
- Order：`OrderApplication.main`，启用 Nacos discovery、Feign、定时任务、Mapper 扫描，Feign 只扫描 product-api（`shopsphere-order/src/main/java/com/shopsphere/order/OrderApplication.java:19-28`）。
- Recommendation：FastAPI 入口为 `app = create_app()`，lifespan 中加载 Nacos 配置、初始化 DB/Redis/MQ 消费者、APScheduler 训练任务、注册 Nacos（`shopsphere-recommendation/app/main.py:81-172`）。

路由入口：

- Gateway 路由配置不写在本地 `application.yml`，而由 Nacos `shopsphere-gateway.yaml` 下发，包含 `/api/user/**`、`/api/product/**`、`/api/order/**`、`/api/recommend/**` 和 `/api/v1/**` rewrite 路由（`shopsphere-gateway/src/main/resources/bootstrap.yml:13-20`，`docs/nacos/shopsphere-gateway.yaml:6-45`）。
- Gateway 鉴权入口是 `JwtAuthFilter`，白名单外强制 RS256 JWT 验签，并注入 `X-User-Id` / `X-User-Name`（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java:29-39`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java:73-105`）。
- Gateway 安全前置入口还包括 `RequestLogFilter`，它剥离外部伪造的用户/trace 头并重新生成 `X-Trace-Id`（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java:24-31`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java:42-55`），以及 `InternalAccessRejectFilter` 拒绝 `/internal/**`（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java:14-32`）。
- User 对外路由集中在 `UserController`：注册、登录、me、行为埋点（`shopsphere-user/src/main/java/com/shopsphere/user/controller/UserController.java:27-62`）。
- Product 对外路由集中在 `ProductController`：商品详情、列表、类目树（`shopsphere-product/src/main/java/com/shopsphere/product/controller/ProductController.java:20-51`）。
- Order 对外路由集中在 `OrderController`：创建、支付、详情、列表、取消（`shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java:26-97`）。
- Recommendation 对外路由集中在 `recommend.py`：用户推荐、相似商品、内部训练触发（`shopsphere-recommendation/app/api/recommend.py:1-8`，`shopsphere-recommendation/app/api/recommend.py:22-73`）。

核心配置：

- Java 服务通过 `bootstrap.yml` 接入 Nacos discovery/config，且机密配置由 Nacos profile 配置和 Jasypt 环境变量提供（`shopsphere-user/src/main/resources/bootstrap.yml:4-28`，`shopsphere-product/src/main/resources/bootstrap.yml:4-27`，`shopsphere-order/src/main/resources/bootstrap.yml:4-26`）。
- Seata 客户端配置在 User/Product/Order 的 `application.yml`，Order/User 使用 AT 数据源代理，Product 关闭自动代理并走业务 TCC（`shopsphere-user/src/main/resources/application.yml:37-62`，`shopsphere-product/src/main/resources/application.yml:19-45`，`shopsphere-order/src/main/resources/application.yml:14-38`）。
- Sentinel 配置在 Gateway/User，规则从 Nacos 加载，Gateway 还定义 API 分组和限流响应体（`shopsphere-gateway/src/main/resources/application.yml:9-26`，`shopsphere-user/src/main/resources/application.yml:18-35`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/config/SentinelGatewayConfig.java:32-49`）。
- Order 共享配置包括支付超时、幂等记录清理、outbox 扫描周期/批量/重试/锁参数（`docs/nacos/shopsphere-order.yaml:5-24`）。
- Recommendation 共享配置包括默认 Top-K、相似 Top-N、训练 cron（`docs/nacos/shopsphere-recommendation.yaml:6-9`）。

## 4. 目录结构：每个目录的职责

- `shopsphere-common/`：Java 公共能力，包括统一响应 `Result`、错误码、全局异常、JWT 工具、用户上下文拦截器、Header 常量（`README.md:146-165`，`shopsphere-common/src/main/java/com/shopsphere/common/result/Result.java:13-67`，`shopsphere-common/src/main/java/com/shopsphere/common/context/UserContextInterceptor.java:16-44`）。
- `shopsphere-api/`：Feign 契约模块，包含 user/product/order 子模块，ProductFeignClient 定义商品详情和库存 TCC 内部接口，UserFeignClient 定义内部用户查询接口，order-api 共享 `order.created` 事件 payload（`README.md:106-117`，`shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java:13-40`，`shopsphere-api/user-api/src/main/java/com/shopsphere/api/user/UserFeignClient.java:9-18`，`shopsphere-api/order-api/src/main/java/com/shopsphere/api/order/event/OrderCreatedEvent.java:13-38`）。
- `shopsphere-gateway/`：统一入口、JWT 鉴权、内部接口拒绝、请求日志、Sentinel 网关限流、公钥加载（`README.md:106-117`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java:29-39`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java:14-32`）。
- `shopsphere-user/`：注册、登录、当前用户、行为埋点、积分/通知 MQ 消费、登录防爆破、JWT 签发（`README.md:106-117`，`shopsphere-user/src/main/java/com/shopsphere/user/controller/UserController.java:21-62`，`shopsphere-user/src/main/java/com/shopsphere/user/service/UserServiceImpl.java:24-140`，`shopsphere-user/src/main/resources/db/migration/V20260522_1000__add_points.sql:1-22`）。
- `shopsphere-product/`：商品/类目查询、商品详情缓存、库存 Redis Lua、库存 TCC Try/Confirm/Cancel、库存预热/对账（`README.md:106-117`，`shopsphere-product/src/main/java/com/shopsphere/product/controller/ProductController.java:20-51`，`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:24-44`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:21-38`）。
- `shopsphere-order/`：下单、支付、取消、订单查询、订单状态机、Seata 全局事务发起、本地消息表 outbox、超时取消消费（`README.md:106-117`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:54-63`，`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:27-42`，`shopsphere-order/src/main/java/com/shopsphere/order/messaging/OrderTimeoutConsumer.java:18-28`）。
- `shopsphere-recommendation/`：Python 推荐服务，包含 FastAPI API、Nacos/DB/Redis 配置、MQ 行为消费、ItemCF 训练、在线召回、训练任务（`README.md:106-117`，`docs/architecture.md:140-167`，`shopsphere-recommendation/app/main.py:81-172`）。
- `shopsphere-e2e-test/`：端到端测试，不进入主 reactor，覆盖注册登录、商品浏览、行为链路、下单幂等、支付确认、取消、超时、越权、推荐冷热启动、网关安全（`shopsphere-e2e-test/README.md:3-29`）。
- `docs/`：架构、API 契约、部署、排障、MQ 拓扑、ADR、Nacos 配置备份等文档（`README.md:121-142`）。
- `docs/nacos/`：Nacos 配置版本化备份，例如 gateway 路由/白名单、各业务服务共享配置、推荐模型参数（`docs/nacos/shopsphere-gateway.yaml:1-63`，`docs/nacos/shopsphere-order.yaml:1-24`）。
- `scripts/`：初始化 MySQL/Nacos/Seata、等待栈健康、推荐 E2E、压测数据等脚本，根 README 也将其定义为 nacos-config-push / wait-stack-healthy 等运维脚本目录（`README.md:146-165`，`docker-compose.yml:36-40`，`docker-compose.yml:174-190`）。
- `perf/`：JMeter 压测计划和验证脚本，用于验证 1000 并发、500 库存、不超卖、账平、Redis/DB 一致性、QPS/P99（`perf/README.md:1-17`，`perf/README.md:68-82`）。
- `monitoring/`：Prometheus 和 Grafana 配置；Prometheus 抓取 user/product/order 的 actuator 指标（`monitoring/prometheus/prometheus.yml:1-27`）。
- `rabbitmq/`：自定义 RabbitMQ 镜像和 delayed-message 插件，Compose 使用 `build: ./rabbitmq`（`docker-compose.yml:105-127`）。
- `seata/`：Seata Server 配置，Compose 挂载到 Seata 容器（`docker-compose.yml:151-162`）。
- `data/`、`logs/`：本地运行时挂载目录，用于基础设施持久化和服务日志；这些是部署时产生/挂载的运行目录，不是核心源码目录（`docs/deployment.md:27-42`）。

## 5. 核心模块：按业务能力划分

### 统一入口与安全上下文

Gateway 负责剥离外部传入的 `X-User-*` / `X-Trace-Id`，重新生成 traceId，再按白名单决定是否 JWT 验签；验签通过后向下游注入用户上下文（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java:42-55`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java:68-105`）。下游 Java 服务通过 common 的 `UserContextInterceptor` 从 header 还原用户上下文，非 `@PublicApi` 且无用户 ID 时返回未认证（`shopsphere-common/src/main/java/com/shopsphere/common/context/UserContextInterceptor.java:16-44`）。

### 用户与行为埋点

User 服务负责注册、登录、查询当前用户和行为埋点。注册写 `t_user` 和 `t_user_profile`，登录校验 BCrypt 密码后由 `JwtSigner` 签发 token，行为埋点先写 `t_user_behavior`，再在事务提交后发布行为事件到 MQ（`shopsphere-user/src/main/java/com/shopsphere/user/service/UserServiceImpl.java:38-102`，`shopsphere-user/src/main/java/com/shopsphere/user/service/BehaviorServiceImpl.java:31-58`，`shopsphere-user/src/main/java/com/shopsphere/user/messaging/BehaviorEventPublisher.java:12-37`）。

### 商品缓存与库存

Product 服务对外提供商品详情、列表和类目树；商品详情缓存使用 Cache-Aside、空值缓存、Redisson 互斥锁、TTL 抖动和延迟双删，库存单独用 `stock:product:{id}` 常驻计数器维护（`shopsphere-product/src/main/java/com/shopsphere/product/controller/ProductController.java:20-51`，`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:24-44`，`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:185-224`）。

库存 TCC 使用业务显式 Try/Confirm/Cancel，不是 Seata `@TwoPhaseBusinessAction`。Try 先 Redis Lua 原子预扣，再 DB 条件更新 `stock-=q, locked_stock+=q` 并写 TCC 日志；Confirm 扣 `locked_stock`；Cancel 回补 DB 和 Redis，并通过 `t_stock_tcc_log` 做幂等、空回滚和防悬挂（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java:17-35`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:21-38`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:56-158`）。

### 订单交易与可靠消息

Order 服务是交易链路和全局事务发起方。创建订单会合并商品、查商品详情、服务端计价、写订单/明细/outbox/幂等记录，再调用 Product 库存 Try；支付会 `CREATED -> PAID` 并调用库存 Confirm；取消会串行化加 Redis 锁、校验状态、更新取消状态并调用库存 Cancel（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:98-183`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:185-210`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:261-301`）。

Order 的本地消息表 outbox 负责把 `order.created` 和 `order.payment.timeout` 从 DB 中继到 RabbitMQ，并通过 publisher confirm 做确认、退避重试和失败标记（`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:27-42`，`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:63-162`）。超时取消由 `OrderTimeoutConsumer` 消费 `q.order.timeout` 并调用 `OrderService.cancel(orderId, null, "system-timeout")`（`shopsphere-order/src/main/java/com/shopsphere/order/messaging/OrderTimeoutConsumer.java:18-65`）。

### 推荐系统

Recommendation 服务消费 `user.behavior` 和 `order.created` 两类消息，把行为事件归一化落入自有库 `behavior_event`，并缓存每个用户最近行为到 Redis（`shopsphere-recommendation/app/consumer/behavior_consumer.py:1-10`，`shopsphere-recommendation/app/consumer/behavior_consumer.py:111-143`，`shopsphere-recommendation/app/consumer/behavior_consumer.py:210-250`）。离线训练从自有库加载行为，构建 ItemCF 相似度，写入 `sim:item:{id}`，并维护 `hot:items:global` 作为冷启动兜底（`shopsphere-recommendation/app/service/itemcf.py:1-14`，`shopsphere-recommendation/app/service/itemcf.py:206-257`）。在线召回优先读用户最近行为和相似 item，模型未就绪、冷启动或邻居为空时回退热门商品（`shopsphere-recommendation/app/service/recall.py:1-10`，`shopsphere-recommendation/app/service/recall.py:62-134`）。

### 端到端与压测验证

E2E 覆盖 A-K 链路，包括注册登录、商品缓存、行为 MQ、下单幂等、TCC Confirm/Cancel、超时取消、推荐冷热启动、网关安全（`shopsphere-e2e-test/README.md:7-21`）。压测脚本目标是 1000 并发对 500 库存，验证不超卖、账平、Redis/DB 一致、TCC 各阶段计数、QPS/P99 等（`perf/README.md:1-4`，`perf/README.md:68-82`）。

## 6. 核心链路：3-5 条适合面试讲解的运行流程

### 链路 1：登录鉴权与用户上下文透传

1. 用户调用 `/api/user/login`，User 服务校验用户名密码，成功后用私钥签发 RS256 JWT（`shopsphere-user/src/main/java/com/shopsphere/user/controller/UserController.java:41-45`，`shopsphere-user/src/main/java/com/shopsphere/user/service/UserServiceImpl.java:78-102`，`shopsphere-common/src/main/java/com/shopsphere/common/util/JwtUtil.java:20-46`）。
2. 后续请求进入 Gateway，`RequestLogFilter` 剥离外部伪造头并生成 `X-Trace-Id`（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java:42-55`）。
3. `JwtAuthFilter` 对非白名单路径验签，成功后注入 `X-User-Id` 和 `X-User-Name`（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java:68-105`）。
4. 下游服务由 `UserContextInterceptor` 还原 `UserContext`，业务代码通过 `UserContextHolder` 获取用户 ID，而不是直接读 header（`shopsphere-common/src/main/java/com/shopsphere/common/context/UserContextInterceptor.java:31-44`，`shopsphere-user/src/main/java/com/shopsphere/user/controller/UserController.java:47-60`）。

面试亮点：鉴权集中在网关，下游只消费可信上下文；网关剥离外部 header 防伪造；Result 统一带 traceId（`docs/api-contracts.md:78-93`，`shopsphere-common/src/main/java/com/shopsphere/common/result/Result.java:13-67`）。

### 链路 2：下单、库存 Try 与本地消息表

1. 客户端调用 `/api/order/create`，必须带 `X-Request-Id`；Order 先查 `(userId, requestId)` 幂等记录，命中则返回原订单（`shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java:38-55`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:84-96`）。
2. `createOrder` 开启 `@GlobalTransactional`，合并商品、查 Product Feign、服务端计价、生成订单和明细（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:98-156`）。
3. Order 同一本地事务写入订单、明细、两个 outbox 消息和幂等记录（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:157-172`，`shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql:32-55`）。
4. Order 调 Product `stockTry`，Product 执行 Redis Lua 预扣、DB 条件扣减、TCC 日志写入；失败则抛错回滚 Order 本地分支（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:174-180`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:56-105`）。
5. outbox 中继定时扫描 PENDING 消息，投递 `order.created` 和 `order.payment.timeout` 到 RabbitMQ（`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:63-110`）。

面试亮点：下单不是直接扣死库存，而是 Try 预留；幂等表防重复下单；本地消息表保证订单和消息写入同事务（`docs/architecture.md:113-127`）。

### 链路 3：支付确认、取消与 TCC 状态变化

1. 支付接口 `/api/order/{id}/pay` 校验订单归属和状态，只允许 `CREATED -> PAID`，本地标记已支付后调用 Product `stockConfirm`（`shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java:57-65`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:185-210`）。
2. Product Confirm 遍历该订单 Try 日志，若未 Confirm，则执行 `locked_stock-=q` 并写 Confirm 日志，Redis 不再重复扣减（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:107-125`）。
3. 取消接口或超时取消会走 `OrderService.cancel`，Redis 锁串行化同一订单取消，状态机校验后更新为 CANCELLED，再调用 Product `stockCancel`（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:261-301`）。
4. Product Cancel 根据是否已经 Confirm 决定释放预留或逆向补偿，并显式回补 Redis；没有 Try 记录时写订单级空回滚标记，防止后续 Try 悬挂（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:127-158`）。

面试亮点：库存状态拆成可售 `stock` 和预留 `locked_stock`，支付 Confirm 才真实出库，Cancel 支持空回滚、防悬挂和 Redis 回补（`docs/api-contracts.md:141-172`）。

### 链路 4：行为事件到推荐召回闭环

1. 用户行为接口 `/api/user/behavior` 先同步写 `t_user_behavior`，事务提交后异步发送 `user.behavior`（`shopsphere-user/src/main/java/com/shopsphere/user/service/BehaviorServiceImpl.java:31-58`，`shopsphere-user/src/main/java/com/shopsphere/user/messaging/BehaviorEventPublisher.java:27-37`）。
2. Order 的 `order.created` 也通过 outbox 投递，MQ 拓扑把 `user.behavior` 和 `order.created` 都绑定到推荐队列 `q.reco.behavior`（`docs/mq-topology.md:32-43`）。
3. Recommendation 的 `BehaviorConsumer` 消费两类事件，`order.created` 会展开为每个商品一条 `order` 行，并用 event_id 幂等落库（`shopsphere-recommendation/app/consumer/behavior_consumer.py:64-97`，`shopsphere-recommendation/app/consumer/behavior_consumer.py:210-232`）。
4. 训练任务用 APScheduler + Redis NX 锁触发 ItemCF 训练，写相似度和热门商品到 Redis（`shopsphere-recommendation/app/tasks/train_job.py:1-13`，`shopsphere-recommendation/app/tasks/train_job.py:60-100`，`shopsphere-recommendation/app/service/itemcf.py:1-14`）。
5. 在线推荐先取用户最近行为，再查相似商品聚合，过滤已购；无模型/无行为/无邻居时 fallback 热门商品（`shopsphere-recommendation/app/service/recall.py:62-134`）。

面试亮点：推荐服务有自有库，不跨服务读 User 库；行为数据通过 MQ 同步，兼顾服务边界和最终一致（`docs/architecture.md:47-49`，`docs/architecture.md:169-174`）。

### 链路 5：超时未支付自动取消

1. 创建订单时 Order 同时写入 `order.payment.timeout` 本地消息，`payExpireAt` 根据 Nacos 配置的 30 分钟或测试 TTL 计算（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:138-162`，`docs/nacos/shopsphere-order.yaml:5-14`）。
2. outbox 把 timeout 消息投到 RabbitMQ，先进入 TTL 等待队列 `q.order.timeout.wait`，到期后死信到 `q.order.timeout`（`docs/mq-topology.md:24-40`，`docs/mq-topology.md:136-140`）。
3. `OrderTimeoutConsumer` 消费 timeout 事件，调用 `orderService.cancel(orderId, null, "system-timeout")`，系统超时取消只取消仍为 `CREATED` 的订单（`shopsphere-order/src/main/java/com/shopsphere/order/messaging/OrderTimeoutConsumer.java:18-65`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:279-285`）。

面试亮点：用原生 TTL + DLX 实现延迟取消，不依赖业务线程睡眠；取消链路复用库存 Cancel 幂等回补（`docs/mq-topology.md:136-147`）。

## 7. 数据模型：主要实体、关系、状态变化

### User 域

主要实体包括 `t_user`、`t_user_profile`、`t_user_behavior`、`t_user_points`、`t_points_log`。`t_user_profile.user_id` 外键关联 `t_user.id`；`t_user_behavior.event_id` 唯一，用于行为事件幂等；`t_points_log.order_id` 唯一，用于订单积分消费幂等（`shopsphere-user/src/main/resources/db/migration/V20260520_1000__init_user.sql:5-30`，`shopsphere-user/src/main/resources/db/migration/V20260520_1001__add_user_behavior.sql:5-17`，`shopsphere-user/src/main/resources/db/migration/V20260522_1000__add_points.sql:5-22`）。

用户状态当前表字段为 `status`，迁移注释显示 1=正常、0=禁用；仓库内未看到完整用户状态机，更多状态变化为待确认（`shopsphere-user/src/main/resources/db/migration/V20260520_1000__init_user.sql:5-18`）。

### Product / Stock 域

主要实体包括 `t_category`、`t_product`、`t_product_stock`、`t_stock_tcc_log`。`t_product.category_id` 指向类目 ID；`t_product_stock.product_id` 与商品 ID 同源；`t_stock_tcc_log` 通过 `(order_id, product_id, phase)` 唯一键保证每个 TCC 阶段幂等（`shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql:5-41`，`shopsphere-product/src/main/resources/db/migration/V20260521_1001__add_stock_tcc_log.sql:1-17`）。

库存状态变化是面试重点：Try 阶段 `stock` 减少、`locked_stock` 增加；Confirm 阶段 `locked_stock` 减少；Cancel 阶段可售库存回补并减少预留，已 Confirm 的取消走逆向补偿（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:56-158`，`docs/api-contracts.md:147-172`）。注意：迁移脚本中 `t_product_stock.stock` 注释写“真实总量”，但架构/代码后续明确语义为“可售库存池，真实总量 = stock + locked_stock”；这属于面试风险点，需要主动解释版本演进（`shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql:33-41`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:29-30`）。

### Order 域

主要实体包括 `t_order`、`t_order_item`、`t_order_request`、`t_local_message`。`t_order_item.order_id` 关联订单；`t_order_request` 用 `(user_id, request_id)` 唯一键实现重复下单幂等；`t_local_message` 存储待投递消息及状态（`shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql:5-55`，`shopsphere-order/src/main/resources/db/migration/V20260522_1000__add_order_address.sql:1-4`）。

订单状态字段 `status` 定义为 0=CREATED、1=PAID、2=SHIPPED、3=COMPLETED、4=CANCELLED；代码中支付只允许 `CREATED -> PAID`，取消允许人工取消 `CREATED/PAID`，系统超时只取消仍为 `CREATED` 的订单（`shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql:5-19`，`docs/api-contracts.md:240-245`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:185-210`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:261-301`）。

`t_local_message.status` 为 0=PENDING、1=SENT、2=CONFIRMED、3=FAILED；outbox 中继把 PENDING 发往 MQ，confirm ack 标 CONFIRMED，nack 退避重试或 FAILED（`shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql:42-55`，`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:120-162`）。

### Recommendation 域

推荐服务自有库包含 `behavior_event` 和 `t_train_log`。`behavior_event.event_id` 唯一，按 `user_id/ts`、`item_id/ts` 建索引；`t_train_log` 记录训练开始/结束、用户数、商品数、行为数、状态和错误（`shopsphere-recommendation/alembic/versions/20260523_0001_init_behavior_event_and_train_log.py:20-67`）。

训练状态变化是 `RUNNING -> SUCCESS / FAILED`；代码通过 Redis `SET NX EX` 控制并发训练，插入 RUNNING 后异步执行训练，成功或失败后更新日志并释放锁（`shopsphere-recommendation/app/tasks/train_job.py:1-13`，`shopsphere-recommendation/app/tasks/train_job.py:60-181`）。

## 8. 外部依赖：第三方 API、云服务、队列、缓存等

第三方 API：仓库内未发现调用外部支付、短信、对象存储、地图、第三方推荐 API 等代码；商品种子数据的 `main_image` 使用 `https://cdn.shop/...` 示例 URL，但未见实际云存储 SDK 或上传逻辑，因此外部商业 API 为待确认（`shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql:54-74`）。

注册配置中心：Nacos 用于 Java 服务 discovery/config，也用于 Python 推荐服务配置加载和注册心跳（`shopsphere-gateway/src/main/resources/bootstrap.yml:7-20`，`shopsphere-recommendation/app/main.py:68-78`，`shopsphere-recommendation/app/main.py:111-118`）。

分布式事务：Seata 用作 TC 协调器，User/Product/Order 配置 Seata 客户端；Order 创建/支付/取消使用 `@GlobalTransactional`（`docker-compose.yml:151-172`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:98-100`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:185-187`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:261-263`）。

队列：RabbitMQ 承载行为、订单、积分/通知、推荐消费、支付超时事件；自定义镜像带 delayed-message 插件，但超时取消当前文档说明使用 TTL+DLX（`docker-compose.yml:105-127`，`docs/mq-topology.md:9-48`，`docs/mq-topology.md:136-140`）。

缓存/锁：Redis 用于库存计数器、推荐索引、登录防爆破、分布式锁和缓存；Product/Order 使用 Redisson 锁，Recommendation 使用 Redis 锁与 ZSET（`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:109-163`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:261-301`，`shopsphere-recommendation/app/tasks/train_job.py:60-88`，`shopsphere-recommendation/app/service/recall.py:51-134`）。

限流/熔断：Sentinel Dashboard 和 Nacos 规则用于网关和 User 侧限流；Gateway block handler 返回统一 `Result(1003)`（`shopsphere-gateway/src/main/resources/application.yml:9-26`，`shopsphere-user/src/main/resources/application.yml:18-35`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/config/SentinelGatewayConfig.java:93-110`）。

可观测：Prometheus/Grafana 通过 Compose 启动，Prometheus 抓取 user/product/order 的 `/actuator/prometheus`（`docker-compose.yml:449-497`，`monitoring/prometheus/prometheus.yml:1-27`）。

## 9. 架构亮点：性能、扩展性、可靠性、安全性方面的设计

性能亮点：

- 商品详情使用 Cache-Aside，包含空值缓存防穿透、Redisson 互斥锁防击穿、TTL 随机抖动防雪崩，并把库存计数器从商品静态详情缓存中拆出（`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:24-44`，`shopsphere-product/src/main/java/com/shopsphere/product/service/ProductCacheService.java:185-224`）。
- 库存预扣使用 Redis Lua，避免 Java 端 GET/SET 非原子操作；库存 key 常驻无 TTL，启动预热后由 Lua 维护（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java:17-35`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java:83-109`）。
- 推荐在线召回使用 Redis ZSET 存 item 相似度、热门商品和用户行为缓存，请求时 pipeline 查多个相似 item 并聚合（`shopsphere-recommendation/app/service/itemcf.py:206-245`，`shopsphere-recommendation/app/service/recall.py:62-134`）。
- 压测目标和验收指标明确：1000 并发、500 库存、不超卖、P99 < 500ms、QPS ≥ 200（`perf/README.md:1-4`，`perf/README.md:68-82`）。

扩展性亮点：

- 按业务子域拆分服务，推荐服务用 Python 独立数据库和 MQ 接入，不污染 Java 栈（`docs/adr/ADR-001-service-boundary.md:21-38`）。
- Feign 契约集中在 `shopsphere-api`，内部接口走 Nacos 服务发现直连，不经 Gateway（`docs/api-contracts.md:119-139`，`shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java:13-40`）。
- 配置集中在 Nacos，路由/白名单/业务参数可按 profile 管理，Compose 启动时会推送 `docs/nacos` 配置（`shopsphere-gateway/src/main/resources/bootstrap.yml:13-20`，`docker-compose.yml:174-190`）。

可靠性亮点：

- 下单链路用 `@GlobalTransactional` + 业务 TCC，Product 通过 `t_stock_tcc_log` 处理幂等、空回滚、防悬挂，Cancel 显式回补 Redis（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:98-180`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:21-38`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:127-158`）。
- 订单事件用本地消息表 outbox，生产端强可靠；消费者有手动 ack、幂等和 DLX 设计（`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:27-42`，`docs/mq-topology.md:78-112`）。
- 推荐冷启动和模型未就绪不返回 5xx，而是 fallback 热门商品（`shopsphere-recommendation/app/api/recommend.py:1-8`，`shopsphere-recommendation/app/service/recall.py:71-91`）。
- E2E 覆盖交易、推荐和网关安全主链路，压测脚本覆盖一致性核验（`shopsphere-e2e-test/README.md:7-21`，`perf/README.md:68-82`）。

安全性亮点：

- JWT 私钥只由 User 签发，Gateway 用公钥验签；业务服务不校验 JWT，只信任网关透传头（`shopsphere-common/src/main/java/com/shopsphere/common/util/JwtUtil.java:20-46`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java:73-105`，`shopsphere-common/src/main/java/com/shopsphere/common/context/UserContextInterceptor.java:16-44`）。
- Gateway 主动剥离外部传入的用户上下文和 trace 头，防止 header 伪造（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java:24-31`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java:45-55`）。
- `/internal/**` 被 Gateway 路由前拒绝，内部 Feign 接口不对外暴露（`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java:14-32`，`docs/api-contracts.md:121-128`）。
- 登录防爆破配置在 Nacos：10 分钟 5 次失败锁 30 分钟；实现细节可追问 `LoginAttemptService`（`docs/nacos/shopsphere-user.yaml:13-20`）。
- Gateway management 端口绑定 `127.0.0.1`，减少 refresh/管理端暴露面（`shopsphere-gateway/src/main/resources/application.yml:27-41`）。

## 10. 面试风险：可能被追问和需要补充说明的地方

1. 前端缺失。仓库里没有前端实现，只能说当前项目以后端微服务、交易一致性和推荐链路为主；如果岗位要求全栈，需要补充一个简单前端或明确“前端待确认”（`docs/architecture.md:9-17`，仓库文件扫描未发现前端配置）。

2. Product 库存字段语义有版本演进痕迹。迁移脚本注释把 `t_product_stock.stock` 写成“真实总量”，但 TCC 实现注释和契约将其解释为“可售库存池，真实总量 = stock + locked_stock”。面试时要主动说明：最终以代码和契约为准，迁移注释可能是早期遗留，需要修正文档注释（`shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql:33-41`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:29-30`，`docs/api-contracts.md:166-171`）。

3. Seata TCC 不是标准 `@TwoPhaseBusinessAction`。代码明确使用“业务显式 TCC”，因为 Confirm 要延迟到支付请求触发，不能在 Seata Try 全局事务提交时自动二阶段回调。面试官可能会追问这是否还算 TCC，需要解释这是按业务阶段拆出的 Try/Confirm/Cancel 语义，配合 Seata 管 Order 本地事务和 XID 关联，不是 Seata 注解式 TCC（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:21-30`，`docs/api-contracts.md:166-172`）。

4. outbox 的边界：文档明确提到 stale SENT 行问题，即消息 send 后 JVM 崩溃且 confirm 永不到达时，行可能停在 SENT，本期不回收。面试时可以说已识别边界，后续补充 “SENT 超时回 PENDING” 清扫任务（`docs/mq-topology.md:142-145`）。

5. 订单超时队列 TTL 不可动态修改。RabbitMQ 队列参数不可变更，修改 `queue-ttl-ms` 需要重建队列，E2E 文档也要求 down -v 后重声明。面试时避免宣称支付超时时间完全热更新无副作用（`docs/mq-topology.md:146-147`，`docs/nacos/shopsphere-order.yaml:10-14`）。

6. 内部接口安全依赖网关拒绝和网络隔离。代码层没有给 `/internal/**` 做服务间签名或 mTLS；文档说明服务间调用不经 Gateway，内部接口不做 JWT。生产环境如果服务暴露在不可信网络，需要补充内网隔离、mTLS、服务网格或内部 token（`docs/api-contracts.md:121-128`，`shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java:14-32`）。

7. 推荐质量是工程闭环，不是复杂算法。ItemCF 实现包含隐式反馈权重、热门惩罚、Top-N 相似度、热门 fallback，但没有看到 A/B 测试、召回评估指标、排序模型或特征平台；如果面试算法/推荐岗位，需要说明这是工程型推荐闭环，推荐效果评估待补充（`shopsphere-recommendation/app/service/itemcf.py:1-14`，`shopsphere-recommendation/app/service/recall.py:1-10`）。

8. 生产部署仍是 Compose 级别。文档只给出生产差异，没有 Kubernetes manifests、Helm、云数据库、密钥管理落地配置；面试要把“一键起栈”定位为本地/CI/演示环境，生产 HA/K8s 是待确认或后续工作（`docs/deployment.md:1-24`，`docs/deployment.md:135-150`）。

9. Gateway 指标未被 Prometheus 抓取。Prometheus 配置只抓 user/product/order，注释说明 gateway management 绑 127.0.0.1 容器不可达。面试可解释压测瓶颈集中在 order/product，但生产完整可观测需要补 gateway 指标暴露方案（`monitoring/prometheus/prometheus.yml:1-27`）。

10. 外部支付链路是简化实现。`/api/order/{id}/pay` 是支付确认接口，仓库内未看到第三方支付回调验签、支付单、退款、对账等模型；面试交易支付方向需要明确这是模拟支付确认，不是完整支付系统（`shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java:57-65`，`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:185-210`）。

11. 文档中提到 1000 并发和压测指标，但本分析未实际运行压测或测试，只确认了脚本和验收标准存在。若要作为简历量化结果，建议用最新 `perf/results/evidence.txt` 或 `docs/perf-tcc-report.md` 的实测数据佐证（`perf/README.md:19-26`，`perf/README.md:68-82`）。

## 面试讲法建议

可以用一句话开场：“ShopSphere 是我用 Spring Cloud Alibaba + FastAPI 做的微服务电商项目，核心不是 CRUD，而是围绕下单交易构建了 TCC 库存预留、Redis Lua 抗超卖、outbox 可靠消息、RabbitMQ 超时取消，以及行为事件驱动的 ItemCF 推荐闭环。”这句话对应的证据分别在 `README.md:13-19`、`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:98-180`、`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:56-158`、`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:27-42`、`shopsphere-recommendation/app/service/recall.py:62-134`。

如果只能讲 3 个亮点，优先讲：

1. 高并发下单不超卖：Redis Lua 预扣 + DB 条件更新 + TCC 日志幂等/空回滚/防悬挂（`shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java:17-35`，`shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java:56-158`）。
2. 可靠消息：订单与 outbox 同事务，定时中继、publisher confirm、退避重试、DLX 边界（`shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java:157-172`，`shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java:63-162`，`docs/mq-topology.md:91-112`）。
3. 异构推荐闭环：Java 用户/订单事件通过 RabbitMQ 到 Python 推荐服务自有库，ItemCF 离线训练 + Redis 在线召回 + 热门 fallback（`shopsphere-recommendation/app/consumer/behavior_consumer.py:1-10`，`shopsphere-recommendation/app/service/itemcf.py:1-14`，`shopsphere-recommendation/app/service/recall.py:62-134`）。
