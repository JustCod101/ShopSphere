# shopsphere-e2e-test

## 职责

端到端测试套件(T5.2)。**不进 reactor 主模块**,独立 maven 子项目,只在压测/发布前手动执行。

覆盖路径(命名前缀 `A_` ~ `K_` 强制执行顺序):

| 编号 | 测试类 | 覆盖 |
|---|---|---|
| A | `A_AuthFlowTest` | 注册 → 登录 → me → 越权拦截 |
| B | `B_ProductBrowseTest` | 商品详情 + 缓存命中 + 库存可见 |
| C | `C_BehaviorPipelineTest` | 行为埋点 → MQ → 推荐侧入库 |
| D | `D_OrderCreateIdempotentTest` | 下单 + `X-Request-Id` 幂等(重发同请求返同 orderId) |
| E | `E_OrderPayConfirmTest` | 支付 → TCC.Confirm → 库存 locked 清零 |
| F | `F_OrderCancelTest` | 取消 → TCC.Cancel → 库存回补 + Redis 同步 |
| G | `G_OrderTimeoutTest` | 30 min 延迟(测试时缩到 1 min)自动取消 |
| H | `H_OrderForbiddenTest` | 取消他人订单返 403/4001 |
| I | `I_RecommendColdStartTest` | 新用户无行为 → fallback 热门 top-N |
| J | `J_RecommendWarmTest` | 有行为后 → 个性化推荐返不同 |
| K | `K_GatewaySecurityTest` | 无 token / 伪造 header / 限流 / 内部接口拒绝 |

## 端口 / 依赖

| 项 | 值 |
|---|---|
| 端口 | 无(纯测试) |
| 依赖运行栈 | 完整 docker compose(Nacos + 4 Java + 1 Py + Redis + RabbitMQ + Seata + Sentinel) |
| 验证手段 | HTTP(ApiClient)+ JDBC(DbFixtures)+ Redis(RedisClient)+ RabbitMQ Mgmt API(MqAdminClient) |

## 本地启动

```bash
# 0. 起完整栈
cp .env.example .env
docker compose --profile java --profile python up -d --build
bash scripts/wait-stack-healthy.sh

# 1. 缩短 30 min 延迟到 1 min(避免 G_OrderTimeoutTest 等 30 分钟)
bash scripts/e2e-set-timeout.sh 60000

# 2. 跑全套
mvn -pl shopsphere-e2e-test test

# 3. 跑单个
mvn -pl shopsphere-e2e-test test -Dtest=D_OrderCreateIdempotentTest
```

## 关键配置(`src/test/resources/e2e.properties`)

| key | 说明 |
|---|---|
| `gateway.url` | 默认 `http://localhost:8080` |
| `mysql.url / user / password` | 直连 MySQL 做断言(读 t_order / t_stock_tcc_log) |
| `redis.host / port` | 验证 stock:product:* |
| `rabbitmq.mgmt.url` | http://localhost:15672(检查队列消息数) |
| `e2e.order.payment.timeout-ms` | 1 分钟(覆盖默认 30 分钟) |
| `e2e.fail-fast` | true(任一失败立停) |

## 关键代码导航

| 路径 | 说明 |
|---|---|
| `support/E2eBase.java` | 测试基类 + 全局 fixture |
| `support/ApiClient.java` | HTTP 工具(自动带 token + X-Request-Id) |
| `support/UserFactory.java` | 测试用户工厂(register + login,缓存 token) |
| `support/DbFixtures.java` | MySQL 直连断言 + reset |
| `support/RedisClient.java` | Redis 读写校验 |
| `support/MqAdminClient.java` | RabbitMQ Management API(读队列计数 / 消息内容) |
| `support/Awaits.java` | 异步消息消费的轮询 + 超时 |
| `support/LogDumper.java` | 失败时 dump 服务日志 |
| `ReportListener.java` | JUnit 5 监听器,生成 [docs/e2e-report.md](../docs/e2e-report.md) |
| `E2eConfig.java` | properties 读取 |

## 测试

E2E 自身就是测试,见 §本地启动。

## 关联文档

- 既有报告:[docs/e2e-report.md](../docs/e2e-report.md)
- API 契约:[docs/api-contracts.md](../docs/api-contracts.md)
- 压测:[perf/README.md](../perf/README.md)(JMeter 是 E2E 的高并发延伸)
