# T0.3 — Phase 0 出口验证报告

日期：2026-05-19 ｜ 范围：Phase 0（T0.1 Maven 骨架 + T0.2 基础设施）出口校验 + .claude/docs 审计
约定：**发现问题只报告不修改**（按任务要求）。

## 总览

| # | 检查项 | 结果 |
|---|---|---|
| 1 | .claude/commands & agents 审计 | ⚠️ PASS（1 WARN + 1 MINOR） |
| 2 | api-contracts §5 端口/库名 vs docker-compose | ✅ PASS（1 INFO） |
| 3 | architecture.md ↔ api-contracts.md 冲突 | ✅ PASS（无未解决冲突） |
| 4 | `mvn clean install -DskipTests` | ✅ PASS |
| 5 | docker infra 健康检查（7 子项） | ❌ FAIL（[d] 15672，其余 PASS） |
| 6 | Nacos dev 命名空间 + 占位配置 | ✅ PASS |

**Phase 0 出口结论**：基本达标。**1 个 FAIL（redis/rabbitmq 宿主端口未发布，环境/状态问题，非配置缺陷）**，1 个 WARN（`seata-tx.md` 与 TCC 决策冲突），需处理后再进入 Phase 1。

---

## 1. .claude 审计

### commands/（5）
| 文件 | 摘要 | 评估 |
|---|---|---|
| add-feign.md | 在 `{arg}-api` 加 Feign + `@FeignClient(fallback=)` + Sentinel 降级 + 调用方扫描 | ✅ 合规 |
| db-migration.md | Flyway `V{yyyyMMddHHmm}__desc.sql`，含 down 思考，字段 comment + created_at/updated_at | ✅ 合规 |
| new-endpoint.md | Controller→Service→DTO(@Valid)→Feign→MockMvc→更新 api-contracts | ✅ 合规 |
| run-stack.md | `docker compose -f docker-compose.infra.yml up -d`→等 Nacos→gateway/user/product/order/recommendation→/actuator/health | ✅ 与 compose 文件名一致 |
| seata-tx.md | 要求 `@GlobalTransactional`+分支 `@Transactional`+**所有库有 undo_log**+TX_XID 透传 | ⚠️ **WARN（见下）** |

**[WARN-1] `seata-tx.md` 与已拍板 TCC 决策冲突**
该命令为 **Seata AT 范式**（“确认所有涉及的库都有 undo_log 表”、分支 `@Transactional`）。但 api-contracts §4.3（S1/S2/S3 拍板）已将**库存分支改为 Seata TCC**：Try/Confirm/Cancel + `t_stock_tcc_log`，幂等键 `(orderId,productId)`，`undo_log` 仅 Order 本地分支保留。命令若被照搬会误导 Phase 3 实现（让 Product 库建 undo_log、用 AT 分支）。
*修复建议*：改写 `seata-tx.md`，区分「Order 本地分支(可 AT/undo_log)」与「库存分支(TCC，校验 Try/Confirm/Cancel 幂等、空回滚、防悬挂、Cancel 回补 Redis、`t_stock_tcc_log`)」。

### agents/（3）
| 文件 | name(frontmatter) | 摘要 | 评估 |
|---|---|---|---|
| architect-veviewer.md | `architect-reviewer` | 架构审查员：JWT/跨服务SQL/Feign fallback/事务边界/Flyway/Controller 逻辑 → [BLOCK]/[PASS] | ⚠️ **MINOR** 文件名拼写 |
| recsys-engineer.md | `recsys-engineer` | ItemCF 正确性/Pandas 内存/离线训练性能/FastAPI/Nacos SDK | ✅ |
| test-generator.md | `test-generator` | JUnit5+Mockito 单测 / @SpringBootTest+Testcontainers 集成 / >80% | ✅ |

**[MINOR-1] `architect-veviewer.md` 文件名拼写错误**（应为 `architect-reviewer.md`）。frontmatter `name: architect-reviewer` 正确，agent 仍可正常解析调用（系统已注册 `architect-reviewer`），仅文件名误导。*建议*：重命名文件以保持一致。

---

## 2. §5 端口/库名 vs docker-compose.infra.yml — ✅ PASS

| §5 基础设施 | compose 发布 | 一致 |
|---|---|---|
| Nacos 8848 | 8848（+9848/9849 gRPC） | ✅ |
| MySQL 3306 | 3306 | ✅ |
| Redis 6379 | 6379 | ✅ |
| RabbitMQ 5672/15672 | 5672/15672 | ✅ |
| Seata 8091 | 8091（+7091 控制台） | ✅ |
| Sentinel 8858 | 8858 | ✅ |

库名：`shopsphere_user/product/order/reco`（§5/§7）+ `shopsphere_seata`、`nacos_config`（基础设施必需）全部一致，均 utf8mb4/unicode_ci。

**[INFO-1]** compose 额外发布 Nacos `9848/9849`（Nacos 2.x 客户端 gRPC，**必需**）与 Seata `7091`（控制台），§5 概览行未列出。非冲突；建议 §5 加脚注说明，避免后续误判。

---

## 3. architecture.md ↔ api-contracts.md — ✅ PASS（无未解决冲突）

| 重点 | api-contracts | architecture | 一致 |
|---|---|---|---|
| §6.4 推荐路径 | `/api/recommend/user/{userId}`、`/similar/{itemId}`、`/internal/recommend/train`、`/api/recommend/health` | §2.4 接口块/模块树/§1.1 图注 完全相同 | ✅ |
| §4.1 服务间通信(C2) | Feign 走 Nacos 直连，不经 Gateway；无 Java→Py Feign | §1.1 图注 + §2.3 链路 + T4.4 一致 | ✅ |
| §4.3 库存 TCC | Try/Confirm/Cancel、`t_stock_tcc_log`、`(orderId,productId)`、Cancel 回补 Redis | §1.2 ADR + §2.2 设计/表 + §2.3 链路 一致 | ✅ |
| §6.3 状态机 | `CREATED→PAID→SHIPPED→COMPLETED`，旁支 `CREATED/PAID→CANCELLED`，SHIPPED 后不可取消 | §2.3 状态机一致 | ✅ |

两文档交叉无未解决冲突。残留唯一不一致在 **.claude/commands/seata-tx.md**（非文档，[WARN-1]）。
**[INFO-2]** 措辞细节：architecture §2.4 “健康检查/Nacos 心跳” 与 api-contracts §6.4（澄清心跳由 nacos-sdk-python 主动维护）表述略异，语义不冲突，可忽略。

---

## 4. mvn clean install -DskipTests — ✅ PASS

退出码 0。产物：`shopsphere-common`、`user-api`、`product-api`、`order-api` 四 jar 均生成。

---

## 5. docker 健康检查 — ❌ FAIL（[d]）

容器：mysql/nacos/redis/rabbitmq/seata/sentinel 均 `healthy`，seata-config-init `Exited(0)`。

| 子项 | 命令 | 结果 |
|---|---|---|
| [a] Nacos readiness | `curl -f .../readiness` | ✅ HTTP 200 |
| [b] MySQL 6 库 | `SHOW DATABASES` | ✅ 6 库齐全 |
| [c] Redis ping | `redis-cli ping`（容器内） | ✅ PONG（容器内/容器网络 OK；**宿主 6379 未发布**，见下） |
| [d] RabbitMQ 15672 | `curl http://localhost:15672` | ❌ **HTTP 502** |
| [e] 延迟插件 | `rabbitmq-plugins list -e` | ✅ `[E*] rabbitmq_delayed_message_exchange 3.13.0` |
| [f] Seata 7091 | `curl http://localhost:7091` | ✅ HTTP 200 |
| [g] Sentinel 8858 | `curl http://localhost:8858` | ✅ HTTP 200 |

**[FAIL-1] redis 与 rabbitmq 宿主端口未发布（502 / 不可达）**
- 现象：`docker port shopsphere-rabbitmq` 与 `shopsphere-redis` **均为空**；而 `HostConfig.PortBindings` 已正确定义（`15672→15672`、`5672→5672`、`6379→6379`）。其余 4 容器端口映射正常。容器内 RabbitMQ 日志确认 `Management plugin: HTTP listener started on port 15672`，AMQP `[::]:5672` 正常——**服务本身健康，仅宿主映射未编程**。
- 根因：T0.2 首次 `up --build` 因宿主原生 Redis 占用 `0.0.0.0:6379` 在网络编程阶段中止（`failed to set up container networking … Bind for 0.0.0.0:6379 failed`），redis/rabbitmq 容器虽被创建但宿主端口绑定未写入 docker 代理；你停掉原生服务后第二次 `up -d` 仅 **start** 既有容器，未重新发布端口。
- 影响：容器网络内（业务服务→`redis:6379`/`rabbitmq:5672`）**不受影响**；仅宿主直连（含本任务 [d] 检查、本机调试、Phase 1 本机起的服务连 `localhost`）失败。
- *修复建议*（你确认后执行）：确保宿主 6379/5672/15672 空闲后
  `docker compose -f docker-compose.infra.yml up -d --force-recreate redis rabbitmq`
  （`--force-recreate` 触发重建以重新编程端口发布），再复跑 [c]/[d]。

**[NOTE-1]** 任务指定的 `rabbitmqctl list_plugins | grep delayed` 在 3.13-management 镜像下无输出（格式差异）；**权威校验用 `rabbitmq-plugins list -e`**，已确认插件启用，建议后续文档/命令统一用后者。

---

## 6. Nacos dev 命名空间 + 占位配置 — ✅ PASS

- 命名空间 `dev` 创建成功（`customNamespaceId=dev`），命名空间列表可见。
- 配置 `shopsphere-gateway-dev.yaml` 发布于 **namespace=dev / group=DEFAULT_GROUP / type=yaml**，内容：
  ```yaml
  security:
    whitelist: []
  ```
- 已落 MySQL：`config_info` 行 `data_id=shopsphere-gateway-dev.yaml, group_id=DEFAULT_GROUP, tenant_id=dev`（同时再次佐证 Nacos MySQL 持久化生效）。
- 首次回读返回 “not exist” 系 Nacos 写后短暂最终一致延迟，二次回读内容正确。

**[NOTE-2]** Nacos「group」非一等对象，随首个 config/服务发布隐式产生；按你“DEFAULT_GROUP 即可”的指示，仅用 DEFAULT_GROUP，未单独“创建三个 group”（Nacos 无此操作）。如需 Phase 1 用独立 group（如 `SHOPSPHERE_GROUP`），发布配置时指定即可，无需预建。

---

## 修复记录（T0.3 收尾，全部已处理）

| 级别 | 项 | 处理 | 状态 |
|---|---|---|---|
| ❌ FAIL | [FAIL-1] redis/rabbitmq 宿主端口未发布 | `docker compose up -d --force-recreate redis rabbitmq`；重建后 `docker port` 确认 6379/5672/15672 → 0.0.0.0 已发布（Docker 绑 0.0.0.0 与宿主原生 redis 127.0.0.1 共存，同 mysql） | ✅ RESOLVED |
| ⚠️ WARN | [WARN-1] seata-tx.md AT 范式违背 TCC | 重写 `.claude/commands/seata-tx.md`：拆分 A.库存分支(TCC：Try/Confirm/Cancel + t_stock_tcc_log + 幂等(orderId,productId) + 空回滚/防悬挂 + Cancel 回补 Redis) / B.Order 本地分支(可 AT，仅 Order 库 undo_log) | ✅ RESOLVED |
| ▫️ MINOR | [MINOR-1] architect-veviewer.md 文件名 | `mv` → `.claude/agents/architect-reviewer.md` | ✅ RESOLVED |
| ℹ️ INFO | [INFO-1] §5 未列 9848/9849/7091 | api-contracts §5 加脚注说明（运维必需端口，非业务契约） | ✅ RESOLVED |
| ℹ️ NOTE | [NOTE-1] 延迟插件校验命令 | README-infra.md 已用 `rabbitmq-plugins list -e`（权威），无需改动 | ✅ N/A（已正确） |

> [FAIL-1] 复验结果见下「修复后复验」。其余为静态修改，已即时生效。

## 修复后复验（[FAIL-1] 闭环）

| 子项 | 复验命令 | 修复前 | 修复后 |
|---|---|---|---|
| [c] Redis | `docker exec redis-cli ping` + `docker port` | PONG（宿主端口缺） | ✅ PONG，`6379→0.0.0.0:6379` 已发布 |
| [d] RabbitMQ 15672 | `curl -L http://localhost:15672/` | ❌ 502 | ✅ **200** |
| [d'] mgmt API | `/api/aliveness-test/shopsphere`（admin） | 502 | ✅ 200 |
| [e] 延迟插件 | `rabbitmq-plugins list -e` | [E*]（本就 OK） | ✅ `[E*] rabbitmq_delayed_message_exchange 3.13.0` |
| 容器总览 | `compose ps` | rabbitmq/redis 端口缺 | ✅ 6 healthy + seata-config-init Exited(0) |

**结论：T0.3 所有问题已修复闭环。Phase 0 出口 = 全 PASS，可进入 Phase 1。**
