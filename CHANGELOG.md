# CHANGELOG

> 按 Phase 倒序;每条引用 commit sha(无 git remote 时为本地 sha,配置 remote 后可在 `git log <sha>` 直查)。
> 时间以 commit 日期为准。

---

## [T5.4] 2026-05-24 — 项目交付文档完善

- 新建根 [README.md](README.md):mermaid 架构图 + 技术栈表 + 3 步快速启动 + 服务一览 + 文档导航
- 新建 [docs/adr/](docs/adr/) 8 条架构决策记录(ADR-001~008)
- 新建 [docs/troubleshooting.md](docs/troubleshooting.md):8 类运行期/集成期故障(Nacos / Seata / TCC 空回滚悬挂 / Redis 漂移 / MQ 延迟插件 / Py 注册 IP / JWT / Sentinel)
- 新建 [CHANGELOG.md](CHANGELOG.md)
- 补齐 5 个服务 README:`gateway / order / common / api / e2e-test`
- 微调 [docs/deployment.md](docs/deployment.md) §7 加 troubleshooting 交叉链接
- architect-reviewer 验收 → [docs/release-checklist.md](docs/release-checklist.md)(17 条逐条 PASS/FAIL,以 api-contracts §9 为基准)

---

## [T5.3] 2026-05-24 — 下单链路压测 + Prometheus / Grafana

- JMeter 计划 `perf/order-create.jmx`:**目标 1000 并发 / 实测 200**(JMeter 参数透传问题,见 [release-checklist.md](docs/release-checklist.md) §2.2 假设 1)/ rampup 1s / 每用户 1 件 / `X-Request-Id=__UUID()` / Groovy 后处理分桶
- 8 个编排脚本:`prepare-users / reset-fixtures / verify-phase{1,2,3} / pay-phase / cancel-sample / run-all`
- 4 Java 服务暴露 `/actuator/prometheus`(`micrometer-registry-prometheus` 进 pom + yml 暴露)
- docker-compose 增 `prometheus + grafana`(profile `monitoring` / `java`),自动 provisioning + 1 dashboard(QPS/P99/JVM/HikariCP)
- 报告模板 [docs/perf-tcc-report.md](docs/perf-tcc-report.md),验收门:成功 ≤ 500 / stock+locked=500 / Redis=DB.stock / TCC 三段计数对齐 / QPS≥200 / P99<500ms

commit: `47e1f03`

---

## [T5.2] 2026-05-24 — 端到端测试模块 + `queueTtlMs` 可配置化

- 新建 `shopsphere-e2e-test` 独立模块(不进 reactor)
- 链路覆盖:register → login → browse → addToCart → createOrder → pay → recommend
- 30 min 延迟队列 TTL 改为可配(`order.payment.timeout-ms`),便于 E2E 缩短到 1 min 验证 Cancel

commit: `e165793`

---

## [T5.1] 2026-05-24 — 全栈容器化 + 3 既有 bug 修复

- 5 个 Dockerfile(4 Java multi-stage / 1 Python + nacos-sdk)
- docker-compose:profile(java / python / monitoring),健康探活,depends_on 编排
- 自动化:`scripts/nacos-config-push.sh` 将 Nacos 配置文件批量推到 Nacos 启动后
- 修复:Gateway 管理端口绑 127.0.0.1(防 DoS)/ Seata Nacos config 编码 / Reco Nacos 注册 IP

commit: `bcd9f30`

---

## [T4.4] 2026-05-24 — 推荐服务 E2E + 前端集成文档

- `scripts/e2e-recommend.sh`:行为投递 → 训练触发 → 在线召回
- [docs/integration-recommend.md](docs/integration-recommend.md):前端 SDK 接入 + Gateway 路由 + 错误码

commit: `5ca85ad`

## [T4.3] 2026-05-24 — 在线推荐接口 + 冷启动 fallback + 越权拦截

- `GET /api/recommend/personal / similar`(FastAPI)
- 冷启动 fallback:用户/商品无数据时返热门 top-N(`code=0` + `fallback=true`)
- 越权拦截:用户只能查自己的个性化推荐

commit: `4fbfbad`

## [T4.2] 2026-05-24 — ItemCF 离线训练 + Recall 索引 + APScheduler

- `item_cf.py` 计算同现矩阵 + 余弦相似度
- 产物 `reco_item_similarity` 表(带 version / TTL)
- APScheduler 定时(可配)训练

commit: `6fea107`

## [T4.1] 2026-05-23 — 推荐服务骨架(FastAPI + Nacos + 自有库 + MQ 消费者)

- Python 3.11 + FastAPI 0.110 + nacos-sdk-python + aio-pika
- 独立库 `shopsphere_reco`(SQLAlchemy + alembic)
- 消费 `behavior.exchange` + `order.exchange`

commit: `d059843`

---

## [T3] 2026-05-22 — 订单 + 分布式事务 + 强可靠 MQ

T3.1-T3.5 在一个 commit 内提交:
- T3.1 Seata 集成验收([docs/seata-verify.md](docs/seata-verify.md))
- T3.2 订单服务骨架 + status 状态机 + 三接口(create/pay/cancel)
- T3.3 库存 TCC 失败回归 7 case 全绿([docs/tcc-rollback-report.md](docs/tcc-rollback-report.md))
- T3.4 RabbitMQ 拓扑 + delayed-message-exchange + 强弱可靠分级([docs/mq-topology.md](docs/mq-topology.md))
- T3.5 本地消息表 + 扫描器 + publisher-confirm

commit: `beaf0ad`

---

## [T2.4] 2026-05-21 — 库存 TCC 接口骨架 + product-api Feign 契约

- `/internal/product/stock/{try,confirm,cancel}` 三个内部接口
- `shopsphere-api/product-api` 模块 Feign 客户端 + Sentinel Fallback

commit: `0be99bb`

## [T2.3] 2026-05-21 — Redis 库存原子预扣

- `stock_prededuct.lua` 原子脚本(返回 -1=不足 / -2=key 缺)
- `stock:product:{id}` 设计([docs/stock-redis.md](docs/stock-redis.md))

commit: `ce9fca7`

## [T2.2] 2026-05-21 — 商品详情 Cache-Aside + 三防

- 防穿透:布隆 + 空值缓存
- 防击穿:互斥锁(`SETNX`)
- 防雪崩:TTL 随机抖动
- 写路径:延迟双删

commit: `a1c3f57`

## [T2.1] 2026-05-21 — 商品模块骨架 + MyBatis-Plus

- `shopsphere-product` 模块创建
- `GET /api/product/{id}` 基础接口
- Flyway migration 初始化(`V20260521_1000__init_product.sql`)

commit: `5432291`

---

## [T1.5] 2026-05-20 — Gateway + User 接入 Sentinel + Nacos 规则热加载

- `sentinel-rules-{gateway,user}-flow.json` 在 Nacos 维护
- 客户端 datasource 配置 `nacos-datasource`,规则修改即时生效
- [docs/sentinel-rules.md](docs/sentinel-rules.md)

commit: `4775607`

## [T1.4] 2026-05-20 — 用户行为埋点 + MQ 轻量直发(M4)

- `POST /api/user/behavior` 接口(支持浏览/点击/加购/搜索)
- `behavior.exchange` topic 直发(无 outbox,fire-and-forget,见 [ADR-008](docs/adr/ADR-008-mq-reliability-tiers.md))

commit: `06430a1`

## [T1.3] 2026-05-20 — User 注册/登录/me/内部 Feign + JWT RS256 签发

- `POST /api/user/register, /login, GET /api/user/me`
- JWT RS256 签发(私钥仅 User 持有)
- 防爆破:登录失败次数限流(Sentinel 规则)

commit: `25d3555`

## [T1.2] 2026-05-20 — Gateway JWT 鉴权 + 白名单 + 剥头

- `GwAuthFilter` 校验 JWT,提取 claim 注入 `X-User-Id` / `X-User-Name` / `X-Trace-Id`
- 公开接口白名单(register/login/swagger)
- 剥头逻辑:客户端伪造的 `X-User-Id` 无条件覆盖

commit: `b63e2d2`

## [T1.1] 2026-05-20 — Gateway 模块骨架

- `shopsphere-gateway` 模块 + Spring Cloud Gateway 配置
- Nacos discovery 路由

commit: `9c1b981`

---

## [T0] 2026-05-19 — 初始化

- 工程骨架(reactor `pom.xml`)
- `shopsphere-common`(Result / ErrorCode / JWT util / HeaderConstant / 全局异常 / UserContext)
- `shopsphere-api`(Feign 契约预留)
- Docker compose 基础设施(MySQL / Redis / Nacos / Seata / Sentinel / RabbitMQ)
- Flyway / MyBatis-Plus 全局配置

commit: `dfaf008`
