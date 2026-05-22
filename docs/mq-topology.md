# MQ 拓扑（T3.4）

强可靠 MQ 链路落地说明。依据 `docs/api-contracts.md` §7 / §8 与 M4 可靠性分级。

**日期**:2026-05-22 · **Broker**:RabbitMQ 3.13（镜像 `shopsphere/rabbitmq:3.13-delayed`，vhost `shopsphere`）

---

## 1. Exchange / Queue / Binding 全量

### Exchanges

| 名称 | 类型 | durable | 声明方 | 用途 |
|---|---|---|---|---|
| `shopsphere.order` | topic | 是 | Order（User 幂等重声明） | 订单事件主交换机 |
| `shopsphere.order.dlx` | fanout | 是 | Order（User 幂等重声明） | 订单链路死信交换机（无视 rk 汇聚） |
| `shopsphere.behavior` | topic | 是 | User | 行为埋点（T1.4 既有，本任务不动） |

### Queues

| 队列 | durable | DLX 参数 | 声明方 | 消费者 |
|---|---|---|---|---|
| `q.points` | 是 | `x-dead-letter-exchange=shopsphere.order.dlx` | User | User · `PointsConsumer` |
| `q.notify` | 是 | `x-dead-letter-exchange=shopsphere.order.dlx` | User | User · `NotificationConsumer` |
| `q.order.timeout.wait` | 是 | `x-message-ttl=30min`、`x-dead-letter-exchange=""`、`x-dead-letter-routing-key=q.order.timeout` | Order | 无（TTL 等待队列，T3.5） |
| `q.order.timeout` | 是 | `x-dead-letter-exchange=shopsphere.order.dlx` | Order | Order · `OrderTimeoutConsumer`（T3.5） |
| `q.order.dlq` | 是 | — | Order（User 幂等重声明） | 无（人工运维 / 后续补偿） |
| `q.reco.behavior` | 是 | 由 Reco 决定 | Reco（Python） | Reco 行为消费者（本任务不涉） |

### Bindings

| 源 exchange | routing key | 目标 queue |
|---|---|---|
| `shopsphere.order` | `order.created` | `q.points` |
| `shopsphere.order` | `order.created` | `q.notify` |
| `shopsphere.order` | `order.created` | `q.reco.behavior`（Reco 自绑定） |
| `shopsphere.order` | `order.payment.timeout` | `q.order.timeout.wait` |
| `""`（默认交换机，TTL 死信） | `q.order.timeout`（队列名） | `q.order.timeout` |
| `shopsphere.order.dlx` | （fanout，无 rk） | `q.order.dlq` |
| `shopsphere.behavior` | `user.behavior` | `q.reco.behavior`（Reco 自绑定） |

**声明归属原则**:「消费方拥有队列」—— 生产方声明 exchange，消费方声明并绑定自己的队列。
`shopsphere.order` / `shopsphere.order.dlx` / `q.order.dlq` 由 Order 主声明，User 侧
`OrderEventRabbitConfig` 幂等重声明（type/durable 一致即安全），消除启动顺序耦合。

---

## 2. 生产链路（🔴 强可靠 · order.*）

```
POST /api/order/create  (@GlobalTransactional)
  └─ persistOrder：t_order + t_order_item + t_local_message×2 + t_order_request   ← 同一本地事务（C3）
        t_local_message：order.created (PENDING) / order.payment.timeout (PENDING)

LocalMessagePublisher.relay()  @Scheduled(5s) + @Transactional
  ├─ Redisson tryLock(lock:order:outbox)            ← 多实例第二层防重
  ├─ selectPendingBatch  ... FOR UPDATE SKIP LOCKED ← 行级互斥，行锁持有至 commit
  ├─ rabbitTemplate.send(exchange, rk, payload字节, CorrelationData=消息id)
  └─ markSent (status 0→1)

ConfirmCallback (broker 异步)
  ├─ ack=true  → markConfirmed (1→2)
  └─ ack=false → retry_count+1；< maxRetry(5)：markRetry(回 0，next_retry_at=now+2^rc 秒，指数退避)
                                  ≥ maxRetry：markFailed (→3) + ERROR 告警
```

`t_local_message.status`:`0=PENDING` `1=SENT` `2=CONFIRMED` `3=FAILED`。

投递的是 `t_local_message.payload` 中已序列化的 JSON 字节（`rabbitTemplate.send`，
不二次序列化）。`order.created` payload = 共享 `OrderCreatedEvent`（`order-api`），
字段 `orderId, orderNo, userId, totalAmount, ts, items[]`。

---

## 3. 消费者幂等键

| 消费者 | 队列 | 幂等键 | 机制 |
|---|---|---|---|
| `PointsConsumer` | `q.points` | `t_points_log.order_id`（UNIQUE `uk_order`） | 幂等键 INSERT 与积分累加同一 `@Transactional`；重复投递 → `DuplicateKeyException` → 整事务回滚、幂等 ack |
| `NotificationConsumer` | `q.notify` | Redis `notify:sent:{orderNo}` | `SET NX EX 86400`；已存在即跳过。Redis 不可用时 fail-open（本期仅日志，重复无害） |
| Reco 行为消费者 | `q.reco.behavior` | `BehaviorEvent.eventId` | Reco 服务侧实现 |

所有 `order.*` 消费者 **手动 ack**（`acknowledge-mode: manual`），消费者内显式 `basicAck` /
`basicNack`，不依赖容器自动重试。

---

## 4. 死信处理流程

```
消费失败（可重试：BusinessException / DataAccessException）
  └─ MqConsumerSupport.retryOrDeadLetter()
       Redis INCR mq:retry:{scope}:{orderId}  (EX 10min)
       attempts <  3 → basicNack(requeue=true)   ← requeue 重投，再次消费
       attempts >= 3 → basicNack(requeue=false)  ← 转 DLX + ERROR 告警
  Redis 不可用（INCR 返回 null）→ 直接转 DLX（宁可入死信，不让 retry 失控）

消费失败（不可恢复 / 毒消息）
  └─ basicNack(requeue=false) 直接转 DLX

basicNack(requeue=false)
  └─ 队列 x-dead-letter-exchange → shopsphere.order.dlx (fanout) → q.order.dlq
```

毒消息（JSON 反序列化失败）由容器 `ConditionalRejectingErrorHandler` 在 listener 调用前
即判定为 fatal 并拒绝转 DLX。

`q.order.dlq` 本期无自动消费者:由运维监控告警 + 人工排查;自动补偿属后续。

---

## 5. 可靠性分级（M4，§8）与本期边界

| 维度 | 🔴 强可靠（`order.*`） | 🟡 轻量（`user.behavior`） |
|---|---|---|
| 生产 | 本地消息表（C3）+ 定时中继 + publisher-confirm | 直发（`@Async`），`mandatory` + confirm 仅告警 |
| 丢消息 | 不丢（订单与消息同事务，confirm 退避重试） | 可容忍丢失（埋点量大，不付重型一致性成本） |
| 消费 | 手动 ack + 幂等 + DLX 兜底 | 消费侧幂等去重（Reco 侧） |
| 失败补偿 | confirm 退避重试 / 消费重试 3 次转 DLX | 不补偿，仅记 WARN（fail-open） |

**Part D 核对**:T1.4 `user.behavior` 直发路径行为不变 —— `BehaviorEventPublisher`（`@Async` 直发）
与 `RabbitConfig` 的 `ConfirmCallback`（nack 仅 WARN，🟡 fail-open）语义保持。User 新增的
`OrderEventRabbitConfig` 与既有 `RabbitConfig` 是两套独立 exchange，互不干扰。

**演练中发现并修复的缺陷**:T3.4 的消费者集成测试是 User 应用首次以 `@SpringBootTest` 全上下文启动，
暴露 T1.4 `RabbitConfig` 一处自动装配循环引用 —— 该 `@Configuration` 构造注入 `RabbitTemplate`，
又同时产出 `RabbitTemplate` 自动配置所需的唯一 `MessageConverter` Bean，经 `@Bean` 工厂方法成环
（`BeanCurrentlyInCreationException`，全上下文启动即失败）。此前 User 仅有 `@WebMvcTest` 切片测试
（排除 `@Configuration`），故缺陷一直潜伏。**已修复（经用户确认）**:把 `mqMessageConverter` 抽到
独立的 `MqConverterConfig`（只依赖 `ObjectMapper`，与 `RabbitTemplate` 无关），环从结构上消除；
`RabbitConfig` 仅保留 exchange 声明与 confirm/returns 回调装配。

**未支付超时延迟（T3.5，TTL+DLX）：**
`order.payment.timeout` 经 outbox 投到 `shopsphere.order` → 路由进 `q.order.timeout.wait`
（`x-message-ttl` = 支付超时窗口、**无消费者**）→ 消息 TTL 到期后经 `x-dead-letter-routing-key`
死信到默认交换机、按队列名投入 `q.order.timeout` → `OrderTimeoutConsumer` 触发系统取消
（仅取消仍为 `CREATED` 的订单）。用原生 RabbitMQ TTL+DLX，不依赖延迟插件（§8 二选一）。

**本期边界:**
- **stale SENT 行**:消息已 `send` 但 confirm 永不到达（如 JVM 在 send 与 confirm 之间崩溃）→
  行卡在 `SENT`。中继任务只扫 `PENDING`,本期不回收;后续可加「`SENT` 且 `updated_at`
  超阈值 → 回 `PENDING`」清扫。
- `q.order.timeout.wait` 的 `x-message-ttl` 在队列声明期固定；改超时窗口需重建队列（RabbitMQ
  队列参数不可变更）。
- `q.order.dlq` 无自动消费;死信靠人工运维。
