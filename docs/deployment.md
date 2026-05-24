# ShopSphere 部署指南(T5.1)

> 一键起全栈:`cp .env.example .env && docker compose up --build -d`。
> 适用范围:本地开发、CI 集成测试、生产部署(差异见 §5)。
> 端口/库名权威源:`docs/api-contracts.md` §5。

---

## 1. 总览

一条命令拉起 12 个容器:7 个基础设施 + 4 个 Java 业务 + 1 个 Python 推荐。

```
                  ┌─ shopsphere-gateway (8080)
                  ├─ shopsphere-user (8081)
   宿主机 → 网关 ──┼─ shopsphere-product (8082)     ←─ Nacos / Sentinel / Seata
                  ├─ shopsphere-order (8083)
                  └─ shopsphere-recommendation (8000)
                          ↑
                          └─ MySQL / Redis / RabbitMQ
```

期望耗时:**首次 build ~10-15 分钟**(maven 拉依赖);**二次起 ~3-5 分钟**(全部 healthy)。

---

## 2. 目录结构

```
.
├── docker-compose.yml              # 全栈(infra + 5 业务)— 本期新增
├── docker-compose.infra.yml        # 仅 infra(保留,二选一)
├── .env                            # 本地实例配置(.gitignore 屏蔽)
├── .env.example                    # 模板
├── logs/                           # 各服务日志挂载(容器写,宿主读)
│   ├── gateway/   user/   product/   order/   recommendation/
├── data/                           # 基础设施持久化(.gitignore 屏蔽)
│   ├── mysql/   nacos/logs/   redis/   rabbitmq/
├── shopsphere-{gateway,user,product,order}/Dockerfile  # Java 多阶段
├── shopsphere-recommendation/Dockerfile + entrypoint.sh  # Python
└── rabbitmq/Dockerfile             # RabbitMQ + 延迟插件
```

---

## 3. 本地开发

### 3.1 完整本地(容器跑全部)

```bash
cp .env.example .env       # 首次
docker compose up --build -d
```

`.env` 默认 `COMPOSE_PROFILES=java,python` → 全栈启动。

### 3.2 仅 infra(IDE 跑业务,常用调试场景)

两种等价做法:

```bash
# 法 A:覆盖 profile
COMPOSE_PROFILES= docker compose up -d

# 法 B:用 infra 专用 compose 文件
docker compose -f docker-compose.infra.yml up -d
```

IDE 启动 Java 服务时:`NACOS_HOST=localhost`(默认即可)、JASYPT_ENCRYPTOR_PASSWORD 经 IDE Run Configuration 注入。

### 3.3 仅 infra + Java(Python IDE 调试)

```bash
COMPOSE_PROFILES=java docker compose up -d
```

Python 推荐服务用 IDE 跑,需要 `export NACOS_REGISTER_IP=host.docker.internal`(macOS)让 Gateway 容器能回连宿主机 Python。

### 3.4 仅 infra + Python(Java IDE 调试)

```bash
COMPOSE_PROFILES=python docker compose up -d
```

### 3.5 端口冲突

改 `.env` 中对应端口变量:`GATEWAY_PORT=18080` 等。容器内端口不变,只改宿主机映射。

### 3.6 重置数据

```bash
docker compose down -v        # 删容器 + 卷
rm -rf data/                  # 删持久化(MySQL/Redis/RabbitMQ)
```

⚠️ `rm -rf data/` 会丢光 MySQL 中所有库 + Nacos 配置 + RabbitMQ 队列。生产慎用。

---

## 4. CI 构建

GitHub Actions / GitLab CI 示例:

```yaml
- name: Build all images
  run: docker compose build

- name: Up & wait healthy
  run: |
    cp .env.example .env
    docker compose up -d
    timeout 600 bash -c 'until docker compose ps --format json | jq -e ".[] | select(.Health != \"healthy\") | length == 0"; do sleep 10; done'

- name: Smoke test
  run: |
    curl -fsS localhost:8080/actuator/health | grep '"status":"UP"'
    curl -fsS localhost:8000/api/recommend/health | grep '"status":"UP"'
    bash scripts/e2e-recommend.sh
```

**关键约束**(CI 友好):

- 镜像构建不依赖宿主机文件(build context = 项目根,COPY 显式)
- Maven 依赖缓存在 builder 镜像层,Docker layer 自动复用
- 不需要 `mvn install` 预热(Dockerfile 内构建)
- 不需要本地 Python venv

**性能调优**:

- 用 GitHub Actions cache 缓存 `~/.m2`(builder 间共享 maven repo)
- 大型 PR 可用 `docker buildx --cache-to type=gha` 跨 job 共享

---

## 5. 生产部署差异

| 项 | 本地/CI | 生产 |
|---|---|---|
| `ports:` 映射 | 全部暴露 | 仅 gateway 8080 暴露;其他经反代/网关内网调 |
| Redis 密码 | 无 | `REDIS_PASSWORD=xxx` + redis command `--requirepass ${REDIS_PASSWORD}` |
| JASYPT_ENCRYPTOR_PASSWORD | `please-change-me` | 强随机 32+ 位,经密钥管理服务注入 |
| 数据卷 | `./data/` 本地 | 云盘 / NAS / k8s PVC |
| 日志驱动 | json-file 默认无限制 | 加 `logging.options.max-size=100m max-file=5` |
| Nacos | standalone | 集群 + 鉴权 (`NACOS_AUTH_ENABLE=true`) |
| Sentinel | dev 控制台 | 持久化规则(MySQL 或 Nacos),HA |
| Seata | 单实例 | TC HA + Raft |
| JVM 内存 | 256m/512m | 按容器规格调 `JAVA_OPTS`(例:1g/2g) |
| 镜像 tag | `:latest` | 具体版本(`:1.2.3-abc1234`) |
| 平台 | macOS arm64(nacos/seata/sentinel 走 amd64 模拟) | linux/amd64 原生 |

---

## 6. NACOS_REGISTER_IP 详解

| 场景 | 设置 | 原因 |
|---|---|---|
| 本地 IDE 跑 Python | 不设 | Python 用 `socket.gethostbyname(hostname)` 解析为 `127.0.0.1`,Gateway 同在 host,可达 |
| 全容器(本指南默认) | 不设 | socket 解析为容器主机名(如 `shopsphere-recommendation`),docker 网络 DNS 可解,容器间可达 |
| host 跑 Python + 容器跑 Gateway | `NACOS_REGISTER_IP=host.docker.internal`(macOS)或宿主机 LAN IP | Gateway 在容器,需穿透到 host 上的 Python |
| K8s | 不设,或注入 pod IP `valueFrom.fieldRef.fieldPath=status.podIP` | Pod IP 自动分配 |
| 生产 docker swarm | 不设 | overlay 网络 DNS 可解 task 名 |

Python 端 `resolve_register_ip()` 优先级:env `NACOS_REGISTER_IP` > `socket.gethostbyname(socket.gethostname())` > `127.0.0.1`。

---

## 7. 故障排查

### 7.1 Java 服务启动 30s 报 Nacos 连不上

**原因**:Nacos `start_period=30s` 偶尔不够(尤其 arm64 amd64 模拟)。

```bash
docker compose logs nacos | tail -50  # 看 Nacos 是否真就绪
docker compose restart shopsphere-user
```

### 7.2 JASYPT 解密失败 `EncryptionOperationNotPossibleException`

`.env` 里的 `JASYPT_ENCRYPTOR_PASSWORD` 与 Nacos 上加密配置时使用的口令不一致。重新加密 Nacos 上的 ENC(...) 字段,或换 `.env`。

### 7.3 Python alembic 迁移失败

```bash
docker compose logs shopsphere-recommendation | grep -A 5 entrypoint
```

常见原因:
- `MYSQL_PASSWORD` 错(用的是 `MYSQL_ROOT_PASSWORD`)→ 改 .env
- `shopsphere_reco` 库不存在 → 先确认 `scripts/01-init-mysql.sql` 在 `./data/mysql` 重置后跑过

### 7.4 推荐返回 `${MYSQL_HOST:localhost}` 类字面值

老版本 nacos_client.py 不展开占位符。本期 T5.1 已修(`nacos_client.py:expand_env_placeholders`)。如果仍出现:
- 镜像没重新 build:`docker compose build shopsphere-recommendation`
- env 没传:`docker compose exec shopsphere-recommendation env | grep MYSQL_HOST`

### 7.5 端口被占

```bash
lsof -i :8080
```

改 `.env` 中 `GATEWAY_PORT=18080`(等),`docker compose up -d` 重起。

### 7.6 容器名冲突

`docker-compose.yml` 与 `docker-compose.infra.yml` 用相同 container_name → 不能同时跑。

```bash
docker compose -f docker-compose.infra.yml down
docker compose up -d
```

### 7.7 macOS arm64 慢

nacos / seata / sentinel `platform: linux/amd64` 模拟,启动 30~90s 是正常的。`start_period` 已经设宽。如果超时,检查 Docker Desktop 资源配额(`Settings → Resources` 加 CPU/RAM)。

### 7.8 E2E:`mvn -Pe2e verify` 立即失败

栈尚未 healthy。先跑 `bash scripts/wait-stack-healthy.sh`(5 分钟超时)再起 E2E。

### 7.9 E2E:case h 未通过 / `queueTtlMs 不是 30000ms`

RabbitMQ 队列 `q.order.timeout.wait` 的 `x-message-ttl` 一旦声明不可改。case h 跑前需:
```bash
bash scripts/e2e-set-timeout.sh    # 推 queueTtlMs=30000 到 Nacos + compose down -v + up -d
bash scripts/wait-stack-healthy.sh
mvn -f shopsphere-e2e-test/pom.xml -Pe2e,e2e-slow verify
```

---

## 8. E2E 测试(T5.2)

独立模块 `shopsphere-e2e-test`(**不进 reactor**),手动构建。

```bash
# 常规 13 个 case(不含 30s 超时)
mvn -f shopsphere-e2e-test/pom.xml -Pe2e verify

# 全量 15 个 case(含 case h:30s 超时自动取消)
bash scripts/e2e-set-timeout.sh
bash scripts/wait-stack-healthy.sh
mvn -f shopsphere-e2e-test/pom.xml -Pe2e,e2e-slow verify

# 看报告
cat docs/e2e-report.md
# 失败日志
ls target/e2e-logs/
```

**约束**:
- 栈必须先 healthy(`scripts/wait-stack-healthy.sh`)。
- DB 在 @BeforeEach 自动 truncate(保留商品/类目种子);**不要跑 E2E 时同时手动用 stack**。
- case h `@Tag("timeout")` 默认跳;需 `-Pe2e,e2e-slow` 才跑,且队列 TTL 须先压到 30000ms。

---

## 8.5 压测(T5.3)

下单链路 1000 并发对 500 库存,验证不超卖 + TCC 三段一致 + Redis/DB 一致。

```bash
# 一键(首次会预创建 1000 用户 ~2 分钟)
bash perf/run-all.sh

# 看产出
cat docs/perf-tcc-report.md         # 填模板
open perf/results/html-report/      # JMeter HTML 报告
cat perf/results/evidence.txt       # 三阶段一致性证据
open http://localhost:3000/d/order-perf   # Grafana 看板(admin/admin123)
```

**约束**:
- 栈必须先 healthy(`scripts/wait-stack-healthy.sh`)。
- `prepare-users.sh` 写入 `t_user`(命名 `perf_<ts>_NNNNN`),`reset-fixtures.sh` 不动 `t_user`,users.csv 持久复用。
- 失败:看 `perf/results/blocker-snapshot.txt`,不擅自改源码,等用户拍板。详见 `perf/README.md`。

**监控**(T5.3 附带接入):
- `monitoring/prometheus/prometheus.yml` 抓 user/product/order 三服务的 `/actuator/prometheus`。
- gateway 因 management `address: 127.0.0.1`(防 refresh DoS)不收。
- Grafana 看板自动 provision(`monitoring/grafana/`),含 4 panel:HTTP QPS / P99 / JVM heap / HikariCP。
- Compose profile:`monitoring`(单独)/`java`(全栈默认带)。

---

## 9. 验证清单

```bash
# 1. 容器全 Up healthy
docker compose ps --format "table {{.Service}}\t{{.Status}}"

# 2. 业务健康
curl -fsS localhost:8080/actuator/health
curl -fsS localhost:8081/actuator/health
curl -fsS localhost:8082/actuator/health
curl -fsS localhost:8083/actuator/health
curl -fsS localhost:8000/api/recommend/health

# 3. e2e(T4.4 落地)
bash scripts/e2e-recommend.sh
```

期望:全部 `"status":"UP"`,e2e 末尾 `E2E PASS`。

---

## 10. 变更记录

| 日期 | 任务 | 内容 |
|---|---|---|
| 2026-05-24 | T5.1 | docker-compose.yml(全栈)+ 4 个 Java Dockerfile + Python entrypoint + .env.example 扩展 + 本文档。修复 `nacos_client.py` 不展开 ${VAR} 的既有 bug(`expand_env_placeholders`)。 |
| 2026-05-24 | T5.2 | shopsphere-e2e-test 模块(15 个 case);OrderProperties.payment.queueTtlMs 配置项 + scripts/e2e-set-timeout.sh / scripts/wait-stack-healthy.sh。 |
| 2026-05-24 | T5.3 | `perf/`(JMeter 下单压测 + 9 个验证脚本)+ `docs/perf-tcc-report.md` + `monitoring/`(Prometheus + Grafana 容器,user/product/order 接入,看板预置);4 服务 pom 加 `micrometer-registry-prometheus`;exposure.include 追加 prometheus。 |
