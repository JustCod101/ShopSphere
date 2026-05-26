export type Accent = "blue" | "cyan" | "orange" | "purple" | "red" | "green";

export type PageId =
  | "overview"
  | "architecture"
  | "modules"
  | "flow"
  | "data"
  | "reliability"
  | "interview";

export type Explanation = {
  what: string;
  how: string;
  why: string;
  interview: string;
};

export type EvidenceClaim = {
  claim: string;
  paths: string[];
};

export type MermaidDiagram = {
  title: string;
  description: string;
  code: string;
};

export type Page = {
  id: PageId;
  title: string;
  eyebrow: string;
  goal: string;
  hero: string;
  explanation: Explanation;
  evidence: EvidenceClaim[];
  remember: string[];
  diagrams: MermaidDiagram[];
};

export type ModuleIconKey =
  | "gateway"
  | "user"
  | "product"
  | "order"
  | "recommendation"
  | "infra";

export type Module = {
  id: string;
  name: string;
  accent: Accent;
  role: string;
  responsibilities: string[];
  coreFiles: string[];
  calls: string[];
  interview: string;
  position: { x: number; y: number };
  icon?: ModuleIconKey;
  tech?: string[];
};

export type EdgeKind = "sync" | "async" | "infra";

export type ArchitectureEdge = {
  from: string;
  to: string;
  label?: string;
  kind?: EdgeKind;
};

export type RequestStep = {
  id: string;
  label: string;
  actor: string;
  summary: string;
  why: string;
  evidence: string[];
  accent: Accent;
};

export type InterviewQuestion = {
  question: string;
  answer: string;
  avoid: string;
  evidence: string[];
};

export const site = {
  title: "ShopSphere 项目讲解站",
  subtitle: "把微服务电商后端讲成一套可追问、可落源码的系统设计故事",
  sourcePlan: "docs/site-plan.md",
  sourceAnalysis: "repo-analysis.md",
  constraints: [
    "页面不依赖真实后端，所有内容来自本地 TypeScript 数据。",
    "每个结论都显示源码证据路径。",
    "待确认和已知边界不写成已实现能力。"
  ]
};

export const pages: Page[] = [
  {
    id: "overview",
    title: "Overview",
    eyebrow: "30 秒项目定位",
    goal: "让面试官快速知道 ShopSphere 不是普通 CRUD，而是围绕交易一致性、可靠消息和推荐闭环设计的后端微服务项目。",
    hero: "Spring Cloud Alibaba + FastAPI 的微服务电商后端，核心讲法是：网关统一入口、交易链路抗超卖、outbox 保证订单事件、推荐服务通过 MQ 建立行为闭环。",
    explanation: {
      what: "ShopSphere 展示用户登录、商品浏览、下单支付、库存一致性、订单事件和推荐召回的完整后端链路。",
      how: "外部请求先进入 Gateway，再路由到 User、Product、Order、Recommendation；基础设施由 MySQL、Redis、RabbitMQ、Nacos、Seata、Sentinel 支撑。",
      why: "项目重点放在面试常问的高并发库存、分布式事务、可靠消息和推荐数据闭环，而不是做一个商城前台。",
      interview: "开场说：我做的是一个 Spring Cloud Alibaba + FastAPI 的微服务电商项目，重点解决下单抗超卖、库存 TCC、outbox 可靠消息和用户行为推荐闭环。"
    },
    evidence: [
      {
        claim: "项目定位是后端微服务电商系统，前端实现未在仓库中发现。",
        paths: ["repo-analysis.md", "README.md", "docs/architecture.md"]
      },
      {
        claim: "本地/CI 部署主要通过 Docker Compose 拉起业务服务和基础设施。",
        paths: ["docker-compose.yml", "docker-compose.infra.yml", "docs/deployment.md"]
      }
    ],
    remember: ["一句话定位：电商微服务后端系统。", "三大亮点：库存一致性、可靠消息、推荐闭环。", "边界：不要把它讲成已有完整商城前端。"],
    diagrams: [
      {
        title: "系统速览",
        description: "从外部入口到服务与基础设施的静态拓扑。",
        code: `flowchart LR
  Client["Client / API Tester"] --> Gateway["Gateway"]
  Gateway --> User["User Service"]
  Gateway --> Product["Product Service"]
  Gateway --> Order["Order Service"]
  Gateway --> Recommendation["Recommendation Service"]
  Order --> ProductApi["Product Feign"]
  User --> MQ["RabbitMQ"]
  Order --> MQ
  MQ --> Recommendation
  User --> MySQL["MySQL"]
  Product --> MySQL
  Order --> MySQL
  Recommendation --> MySQL
  Product --> Redis["Redis"]
  Recommendation --> Redis
  Gateway --> Nacos["Nacos / Sentinel"]
  Order --> Seata["Seata"]
  Product --> Seata`
      }
    ]
  },
  {
    id: "architecture",
    title: "Architecture",
    eyebrow: "服务边界与通信方式",
    goal: "解释五个服务各自负责什么、服务之间如何通信、数据如何隔离。",
    hero: "架构讲解顺序：Gateway 对外，Feign 对内，RabbitMQ 解耦弱一致，Nacos/Seata/Sentinel 做治理，每服务拥有自己的数据边界。",
    explanation: {
      what: "全局架构页负责把服务边界讲清楚，避免把所有功能讲成一个大单体。",
      how: "Gateway 承接外部流量；Java 服务间调用走 Nacos 服务发现和 Feign；弱一致链路走 RabbitMQ；推荐服务用 Python/FastAPI 独立部署并拥有自有库。",
      why: "按业务子域拆分有利于交易、商品、用户、推荐各自演进；推荐使用 Python 是为了复用机器学习生态，并通过 MQ 避免跨库读取。",
      interview: "先讲服务边界，再讲同步/异步通信，再讲每服务一库和异构推荐，最后补充本地/CI 通过 Compose 一键起栈。"
    },
    evidence: [
      {
        claim: "系统按 Gateway、User、Product、Order、Recommendation 拆分。",
        paths: ["README.md", "docs/architecture.md", "docs/adr/ADR-001-service-boundary.md"]
      },
      {
        claim: "服务间同步调用通过 Feign API 模块暴露契约。",
        paths: [
          "shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java",
          "shopsphere-api/user-api/src/main/java/com/shopsphere/api/user/UserFeignClient.java"
        ]
      }
    ],
    remember: ["Gateway 对外，Feign 对内，MQ 解耦弱一致。", "Recommendation 不跨库读 User/Order，而是消费事件建自己的行为库。", "服务拆分是业务域拆分，不是按技术层拆分。"],
    diagrams: [
      {
        title: "通信拓扑",
        description: "同步调用、异步事件和治理组件的关系。",
        code: `flowchart TD
  Gateway["Gateway"] --> User["User"]
  Gateway --> Product["Product"]
  Gateway --> Order["Order"]
  Gateway --> Reco["Recommendation"]
  Order -->|"Feign stock/product query"| Product
  User -->|"user.behavior"| MQ["RabbitMQ"]
  Order -->|"order.created / timeout"| MQ
  MQ -->|"behavior events"| Reco
  MQ -->|"points / notify"| User
  User -. "register/config" .-> Nacos["Nacos"]
  Product -. "register/config" .-> Nacos
  Order -. "register/config" .-> Nacos
  Order -. "global transaction" .-> Seata["Seata"]
  Product -. "TCC participant" .-> Seata`
      }
    ]
  },
  {
    id: "modules",
    title: "Core Modules",
    eyebrow: "点击模块看职责",
    goal: "把业务能力按模块讲清楚，并把每个模块的职责、核心文件、调用关系和面试讲法放到同一张卡片里。",
    hero: "本页适合面试前最后复盘：点一个服务，就能看到它负责什么、怎么被调用、哪些文件能证明、面试怎么讲。",
    explanation: {
      what: "核心模块覆盖网关鉴权、用户行为、商品库存、订单交易、推荐召回和公共能力。",
      how: "模块之间通过 Gateway 路由、Feign 内部调用、RabbitMQ 事件和 common 上下文拦截器协作。",
      why: "按业务能力而不是代码目录讲项目，可以让面试官快速理解系统边界和技术取舍。",
      interview: "讲模块时先说边界，再说调用方式，最后落到源码路径；不要只按 Controller、Service、Mapper 分层背目录。"
    },
    evidence: [
      {
        claim: "模块边界来自服务目录、架构文档和 ADR。",
        paths: ["docs/architecture.md", "docs/adr/ADR-001-service-boundary.md", "repo-analysis.md"]
      }
    ],
    remember: ["先讲业务能力，再讲技术实现。", "点击模块时重点看职责和调用关系。", "每个模块都要能落到核心文件。"],
    diagrams: []
  },
  {
    id: "flow",
    title: "Request Flow",
    eyebrow: "下单主链路逐步高亮",
    goal: "把最核心的下单链路讲成一条可点击、可追问的运行流程。",
    hero: "请求链路从 X-Request-Id 幂等开始，经过服务端计价、订单本地事务、outbox、Product 库存 Try，最终由支付/取消触发库存 Confirm 或 Cancel。",
    explanation: {
      what: "Order 服务负责创建订单，并作为下单链路的分布式事务发起方。",
      how: "Controller 要求 X-Request-Id；Service 查幂等记录，开启全局事务，查商品、计价、写订单和 outbox，再调用 Product 的库存 Try。",
      why: "幂等表避免客户端重试重复下单；服务端计价避免客户端篡改价格；outbox 让订单与消息意图进入同一个本地事务；TCC Try 把库存先预留而不是直接出库。",
      interview: "讲下单不要从插订单表开始，而要从幂等、服务端计价、库存预留、outbox 同事务这四个关键词开始。"
    },
    evidence: [
      {
        claim: "下单接口要求 X-Request-Id，并在服务层处理幂等、订单、消息和库存 Try。",
        paths: [
          "shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java",
          "shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java",
          "shopsphere-order/src/main/java/com/shopsphere/order/service/OrderPersistServiceImpl.java"
        ]
      },
      {
        claim: "Product Feign 提供库存相关内部调用契约。",
        paths: ["shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java", "docs/api-contracts.md"]
      }
    ],
    remember: ["下单接口必须带 X-Request-Id。", "订单、明细、幂等记录和 outbox 同本地事务写入。", "下单只做库存 Try 预留，不等于支付成功。"],
    diagrams: [
      {
        title: "下单时序",
        description: "从客户端到订单和库存 Try 的关键调用。",
        code: `sequenceDiagram
  participant C as Client
  participant G as Gateway
  participant O as Order
  participant P as Product
  participant DB as MySQL
  participant R as Redis
  participant MQ as RabbitMQ
  C->>G: POST /api/order/create + X-Request-Id
  G->>O: user context headers
  O->>DB: check t_order_request
  O->>P: query product price
  O->>DB: insert order/items/request/outbox
  O->>P: stockTry(orderId, productId, qty)
  P->>R: Lua pre-deduct stock
  P->>DB: lock stock and write t_stock_tcc_log
  O-->>C: order created
  O->>MQ: relay outbox later`
      }
    ]
  },
  {
    id: "data",
    title: "Data Model",
    eyebrow: "实体关系与状态机",
    goal: "让面试者能快速回答有哪些表、它们怎么关联、状态怎么流转。",
    hero: "数据模型不要只背表名，重点讲订单状态机、库存 TCC 日志、outbox 状态机和推荐训练状态。",
    explanation: {
      what: "本页把用户、商品、订单、推荐的关键表和状态机放到面试速查图里。",
      how: "各服务拥有自己的数据模型；跨服务关系不靠跨库外键，而靠 Feign、MQ payload 和业务幂等键维护。",
      why: "每服务一库符合微服务边界；状态机能防止重复支付、已发货取消、消息重复投递和训练任务并发冲突。",
      interview: "先讲订单状态机，再讲库存 TCC 日志和 outbox 状态机，这两个最能体现工程复杂度。"
    },
    evidence: [
      {
        claim: "用户、商品、订单、推荐各有独立迁移脚本定义主要实体。",
        paths: [
          "shopsphere-user/src/main/resources/db/migration/V20260520_1000__init_user.sql",
          "shopsphere-product/src/main/resources/db/migration/V20260521_1000__init_product.sql",
          "shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql",
          "shopsphere-recommendation/alembic/versions/20260523_0001_init_behavior_event_and_train_log.py"
        ]
      },
      {
        claim: "订单状态转换由枚举和状态校验器约束。",
        paths: [
          "shopsphere-order/src/main/java/com/shopsphere/order/enums/OrderStatus.java",
          "shopsphere-order/src/main/java/com/shopsphere/order/statemachine/OrderStatusTransitionValidator.java"
        ]
      }
    ],
    remember: ["订单、库存、消息、训练各有状态机。", "幂等表和幂等键是高频追问点。", "不要说推荐直接查用户库。"],
    diagrams: [
      {
        title: "核心实体",
        description: "按服务边界组织的主要实体。",
        code: `erDiagram
  USER ||--o{ USER_BEHAVIOR : emits
  USER ||--o{ USER_POINTS : owns
  PRODUCT ||--|| PRODUCT_STOCK : has
  PRODUCT ||--o{ STOCK_TCC_LOG : reserves
  ORDER ||--o{ ORDER_ITEM : contains
  ORDER ||--o{ LOCAL_MESSAGE : emits
  ORDER ||--|| ORDER_REQUEST : deduplicates
  BEHAVIOR_EVENT ||--o{ TRAIN_LOG : feeds`
      },
      {
        title: "订单状态机",
        description: "订单主状态的正向和取消路径。",
        code: `stateDiagram-v2
  [*] --> CREATED
  CREATED --> PAID
  PAID --> SHIPPED
  SHIPPED --> COMPLETED
  CREATED --> CANCELLED
  PAID --> CANCELLED`
      }
    ]
  },
  {
    id: "reliability",
    title: "Scaling & Reliability",
    eyebrow: "性能、可靠性与边界",
    goal: "解释库存、消息、推荐和部署可观测的可靠性设计，以及哪些生产能力仍然待补。",
    hero: "可靠性主线：Redis Lua 扛热点库存入口，DB/TCC 日志保事实，outbox 保订单事件，消费者幂等兜住重复投递，Compose 负责本地可复现。",
    explanation: {
      what: "本页覆盖库存抗超卖、可靠消息、推荐 fallback、部署和可观测能力。",
      how: "Product 用 Redis Lua + DB 条件更新 + TCC 日志；Order 用本地消息表 + publisher confirm；Recommendation 用热门商品 fallback；监控通过 Prometheus/Grafana 覆盖部分服务。",
      why: "这些设计分别解决热点库存竞争、订单成功但消息丢失、模型未就绪导致推荐不可用、演示环境不可复现的问题。",
      interview: "要主动讲边界：Compose 不是生产 HA，Gateway 指标仍是缺口，outbox 还需要 stale SENT 清扫，推荐是工程型 ItemCF 而不是复杂推荐平台。"
    },
    evidence: [
      {
        claim: "库存 Try/Confirm/Cancel 使用 Redis Lua、DB 条件更新和 TCC 日志。",
        paths: [
          "shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java",
          "shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java",
          "shopsphere-product/src/main/resources/scripts/stock_prededuct.lua",
          "shopsphere-product/src/main/resources/scripts/stock_restore.lua"
        ]
      },
      {
        claim: "订单事件通过本地消息表和 RabbitMQ relay 投递。",
        paths: [
          "shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java",
          "shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql",
          "docs/mq-topology.md"
        ]
      },
      {
        claim: "本地部署和监控通过 Compose、Prometheus、Grafana 描述。",
        paths: ["docker-compose.yml", "monitoring/prometheus/prometheus.yml", "monitoring/grafana/dashboards/order-perf.json"]
      }
    ],
    remember: ["Redis 负责高并发入口，DB 负责事实记录，TCC 负责异常语义。", "outbox 解决订单成功但消息丢失。", "生产 HA、密钥管理、Gateway 指标需要主动说明为待补。"],
    diagrams: [
      {
        title: "库存 TCC",
        description: "Redis、DB 和 TCC 日志的三段动作。",
        code: `stateDiagram-v2
  [*] --> TRY
  TRY: Redis Lua prededuct
  TRY: DB stock -= q and locked_stock += q
  TRY --> CONFIRM: payment success
  TRY --> CANCEL: payment timeout or failure
  CONFIRM: DB locked_stock -= q
  CANCEL: DB release locked stock
  CANCEL: Redis restore stock
  CONFIRM --> [*]
  CANCEL --> [*]`
      },
      {
        title: "Outbox 消息状态",
        description: "本地消息从待发送到确认或失败的状态机。",
        code: `stateDiagram-v2
  [*] --> PENDING
  PENDING --> SENT: relay sends
  SENT --> CONFIRMED: publisher confirm
  PENDING --> FAILED: retry exhausted
  SENT --> FAILED: stale or nack
  CONFIRMED --> [*]
  FAILED --> [*]`
      }
    ]
  },
  {
    id: "interview",
    title: "Interview Mode",
    eyebrow: "亮点、风险、追问回答",
    goal: "把项目亮点和风险转成可背诵、可点击源码证据的问答卡片。",
    hero: "面试模式的原则是：先讲已实现证据，再讲权衡和边界；主动暴露风险比被追问后补救更可信。",
    explanation: {
      what: "本页帮助求职者把库存 TCC、outbox、推荐闭环讲得克制、准确。",
      how: "每个问答卡片包含标准回答、不要这么说、源码证据和可补充优化。",
      why: "面试官通常会追问权衡、异常和边界；把风险前置能显得更可信。",
      interview: "使用背景、方案、权衡、证据路径的结构回答，不把 Compose 说成生产 HA，不把 ItemCF 说成复杂推荐平台。"
    },
    evidence: [
      {
        claim: "风险和追问点来自仓库分析结论。",
        paths: ["repo-analysis.md", "docs/mq-topology.md", "docs/api-contracts.md", "docs/deployment.md"]
      }
    ],
    remember: ["所有亮点都要落源码路径。", "主动讲边界。", "最推荐背诵：outbox 同事务、Redis/DB/TCC 协同、推荐不跨库读数据。"],
    diagrams: [
      {
        title: "回答模板",
        description: "每个追问按同一结构收束。",
        code: `flowchart LR
  B["背景: 面试官问什么问题"] --> S["方案: 当前仓库怎么做"]
  S --> T["权衡: 为什么这样设计"]
  T --> E["证据: 指到源码路径"]
  E --> N["下一步: 已知边界和补充方案"]`
      }
    ]
  }
];

export const modules: Module[] = [
  {
    id: "gateway",
    name: "Gateway",
    accent: "blue",
    role: "统一入口、JWT 验签、traceId 与用户上下文注入",
    responsibilities: ["路由外部请求", "剥离外部伪造 header", "非白名单请求验签", "拒绝外部访问 /internal/**"],
    coreFiles: [
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java",
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java",
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java"
    ],
    calls: ["Client -> Gateway -> User/Product/Order/Recommendation", "Gateway 注入 X-Trace-Id、X-User-Id、X-User-Name"],
    interview: "业务服务不验 JWT，但不是裸奔；外部流量统一过 Gateway，Gateway 剥离伪造头后注入可信上下文。",
    position: { x: 260, y: 60 },
    icon: "gateway",
    tech: ["Spring Cloud Gateway", "JWT", "Nacos"]
  },
  {
    id: "user",
    name: "User",
    accent: "cyan",
    role: "用户、登录、行为事件、积分和通知消费",
    responsibilities: ["登录和用户资料", "记录用户行为", "消费订单积分消息", "消费通知消息"],
    coreFiles: [
      "shopsphere-user/src/main/resources/db/migration/V20260520_1000__init_user.sql",
      "shopsphere-user/src/main/resources/db/migration/V20260520_1001__add_user_behavior.sql",
      "shopsphere-user/src/main/java/com/shopsphere/user/messaging/PointsConsumer.java",
      "shopsphere-user/src/main/java/com/shopsphere/user/messaging/NotificationConsumer.java"
    ],
    calls: ["User -> RabbitMQ user.behavior", "RabbitMQ order events -> User consumers"],
    interview: "User 不只是登录模块，还承担行为事件生产和订单后置消费，是推荐闭环与积分通知的入口之一。",
    position: { x: 60, y: 210 },
    icon: "user",
    tech: ["Spring Boot", "MyBatis-Plus", "RabbitMQ"]
  },
  {
    id: "product",
    name: "Product",
    accent: "orange",
    role: "商品查询和库存 TCC",
    responsibilities: ["商品/类目查询", "Redis Lua 预扣库存", "DB 条件更新锁定库存", "TCC Confirm/Cancel 释放或确认预留"],
    coreFiles: [
      "shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java",
      "shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java",
      "shopsphere-product/src/main/resources/scripts/stock_prededuct.lua",
      "shopsphere-product/src/main/resources/db/migration/V20260521_1001__add_stock_tcc_log.sql"
    ],
    calls: ["Order -> ProductFeignClient", "Product -> Redis", "Product -> MySQL", "Product -> Seata"],
    interview: "库存不是简单扣减，而是 Try 预留、Confirm 出库、Cancel 回补，用 Redis 扛并发入口，用 DB/TCC 日志记录事实。",
    position: { x: 270, y: 210 },
    icon: "product",
    tech: ["Redis Lua", "Seata TCC", "MyBatis-Plus"]
  },
  {
    id: "order",
    name: "Order",
    accent: "green",
    role: "下单、订单状态、幂等、outbox 和超时取消",
    responsibilities: ["校验 X-Request-Id 幂等", "服务端计价", "写订单/明细/幂等/outbox", "发起库存 Try", "处理支付、取消和超时"],
    coreFiles: [
      "shopsphere-order/src/main/java/com/shopsphere/order/controller/OrderController.java",
      "shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java",
      "shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java",
      "shopsphere-order/src/main/java/com/shopsphere/order/statemachine/OrderStatusTransitionValidator.java"
    ],
    calls: ["Order -> Product Feign", "Order -> MySQL", "Order -> RabbitMQ", "RabbitMQ timeout -> OrderTimeoutConsumer"],
    interview: "Order 是交易链路编排者，面试要围绕幂等、服务端计价、库存预留和 outbox 同事务展开。",
    position: { x: 480, y: 210 },
    icon: "order",
    tech: ["Seata TCC", "Outbox", "RabbitMQ TTL"]
  },
  {
    id: "recommendation",
    name: "Recommendation",
    accent: "purple",
    role: "行为消费、ItemCF 训练、在线召回和冷启动 fallback",
    responsibilities: ["消费行为和订单事件", "落自有 behavior_event 库", "训练 ItemCF 相似度", "Redis 存 sim/hot", "冷启动返回热门商品"],
    coreFiles: [
      "shopsphere-recommendation/app/consumer/behavior_consumer.py",
      "shopsphere-recommendation/app/service/itemcf.py",
      "shopsphere-recommendation/app/service/recall.py",
      "shopsphere-recommendation/app/tasks/train_job.py"
    ],
    calls: ["RabbitMQ -> Recommendation", "Recommendation -> MySQL", "Recommendation -> Redis", "Gateway -> Recommendation API"],
    interview: "推荐要讲成工程闭环：不跨库读数据，通过 MQ 建自己的行为库，模型未就绪时有热门商品 fallback。",
    position: { x: 690, y: 210 },
    icon: "recommendation",
    tech: ["FastAPI", "ItemCF", "Redis", "RabbitMQ"]
  }
];

export const architectureEdges: ArchitectureEdge[] = [
  { from: "gateway", to: "user", kind: "sync", label: "route" },
  { from: "gateway", to: "product", kind: "sync", label: "route" },
  { from: "gateway", to: "order", kind: "sync", label: "route" },
  { from: "gateway", to: "recommendation", kind: "sync", label: "route" },
  { from: "order", to: "product", kind: "sync", label: "Feign · TCC" },
  { from: "user", to: "recommendation", kind: "async", label: "MQ · behavior" },
  { from: "order", to: "recommendation", kind: "async", label: "MQ · order events" }
];

export const requestSteps: RequestStep[] = [
  {
    id: "entry",
    label: "1. Gateway 入站",
    actor: "Gateway",
    summary: "请求带 X-Request-Id 和 JWT 进入 Gateway，Gateway 负责 traceId、验签和用户上下文注入。",
    why: "统一入口减少下游重复鉴权，并避免外部伪造用户 header。",
    evidence: [
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java",
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/JwtAuthFilter.java"
    ],
    accent: "blue"
  },
  {
    id: "idempotency",
    label: "2. 幂等检查",
    actor: "Order",
    summary: "Order 根据 userId 和 requestId 查询幂等记录，避免客户端重试创建重复订单。",
    why: "下单接口天然会被重试，幂等表是交易入口的第一道保险。",
    evidence: [
      "shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java",
      "shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql"
    ],
    accent: "green"
  },
  {
    id: "price",
    label: "3. 查商品并计价",
    actor: "Order -> Product",
    summary: "Order 通过 Product Feign 查询商品信息，并在服务端计算订单金额。",
    why: "价格不能信任客户端传入，服务端计价能抵御篡改。",
    evidence: [
      "shopsphere-api/product-api/src/main/java/com/shopsphere/api/product/ProductFeignClient.java",
      "shopsphere-order/src/main/java/com/shopsphere/order/service/OrderServiceImpl.java"
    ],
    accent: "cyan"
  },
  {
    id: "persist",
    label: "4. 本地事务写入",
    actor: "Order + MySQL",
    summary: "Order 写订单、明细、幂等记录和本地消息表。",
    why: "订单事实和消息意图处在同一个本地事务，避免订单成功但事件丢失。",
    evidence: [
      "shopsphere-order/src/main/java/com/shopsphere/order/service/OrderPersistServiceImpl.java",
      "shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql"
    ],
    accent: "green"
  },
  {
    id: "stock",
    label: "5. 库存 Try",
    actor: "Product + Redis + MySQL",
    summary: "Product 先用 Redis Lua 原子预扣，再用 DB 条件更新锁定库存并记录 TCC 日志。",
    why: "Redis 扛热点入口，DB/TCC 日志保证事实和异常恢复语义。",
    evidence: [
      "shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java",
      "shopsphere-product/src/main/resources/scripts/stock_prededuct.lua"
    ],
    accent: "orange"
  },
  {
    id: "events",
    label: "6. 事件投递",
    actor: "Order -> RabbitMQ",
    summary: "LocalMessagePublisher 扫描 outbox 并投递到 RabbitMQ，publisher confirm 更新消息状态。",
    why: "可靠消息中继让积分、通知、推荐、超时取消不依赖下单线程直接发送 MQ。",
    evidence: ["shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java", "docs/mq-topology.md"],
    accent: "purple"
  }
];

export const interviewQuestions: InterviewQuestion[] = [
  {
    question: "为什么不用纯数据库扣库存？",
    answer: "热点商品只靠 DB 条件更新会把竞争压到数据库行锁上。当前方案用 Redis Lua 做入口原子预扣，DB 记录可售/锁定库存和 TCC 日志，Try/Confirm/Cancel 明确维护一致性。",
    avoid: "不要说 Redis 扣了就一定成功；还要讲 DB 条件更新失败后的回补和 TCC 日志。",
    evidence: [
      "shopsphere-product/src/main/java/com/shopsphere/product/service/StockRedisService.java",
      "shopsphere-product/src/main/java/com/shopsphere/product/service/StockTccServiceImpl.java"
    ]
  },
  {
    question: "为什么下单不直接发 MQ？",
    answer: "直接发 MQ 会出现订单入库成功但消息发送失败，或消息发出但事务回滚的割裂。outbox 把订单和消息意图放进同一个本地事务，再由中继异步投递。",
    avoid: "不要说有 RabbitMQ 就天然可靠；要讲本地消息表、publisher confirm、消费者幂等和 stale SENT 边界。",
    evidence: [
      "shopsphere-order/src/main/java/com/shopsphere/order/messaging/LocalMessagePublisher.java",
      "shopsphere-order/src/main/resources/db/migration/V20260521_1000__init_order.sql",
      "docs/mq-topology.md"
    ]
  },
  {
    question: "推荐服务为什么不直接查 User/Order 库？",
    answer: "推荐服务保持独立数据边界，通过 RabbitMQ 消费行为和订单事件，落自己的 behavior_event 库，再训练 ItemCF 并写 Redis。这样不会破坏微服务每服务一库边界。",
    avoid: "不要把它讲成复杂推荐平台；更准确的定位是工程型推荐闭环。",
    evidence: [
      "shopsphere-recommendation/app/consumer/behavior_consumer.py",
      "shopsphere-recommendation/app/service/itemcf.py",
      "shopsphere-recommendation/app/service/recall.py"
    ]
  },
  {
    question: "业务服务不验 JWT 安全吗？",
    answer: "安全前提是外部流量只能进 Gateway。Gateway 会剥离外部用户 header、验签 JWT、重新注入可信用户上下文，并拒绝外部访问 /internal/**。",
    avoid: "不要说下游完全不需要安全；生产还需要网络隔离、mTLS 或内部 token 等补强。",
    evidence: [
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/RequestLogFilter.java",
      "shopsphere-gateway/src/main/java/com/shopsphere/gateway/filter/InternalAccessRejectFilter.java",
      "shopsphere-common/src/main/java/com/shopsphere/common/context/UserContextInterceptor.java"
    ]
  }
];
