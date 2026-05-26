# ShopSphere 可视化项目讲解网站规划

> 面向对象：准备面试的求职者。目标不是做运维文档，而是把 ShopSphere 讲成一套“能被面试官快速听懂、能追问、能落到源码”的系统设计故事。

## 设计基调

- 图示风格：参考 ByteByteGo 式系统设计教学表达，使用清晰线框、少色块、高对比文本、模块卡片、箭头流程、时序图和状态机图。重点是“看一眼知道链路”，不是炫技动效。
- 页面结构：每页固定回答四个问题：这个模块做什么、怎么运行、为什么这么设计、面试怎么讲。
- 视觉语言：白底或浅灰底，卡片圆角 6-8px，主色用蓝/青表示同步链路，橙色表示事务/库存风险，紫色表示推荐链路，红色仅用于故障/风险。
- 图表规范：所有图都必须带“源码证据”侧栏，点击后能看到文件路径；所有架构判断都从 `repo-analysis.md` 和仓库文件反推。
- 非目标：不做电商用户购物前台，不做登录下单真实操作界面，不展示无证据的生产云架构。

## 页面列表

1. 首页：30 秒讲清项目
2. 全局架构页：五服务与基础设施
3. 网关与鉴权页：JWT、白名单、上下文透传
4. 下单主链路页：Order 发起、幂等、TCC Try
5. 库存一致性页：Redis Lua、DB 条件更新、TCC 三段
6. 可靠消息页：outbox、RabbitMQ、积分/通知/超时
7. 推荐闭环页：行为事件、ItemCF、冷热启动
8. 数据模型与状态机页：核心实体、状态变化
9. 部署与可观测页：Docker Compose、Nacos、Seata、Prometheus
10. 面试攻防页：亮点、风险、追问回答

## 1. 首页：30 秒讲清项目

### 页面目标

让访问者在第一屏知道：ShopSphere 是一个后端微服务电商项目，核心卖点是交易一致性、抗超卖、可靠消息和推荐闭环。

### 需要展示的图

- Hero 架构速览图：Client -> Gateway -> User/Product/Order/Recommendation，下方挂 MySQL/Redis/RabbitMQ/Nacos/Seata/Sentinel。
- 三张重点卡片：
  - 高并发下单不超卖
  - outbox 可靠消息
  - 行为驱动推荐闭环
- “面试路线”进度条：架构总览 -> 下单链路 -> 库存 TCC -> MQ -> 推荐 -> 风险。

### 核心文案

- 做什么：ShopSphere 是一个微服务电商后端项目，用来展示从用户登录、商品浏览、下单支付、库存一致性到推荐召回的完整链路。
- 怎么运行：外部请求先进入 Gateway，再路由到 User、Product、Order、Recommendation；中间通过 Nacos、Redis、RabbitMQ、Seata、Sentinel、MySQL 支撑治理和数据一致性。
- 为什么这么设计：项目重点不是 CRUD，而是把电商交易中最容易被追问的高并发库存、分布式事务、可靠消息、推荐数据闭环做成可讲清的工程样例。
- 面试怎么讲：开场可以说：“我做的是一个 Spring Cloud Alibaba + FastAPI 的微服务电商项目，重点解决下单抗超卖、库存 TCC、outbox 可靠消息和用户行为推荐闭环。”

### 源码证据路径

- `repo-analysis.md`
- `README.md`
- `docs/architecture.md`
- `docs/adr/ADR-001-service-boundary.md`
- `docker-compose.yml`

### 面试者重点记住

- 一句话定位：不是商城前端，是电商微服务后端系统。
- 三个主亮点：库存一致性、可靠消息、推荐闭环。
- 一个边界：仓库未提供独立前端实现。

## 2. 全局架构页：五服务与基础设施

### 页面目标

解释系统被拆成哪些服务，每个服务负责什么，服务之间如何通信，数据如何隔离。

### 需要展示的图

- 模块图：Gateway、User、Product、Order、Recommendation 五个服务卡片。
- 依赖拓扑图：
  - 同步：Order -> Product Feign
  - 异步：User/Order -> RabbitMQ -> Recommendation/User consumers
  - 治理：所有服务 -> Nacos；Order/Product -> Seata；Gateway/User -> Sentinel
- 每服务一库图：User DB、Product DB、Order DB、Reco DB。

### 核心文案

- 做什么：全局架构页负责把服务边界讲清楚，避免面试时把所有功能讲成一个大单体。
- 怎么运行：Gateway 只承接外部流量；Java 服务间调用走 Nacos 服务发现和 Feign；弱一致链路用 RabbitMQ；推荐服务用 Python/FastAPI 独立部署并拥有自有库。
- 为什么这么设计：按业务子域拆分可以让交易、商品、用户、推荐各自演进；推荐用 Python 是为了利用机器学习生态，同时通过 MQ 避免跨库读取。
- 面试怎么讲：先讲服务边界，再讲通信方式，再讲“每服务一库”和异构推荐，最后补充本地/CI 通过 Compose 一键起栈。

### 源码证据路径

- `README.md`
- `docs/architecture.md`
- `docs/adr/ADR-001-service-boundary.md`
- `docs/api-contracts.md`
- `shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java`
- `shopsphere-api/user-api/src/main/java/com/shopsphere/api/user/UserFeignClient.java`
- `shopsphere-recommendation/app/main.py`

### 面试者重点记住

- Gateway 对外，Feign 对内，MQ 解耦弱一致。
- Recommendation 不读 User 库，靠 MQ 同步行为数据。
- 服务拆分是业务域拆分，不是按 Controller/DAO 技术层拆分。

## 3. 网关与鉴权页：JWT、白名单、上下文透传

### 页面目标

解释统一鉴权为什么放在 Gateway，下游服务如何拿到用户身份，以及如何防止伪造 header。

### 需要展示的图

- 鉴权流程图：Client -> RequestLogFilter -> InternalAccessRejectFilter -> JwtAuthFilter -> downstream service。
- 白名单决策图：public route 放行，protected route 验签，internal route 403。
- 请求头转换卡片：外部 header 被剥离，Gateway 重新注入 `X-Trace-Id`、`X-User-Id`、`X-User-Name`。
- 小型时序图：login 签发 JWT -> Gateway 验签 -> service 从 `UserContextHolder` 获取 userId。

### 核心文案

- 做什么：Gateway 统一做路由、JWT 验签、traceId 生成、上下文注入和内部接口防护。
- 怎么运行：`RequestLogFilter` 先移除外部传入的用户/trace 头并生成 traceId；`JwtAuthFilter` 对非白名单请求验签；验签成功后注入用户上下文，下游由 common 拦截器读取。
- 为什么这么设计：鉴权集中可以减少业务服务重复代码；剥离外部 header 可以防止用户伪造身份；`/internal/**` 不暴露给外部可以保护 Feign 内部接口。
- 面试怎么讲：强调“业务服务不验 JWT，但不是裸奔，因为外部流量只能过 Gateway，Gateway 会剥离伪造头并重新注入可信上下文。”

### 源码证据路径

- `shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java`
- `shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java`
- `shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java`
- `shopsphere-common/src/main/java/com/shopsphere/common/context/UserContextInterceptor.java`
- `shopsphere-common/src/main/java/com/shopsphere/common/util/JwtUtil.java`
- `docs/nacos/shopsphere-gateway.yaml`
- `docs/api-contracts.md`

### 面试者重点记住

- 统一鉴权在 Gateway。
- 下游只信任 Gateway 注入的 header。
- `/internal/**` 被外部拒绝。
- 生产风险：如果服务暴露到不可信网络，需要补内网隔离、mTLS 或内部 token。

## 4. 下单主链路页：Order 发起、幂等、TCC Try

### 页面目标

把最核心的下单流程讲成一条可视化主线，说明从请求进来到订单创建、库存预留和消息写入发生了什么。

### 需要展示的图

- 主流程图：`POST /api/order/create` -> 幂等检查 -> 查商品 -> 服务端计价 -> 写订单/outbox/request -> Product stockTry。
- 时序图：Client、Gateway、Order、Product、MySQL、Redis、RabbitMQ。
- 幂等卡片：`X-Request-Id` + `(userId, requestId)` 唯一约束。
- 事务边界图：Order 本地 AT 分支 + Product 业务 TCC Try。

### 核心文案

- 做什么：Order 服务负责创建订单，并作为下单链路的分布式事务发起方。
- 怎么运行：Controller 要求 `X-Request-Id`；Service 先查幂等记录，未命中则开启全局事务，查商品、计价、写订单和 outbox，再调用 Product 的库存 Try。
- 为什么这么设计：先写订单和消息，再调用库存 Try，失败时由全局事务回滚 Order 本地分支；幂等表保证客户端重试不会重复下单。
- 面试怎么讲：讲下单时不要从“插订单表”开始，而要从“幂等、服务端计价、库存预留、outbox 同事务”四个关键词开始。

### 源码证据路径

- `shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java`
- `shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java`
- `shopsphere-order/src/main/java/com/shopsphere/order/service/OrderPersistServiceImpl.java`
- `shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql`
- `shopsphere-order/src/main/resources/db/migration/V20260522_1000__add_order_address.sql`
- `shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java`
- `docs/api-contracts.md`

### 面试者重点记住

- 下单接口必须带 `X-Request-Id`。
- 订单、明细、幂等记录和 outbox 同本地事务写入。
- 下单只做库存 Try 预留，不等于支付成功。
- Product 库存 Try 失败时，Order 本地写入会回滚。

## 5. 库存一致性页：Redis Lua、DB 条件更新、TCC 三段

### 页面目标

解释项目最容易被追问的抗超卖设计：Redis 可售计数、DB 可售/预留库存、TCC 日志如何配合。

### 需要展示的图

- 三段 TCC 状态图：
  - Try：Redis `stock:product:{id} -= q`，DB `stock-=q, locked_stock+=q`
  - Confirm：DB `locked_stock-=q`
  - Cancel：DB 回补，Redis 回补
- 库存数值示意卡片：`真实总量 = stock + locked_stock`。
- 防异常图：幂等、空回滚、防悬挂、部分失败回补。
- Redis Lua 原子操作小图：避免 GET + SET 竞态。

### 核心文案

- 做什么：Product 服务负责商品库存的高并发预扣、确认和取消回补。
- 怎么运行：Try 阶段先用 Redis Lua 原子预扣，再用 DB 条件更新锁定库存，并写 `t_stock_tcc_log`；Confirm 只扣预留库存；Cancel 根据是否 Confirm 做释放或逆向补偿，并显式回补 Redis。
- 为什么这么设计：只靠 DB 行锁会影响热点商品并发；只靠 Redis 又难以保证最终一致。Redis 负责高并发入口，DB 负责事实记录，TCC 日志负责异常恢复语义。
- 面试怎么讲：重点讲“Redis 和 DB 不是双写随缘一致，而是 Try/Confirm/Cancel 三个业务动作明确维护它们的一致性。”

### 源码证据路径

- `shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java`
- `shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java`
- `shopsphere-product/src/main/resources/scripts/stock_prededuct.lua`
- `shopsphere-product/src/main/resources/scripts/stock_restore.lua`
- `shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql`
- `shopsphere-product/src/main/resources/db/migration/V20260521_1001__add_stock_tcc_log.sql`
- `docs/api-contracts.md`
- `docs/stock-redis.md`
- `docs/tcc-rollback-report.md`

### 面试者重点记住

- Try 预留，Confirm 出库，Cancel 释放。
- 幂等键是 `(orderId, productId, phase)`。
- 空回滚和防悬挂是 TCC 必问点。
- 迁移脚本里 `stock` 注释有历史语义，最终以代码/契约的“可售库存池”为准。

## 6. 可靠消息页：outbox、RabbitMQ、积分/通知/超时

### 页面目标

解释为什么订单事件不能直接 `convertAndSend`，本地消息表如何保证订单与消息一致，以及消费者如何幂等。

### 需要展示的图

- outbox 流程图：订单事务写 `t_local_message` -> 定时 relay -> RabbitMQ -> confirm -> 状态更新。
- MQ 拓扑图：`shopsphere.order`、`shopsphere.behavior`、`q.points`、`q.notify`、`q.reco.behavior`、`q.order.timeout.wait`、`q.order.timeout`、DLQ。
- 消息状态机：PENDING -> SENT -> CONFIRMED；SENT/PENDING -> FAILED。
- 超时取消时序图：order.payment.timeout -> TTL wait queue -> DLX -> timeout consumer -> cancel。

### 核心文案

- 做什么：可靠消息页讲订单创建后如何通知积分、通知、推荐和超时取消。
- 怎么运行：Order 在本地事务中写订单和 outbox；`LocalMessagePublisher` 定时扫描 PENDING 消息，用 RabbitTemplate 发送并监听 publisher confirm；消费者使用手动 ack、幂等键和 DLX。
- 为什么这么设计：如果订单入库成功但消息发送失败，后续积分/通知/推荐/超时取消都会丢；outbox 把业务数据和消息意图放到同一个数据库事务里。
- 面试怎么讲：可以说“我没有在下单事务里直接发 MQ，而是先落本地消息表，再由中继异步发送，用 confirm 和状态机追踪投递结果。”

### 源码证据路径

- `shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java`
- `shopsphere-order/src/main/java/com/shopsphere/order/messaging/OrderTimeoutConsumer.java`
- `shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java`
- `shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql`
- `shopsphere-user/src/main/java/com/shopsphere/user/messaging/PointsConsumer.java`
- `shopsphere-user/src/main/java/com/shopsphere/user/messaging/NotificationConsumer.java`
- `shopsphere-recommendation/app/consumer/behavior_consumer.py`
- `docs/mq-topology.md`

### 面试者重点记住

- outbox 解决“订单成功但消息丢失”。
- 生产链路 `order.*` 是强可靠；用户行为 `user.behavior` 是轻量 fail-open。
- 消费端要讲幂等键：积分用 `order_id`，推荐用 `event_id`。
- 已知边界：SENT 行 confirm 永不到达时需要后续 stale SENT 清扫。

## 7. 推荐闭环页：行为事件、ItemCF、冷热启动

### 页面目标

把推荐服务从“Python 附属模块”讲成独立业务闭环：数据从哪里来、怎么训练、怎么在线召回、冷启动怎么处理。

### 需要展示的图

- 数据闭环图：User behavior / Order created -> RabbitMQ -> Recommendation DB -> TrainJob -> Redis sim/hot -> API。
- ItemCF 训练管线图：行为窗口 -> user-item 矩阵 -> item-item 相似度 -> Redis ZSET。
- 在线召回流程图：recent behavior -> sim:item 查邻居 -> 加权聚合 -> 过滤已购 -> Top-K。
- fallback 分支图：model not ready / no behavior / empty neighbors -> hot:items:global。

### 核心文案

- 做什么：Recommendation 服务提供用户个性化推荐和相似商品推荐。
- 怎么运行：它消费行为和订单事件，落自有库 `behavior_event`；定时训练 ItemCF 相似度并写 Redis；在线请求优先走个性化召回，失败或冷启动时返回热门商品。
- 为什么这么设计：推荐服务不跨服务读 User 或 Order 数据库，避免破坏服务边界；用 MQ 保持最终一致；用 Redis 支撑低延迟在线召回。
- 面试怎么讲：不要夸成复杂推荐系统，要诚实说这是工程型推荐闭环，亮点在数据边界、异构服务接入、训练互斥和 fallback。

### 源码证据路径

- `shopsphere-recommendation/app/main.py`
- `shopsphere-recommendation/app/consumer/behavior_consumer.py`
- `shopsphere-recommendation/app/service/itemcf.py`
- `shopsphere-recommendation/app/service/recall.py`
- `shopsphere-recommendation/app/tasks/train_job.py`
- `shopsphere-recommendation/alembic/versions/20260523_0001_init_behavior_event_and_train_log.py`
- `docs/integration-recommend.md`
- `docs/recsys-train-perf.md`
- `docs/architecture.md`

### 面试者重点记住

- 推荐服务有自有库，不跨库读数据。
- 行为来自两路：用户行为和订单创建。
- 在线推荐有冷启动 fallback，不让接口因为模型未就绪返回 5xx。
- 算法深度有限，偏工程推荐系统。

## 8. 数据模型与状态机页：核心实体、状态变化

### 页面目标

让面试者能快速回答“有哪些表、它们怎么关联、状态怎么流转”。

### 需要展示的图

- 领域 ER 简图：
  - User：`t_user`、`t_user_profile`、`t_user_behavior`、`t_user_points`、`t_points_log`
  - Product：`t_product`、`t_category`、`t_product_stock`、`t_stock_tcc_log`
  - Order：`t_order`、`t_order_item`、`t_order_request`、`t_local_message`
  - Recommendation：`behavior_event`、`t_train_log`
- 订单状态机：CREATED -> PAID -> SHIPPED -> COMPLETED；CREATED/PAID -> CANCELLED。
- 本地消息状态机：PENDING -> SENT -> CONFIRMED / FAILED。
- 训练任务状态机：RUNNING -> SUCCESS / FAILED。

### 核心文案

- 做什么：本页把系统中的关键表和状态机放到一张面试速查图里。
- 怎么运行：用户、商品、订单、推荐各有自己的数据模型；跨服务关系不靠外键跨库约束，而靠 Feign、MQ payload 和业务幂等键维护。
- 为什么这么设计：每服务一库符合微服务边界；状态机能防止订单重复支付、已发货取消、消息重复投递等问题。
- 面试怎么讲：先讲订单状态机，再讲库存 TCC 日志和 outbox 状态机，因为这两个最容易体现工程复杂度。

### 源码证据路径

- `shopsphere-user/src/main/resources/db/migration/V20260520_1000__init_user.sql`
- `shopsphere-user/src/main/resources/db/migration/V20260520_1001__add_user_behavior.sql`
- `shopsphere-user/src/main/resources/db/migration/V20260522_1000__add_points.sql`
- `shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql`
- `shopsphere-product/src/main/resources/db/migration/V20260521_1001__add_stock_tcc_log.sql`
- `shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql`
- `shopsphere-order/src/main/java/com/shopsphere/order/statemachine/OrderStatusTransitionValidator.java`
- `shopsphere-order/src/main/java/com/shopsphere/order/enums/OrderStatus.java`
- `shopsphere-recommendation/alembic/versions/20260523_0001_init_behavior_event_and_train_log.py`

### 面试者重点记住

- 订单、库存、消息、训练各有状态机。
- 幂等表/幂等键是高频追问点。
- 每服务一库意味着不要说“推荐直接查用户库”。

## 9. 部署与可观测页：Docker Compose、Nacos、Seata、Prometheus

### 页面目标

解释项目如何跑起来，基础设施各自做什么，哪些是本地/CI 能证明的，哪些生产能力还待补齐。

### 需要展示的图

- Compose 部署图：infra 层、Java 服务层、Python 服务层、monitoring 层。
- 配置流向图：`docs/nacos` -> nacos-config-init -> Nacos -> services。
- 可观测图：user/product/order -> Prometheus -> Grafana。
- 环境差异表：local/CI vs production。

### 核心文案

- 做什么：本页说明如何用一条 Compose 命令拉起基础设施和业务服务，并展示配置和监控链路。
- 怎么运行：MySQL、Nacos、Redis、RabbitMQ、Seata、Sentinel 先启动；Java/Python 服务通过 profile 启动；Nacos 配置由脚本推送；Prometheus 抓取业务服务指标。
- 为什么这么设计：个人项目用 Compose 降低部署成本，保证面试演示和 E2E/压测可复现；生产 HA、K8s、云数据库等作为后续扩展。
- 面试怎么讲：强调“我能本地一键起完整栈并跑 E2E/压测”，但不要把 Compose 说成生产级容器编排。

### 源码证据路径

- `docker-compose.yml`
- `docker-compose.infra.yml`
- `docs/deployment.md`
- `docs/nacos/`
- `scripts/wait-stack-healthy.sh`
- `scripts/nacos-config-push.sh`
- `monitoring/prometheus/prometheus.yml`
- `monitoring/grafana/dashboards/order-perf.json`
- `shopsphere-e2e-test/README.md`
- `perf/README.md`

### 面试者重点记住

- 本地/CI：Compose 是主要部署方式。
- 配置：Nacos 是服务配置中心和注册中心。
- 监控：Prometheus 当前抓 user/product/order，Gateway 指标是已知缺口。
- 生产：HA、K8s、密钥管理、网关指标暴露需要补充说明。

## 10. 面试攻防页：亮点、风险、追问回答

### 页面目标

把 `repo-analysis.md` 的面试风险转成可背诵、可点击源码证据的问答卡片。

### 需要展示的图

- “三大亮点”卡片墙：库存 TCC、outbox、推荐闭环。
- “高频追问”问答列表：
  - 为什么不用 Seata AT 扣库存？
  - 为什么业务 TCC 不是 `@TwoPhaseBusinessAction`？
  - outbox 有什么边界？
  - 推荐为什么不跨库查 User？
  - Gateway 不让业务服务验 JWT 安全吗？
- 风险雷达图：前端缺失、生产部署、推荐算法深度、外部支付简化、Gateway 指标缺口。
- 回答模板：背景 -> 方案 -> 权衡 -> 证据路径。

### 核心文案

- 做什么：帮助求职者把项目亮点讲得克制、准确，并主动暴露边界。
- 怎么运行：每个问答卡片都包含“标准回答、不要这么说、源码证据、可补充优化”四段。
- 为什么这么设计：面试官通常不是只听架构图，而是追问权衡和边界；把风险前置能显得更可信。
- 面试怎么讲：先讲已实现的证据，再讲权衡和后续优化；不要把 Compose 说成生产 HA，不要把 ItemCF 说成复杂推荐平台，不要说库存字段没有历史歧义。

### 源码证据路径

- `repo-analysis.md`
- `docs/mq-topology.md`
- `docs/api-contracts.md`
- `docs/deployment.md`
- `monitoring/prometheus/prometheus.yml`
- `shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java`
- `shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java`
- `shopsphere-recommendation/app/service/itemcf.py`
- `shopsphere-recommendation/app/service/recall.py`

### 面试者重点记住

- 主动讲边界比被动承认更稳。
- 所有亮点都要落到源码路径。
- 最推荐背诵的三个句式：
  - “这个设计解决的是高并发库存预留和 Redis/DB 一致性问题。”
  - “我用 outbox 把订单和消息意图放进同一个本地事务。”
  - “推荐服务不跨库读数据，而是通过 MQ 建自己的行为库。”

## 全站导航与交互建议

- 顶部导航：概览、架构、链路、数据、部署、面试。
- 侧边证据栏：当前页面引用的文件路径，支持复制路径。
- 图文联动：点击流程图节点，高亮对应“做什么/怎么运行/为什么/面试怎么讲”文案。
- 面试模式：隐藏源码细节，只显示 60 秒讲法、3 分钟讲法、追问回答。
- 证据模式：显示文件路径、类名、表名、关键方法名。
- 风险标记：对待确认或边界项使用黄色角标，例如“前端待确认”“生产 HA 待补”“Gateway 指标缺口”。

## 可视化组件清单

- `ArchitectureMap`：服务拓扑模块图。
- `FlowDiagram`：下单、推荐、超时取消等流程。
- `SequenceDiagram`：登录鉴权、下单、outbox、推荐召回。
- `StateMachine`：订单、库存 TCC、消息、训练任务。
- `EvidenceCard`：源码证据路径卡片。
- `InterviewCard`：面试讲法卡片。
- `RiskCallout`：边界和风险提示。
- `MetricCard`：压测指标和可观测指标。

## 首版实现优先级

1. 首页、全局架构页、下单主链路页、库存一致性页、面试攻防页。
2. 可靠消息页、推荐闭环页。
3. 数据模型页、部署与可观测页。
4. 交互增强：证据栏、面试模式、图文联动。

## 内容验收标准

- 每页必须出现四段：做什么、怎么运行、为什么这么设计、面试怎么讲。
- 每页至少有 2 类图：卡片/流程图/模块图/时序图/状态机图中的任意两类。
- 每页必须列出源码证据路径。
- 所有“不确定”内容必须标为待确认或风险，不允许写成已实现。
- 任何图上的箭头都必须能在源码、配置、迁移或 `repo-analysis.md` 中找到证据。
