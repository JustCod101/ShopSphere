# shopsphere-recommendation

ShopSphere 推荐服务（Python 3.11 + FastAPI），对应 `docs/architecture.md` §2.4 与 `docs/api-contracts.md` §6.4 / §7 / §8。

本 README 描述 **T4.1（骨架）**：FastAPI 项目脚手架、Nacos 注册与配置、MQ 行为消费者、`Result` 同构、Alembic 迁移、错误码与中间件。**不含**算法实现（ItemCF 训练在 T4.2，在线召回/冷启动兜底在 T4.3，Gateway 路由在 T4.4）。

---

## 一、目录结构

```
shopsphere-recommendation/
├── app/
│   ├── main.py                       # FastAPI 入口 + lifespan
│   ├── api/                          # 接口骨架（recommend / health）
│   ├── core/                         # config / result / error_code / exceptions / nacos / db / redis
│   ├── middleware/                   # UserContextMiddleware / 全局异常 handler
│   ├── consumer/behavior_consumer.py # MQ 行为消费者（双绑 user.behavior + order.created）
│   ├── schemas/events.py             # MQ payload Pydantic 模型
│   ├── models/behavior.py            # SQLAlchemy ORM (behavior_event / t_train_log)
│   ├── service/                      # 留给 T4.2/T4.3（itemcf / recall / feature_loader）
│   └── tasks/                        # 留给 T4.2（train_job）
├── alembic/                          # 迁移脚本（建表 behavior_event / t_train_log）
├── tests/                            # 单元测试（17 passed）
├── requirements.txt
├── requirements-dev.txt
└── Dockerfile
```

---

## 二、本地开发

### 1. 基础设施

启动 Nacos / MySQL / Redis / RabbitMQ（根目录 `docker-compose.infra.yml`）：

```bash
docker compose -f docker-compose.infra.yml up -d
```

在 Nacos 控制台（`http://127.0.0.1:8848/nacos`）发布两个配置（`DEFAULT_GROUP`）：

- `shopsphere-recommendation.yaml`（公共，含 `mysql/redis/rabbitmq/model/server_port` 等非机密项）
- `shopsphere-recommendation-{profile}.yaml`（如 `-dev`，覆盖账号口令等机密项）

profile 覆盖语义：`profile_cfg` 覆盖 `public_cfg`。

### 2. Python 环境

```bash
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
```

### 3. 数据库迁移

`shopsphere_reco` 库为推荐服务**自有库**（契约 §7），不与其他服务共用。迁移由环境变量提供 DSN：

```bash
export ALEMBIC_SQLALCHEMY_URL="mysql+pymysql://shopsphere:****@127.0.0.1:3306/shopsphere_reco?charset=utf8mb4"
# 或拆分变量：MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
alembic upgrade head
```

迁移产物：`behavior_event`（唯一索引 `uk_event_id`，复合索引 `idx_user_ts` / `idx_item_ts`）、`t_train_log`。

### 4. 启动

```bash
export NACOS_SERVER=127.0.0.1:8848
export NACOS_NAMESPACE=                  # 可空
export APP_PROFILE=dev
export SERVER_PORT=8000
export NACOS_REGISTER_IP=127.0.0.1       # 可选；缺省按主机名解析，开发兜底 127.0.0.1
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

启动顺序（`lifespan`）：日志 → Nacos 拉公共/profile 配置 → AppSettings → DB Engine → Redis → BehaviorConsumer.start → Nacos 注册。关停反向：deregister → consumer.stop → redis.aclose → engine.dispose。

---

## 三、对外接口

以 `docs/api-contracts.md` §6.4 为唯一标准：

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/recommend/user/{userId}?topk=10` | A | 个性化召回；冷启动回退热门 |
| GET | `/api/recommend/similar/{itemId}?topk=10` | P | i2i 相似商品 |
| POST | `/internal/recommend/train` | 内部 | 手动触发离线训练 |
| GET | `/api/recommend/health` | P | 健康检查（含 `model_ready` 标志） |

T4.1 仅返回 `Result` 骨架（`items=[]`、`fallback=true`），真实召回逻辑在 T4.2 / T4.3 落地。

错误码契约：`code=0 + data.fallback=true` 表示冷启动 / 模型未就绪（C1 拍板）；`5001 / 5002` 仅用于监控埋点，**不进 `Result.code`**。

---

## 四、MQ 拓扑

完整拓扑见 `docs/mq-topology.md`。推荐服务自有的三件（启动期由 `BehaviorConsumer` 声明）：

| 资源 | 类型 | 备注 |
|---|---|---|
| `shopsphere.reco.dlx` | fanout exchange | 推荐链路死信交换机，独立于 `shopsphere.order.dlx` |
| `q.reco.behavior` | queue | 主消费队列；`x-dead-letter-exchange=shopsphere.reco.dlx`；双绑 `shopsphere.behavior:user.behavior` + `shopsphere.order:order.created` |
| `q.reco.behavior.dlq` | queue | 死信队列，人工运维 |

幂等键派生规则：

- `user.behavior` → 直接用 payload 自带 `eventId`（User 侧 32hex UUID）
- `order.created` → 展开为 N 条，`eventId = f"order-{orderId}-{productId}"`（确定性派生，重投天然幂等）

入库 SQL：`INSERT ... ON DUPLICATE KEY UPDATE event_id = event_id`（依赖 `uk_event_id`）。

失败语义：JSON 解析 / Pydantic 校验失败 → `reject(requeue=False)` 进 DLX；DB / Redis 失败 → `nack(requeue=False)` 进 DLX。不依赖容器自动重试。

---

## 五、测试

```bash
pytest tests/ -q
# 17 passed
```

覆盖：`Result` 结构与 timestamp 格式、错误码号段、`UserContextMiddleware` 受保护路径与 traceId 注入、异常 handler 三类映射（BusinessException / ValidationError / 未捕获）、消费者纯函数（`normalize_user_behavior` / `normalize_order_created` 与 eventId 派生）。

真实 broker / DB / Nacos 的集成测试留待 T4.2 / T4.3 配合算法落地。

---

## 六、后续待办

| 任务 | 范围 |
|---|---|
| T4.2 | 离线 ItemCF 训练（pandas + scipy 稀疏矩阵 + 余弦相似 + 热门惩罚），`t_train_log` 状态机，写 Redis `sim:item:{id}` zset |
| T4.3 | 在线召回 `/api/recommend/user`、`/api/recommend/similar` 真实实现；冷启动热门 Top-N 兜底（`data.fallback=true`） |
| T4.4 | Gateway 路由 `/api/recommend/**` 接入；前端经 Gateway 直连 Python（C2，不走 Java Feign） |
