# shopsphere-order

## 职责

订单子域。负责:
- **下单**(写订单 + Try 库存 + 投本地消息表,全 `@GlobalTransactional`)
- **支付**(状态机 `CREATED → PAID` + Confirm 库存)
- **取消**(用户主动 / 30 min 延迟自动 → Cancel 库存)
- **本地消息表**(outbox + publisher-confirm + 退避重试,详见 [ADR-007](../docs/adr/ADR-007-local-message-table.md))
- **延迟取消**(RabbitMQ `x-delayed-message` exchange,30 min 默认,可配)
- **TCC 发起方**(向 product 服务的内部接口调 Try/Confirm/Cancel)

## 端口 / 依赖

| 项 | 值 |
|---|---|
| 端口 | **8083**(`ORDER_PORT`) |
| Sentinel transport | 8722 |
| 数据库 | `shopsphere_order`(MySQL) |
| 依赖 | Nacos、MySQL、Redis、RabbitMQ(强制延迟插件)、Seata TC、`shopsphere-product`(Feign) |

## 本地启动

```bash
# 1. 起基础设施(必备)
docker compose up -d nacos mysql redis rabbitmq seata sentinel

# 2. 起 product 服务(TCC 下游)
docker compose up -d shopsphere-product

# 3. 启动 Order
cd shopsphere-order && mvn spring-boot:run
```

## 关键配置(application.yml)

| key | 说明 |
|---|---|
| `seata.tx-service-group` | `default_tx_group`(与 server vgroupMapping 一致) |
| `order.payment.timeout-ms` | 延迟取消 TTL(默认 1800000=30 min,T5.2 引入可配) |
| `order.outbox.scan-interval-ms` | 本地消息表扫描间隔(默认 5000) |
| `order.outbox.batch-size` | 单次扫描批量(默认 100) |
| `spring.rabbitmq.publisher-confirm-type` | `correlated`(必须;outbox 状态依此回调更新) |
| `spring.flyway.locations` | `classpath:db/migration` |

Nacos dataIds:`order.yml` / `common-config.yml` / `sentinel-rules-order-flow.json`

## 核心代码导航

| 路径 | 说明 |
|---|---|
| `OrderApplication.java` | 启动类 + `@EnableFeignClients(basePackages="com.shopsphere.api")` |
| `controller/OrderController.java` | `POST /create, /pay, /cancel`;`X-Request-Id` 必填(防重复下单) |
| `service/OrderService(Impl).java` | 业务编排;`@GlobalTransactional`(Seata) |
| `service/OrderStatusMachine.java` | 状态转换约束 |
| `messaging/LocalMessagePublisher.java` | outbox 扫描器 + publisher-confirm 回调 |
| `messaging/OrderDelayMessageListener.java` | 消费 `order.payment.timeout` → 调 cancel |
| `mapper/*.java` | t_order / t_order_item / t_order_request(幂等)/ t_local_message |
| `db/migration/V20260521_1000__init_order.sql` | 表结构(含 outbox 表行 42) |

## 测试

```bash
# 单测
mvn -pl shopsphere-order test

# 端到端(下单全链路)
mvn -pl shopsphere-e2e-test test -Dtest=D_OrderCreateIdempotentTest,E_OrderPayConfirmTest,F_OrderCancelTest,G_OrderTimeoutTest

# 压测
bash perf/run-all.sh
```

## 关联文档

- TCC 设计:[ADR-003](../docs/adr/ADR-003-stock-seata-tcc.md) / [ADR-004](../docs/adr/ADR-004-order-tcc-lifecycle.md)
- 本地消息表:[ADR-007](../docs/adr/ADR-007-local-message-table.md)
- MQ 拓扑:[docs/mq-topology.md](../docs/mq-topology.md)
- TCC 失败回归:[docs/tcc-rollback-report.md](../docs/tcc-rollback-report.md)
- 压测:[perf/README.md](../perf/README.md) / [docs/perf-tcc-report.md](../docs/perf-tcc-report.md)
- 排障:[docs/troubleshooting.md](../docs/troubleshooting.md) §2 / §3 / §5
