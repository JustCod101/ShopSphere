# ShopSphere 基础设施（docker-compose.infra.yml）

T0.2 产出：本地一键拉起 Nacos / MySQL / Redis / RabbitMQ / Seata / Sentinel。
版本锁定：nacos `v2.3.2` · mysql `8.0.36` · redis `7.2-alpine` · rabbitmq `3.13` + delayed-plugin `3.13.0` · seata `1.8.0` · sentinel `1.8.8`。
端口/库名严格对齐 `docs/api-contracts.md §5`。

## 启动 / 停止

```bash
cp .env.example .env                                  # 首次：准备密钥
docker compose -f docker-compose.infra.yml up -d      # 一键启动（首次会 build rabbitmq）
docker compose -f docker-compose.infra.yml ps         # 查看健康状态
docker compose -f docker-compose.infra.yml down       # 停止（保留 ./data）
docker compose -f docker-compose.infra.yml down -v && rm -rf ./data   # 彻底清空重来
```

预期 `ps`：`mysql/nacos/redis/rabbitmq/seata-server/sentinel-dashboard` 均 `healthy`，
`seata-config-init` 为 `exited (0)`（一次性配置推送容器）。

## 启动顺序（强约束）

```
mysql(healthy) → nacos(healthy) → seata-config-init(成功退出) → seata-server
redis / rabbitmq / sentinel-dashboard 独立并行
```

## 健康检查 URL / 账号

| 组件 | 地址 | 账号 |
|---|---|---|
| Nacos 控制台 | http://localhost:8848/nacos | dev 关闭鉴权（无需登录） |
| RabbitMQ 管理台 | http://localhost:15672 | `${RABBITMQ_USER}` / `${RABBITMQ_PASSWORD}`，vhost `shopsphere` |
| Sentinel Dashboard | http://localhost:8858 | `sentinel` / `sentinel` |
| Seata 控制台 | http://localhost:7091 | `seata` / `seata` |
| MySQL | localhost:3306 | `root` / `${MYSQL_ROOT_PASSWORD}` |
| Redis | localhost:6379 | 无密码（dev） |

库：`shopsphere_user` / `shopsphere_product` / `shopsphere_order` / `shopsphere_reco`（推荐自有库，§7）/ `shopsphere_seata` / `nacos_config`。

## Apple Silicon (arm64) 说明

`nacos/nacos-server:v2.3.2`、`seataio/seata-server:1.8.0`、`bladex/sentinel-dashboard:1.8.8`
无 arm64 镜像，已在 compose 中对这些服务声明 `platform: linux/amd64`，由 Docker Desktop
amd64 模拟运行（首启稍慢，功能正常）。mysql/redis/rabbitmq 为原生多架构。

## 故障处理

### Nacos 首启慢 → Seata 配置推送 / 注册失败

Nacos 首次连 MySQL 建表 + 启动较慢；`seata-config-init` 已配 `restart: on-failure` 会自动重试。
若 `seata-config-init` 或 `seata-server` 仍失败：

```bash
docker compose -f docker-compose.infra.yml logs nacos | tail -50      # 确认 nacos 已 readiness
docker compose -f docker-compose.infra.yml restart seata-config-init  # 重推配置
docker compose -f docker-compose.infra.yml restart seata-server       # 重启 Seata
```

排查 Seata `store.mode=db` 是否生效：Nacos 控制台「配置管理」group=`SEATA_GROUP` 下应有
`store.mode` / `store.db.url` 等逐 key 配置项；缺失则说明 `seata-config-init` 未成功，重跑上面命令。

### MySQL 已初始化但改了 init SQL 不生效

`/docker-entrypoint-initdb.d/*.sql` 仅在数据目录为空时执行。改了 `scripts/*.sql` 需
`down -v && rm -rf ./data/mysql` 后重启。

## RabbitMQ 延迟插件验证（M4）

延迟消息 `order.payment.timeout`（未支付超时取消）依赖 `rabbitmq_delayed_message_exchange`：

```bash
docker exec shopsphere-rabbitmq rabbitmq-plugins list -e        # 应见 rabbitmq_delayed_message_exchange [E*]
docker exec shopsphere-rabbitmq rabbitmqctl list_exchanges name type
```

或管理台 → Exchanges → Add exchange，Type 下拉出现 **`x-delayed-message`** 即插件就绪。
插件 `.ez` 已 vendor 至 `rabbitmq/plugins/`（版本匹配 broker 3.13），离线可复现。

## 生产改造提示（dev 默认关闭）

- **Redis**：`docker-compose.infra.yml` redis `command` 追加 `--requirepass ${REDIS_PASSWORD}`，`.env` 补 `REDIS_PASSWORD`。
- **Nacos**：`NACOS_AUTH_ENABLE=true` 并补 `NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE`。
- **Sentinel**：通过环境变量改默认 `sentinel/sentinel`。
- 所有口令仅存 `.env`（已 gitignore），`.env.example` 为占位模板；业务服务 Jasypt 解密复用同一 `JASYPT_ENCRYPTOR_PASSWORD`（§10）。

## 第三方 vendor 产物（离线可复现）

| 文件 | 来源 |
|---|---|
| `scripts/02-nacos-mysql-schema.sql` | alibaba/nacos `2.3.2` `distribution/conf/mysql-schema.sql`（仅本地加一行 `USE nacos_config;`） |
| `scripts/03-seata-server.sql` | apache/incubator-seata `v1.8.0` `script/server/db/mysql.sql`（仅本地加一行 `USE shopsphere_seata;`） |
| `scripts/seata-nacos-config.sh` | apache/incubator-seata `v1.8.0` `script/config-center/nacos/nacos-config.sh`（未改动） |
| `rabbitmq/plugins/rabbitmq_delayed_message_exchange-3.13.0.ez` | rabbitmq/rabbitmq-delayed-message-exchange release `v3.13.0` |
