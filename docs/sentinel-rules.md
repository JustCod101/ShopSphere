# ShopSphere Sentinel 流控规则

T1.5 落地。Sentinel 1.8.x(由 Spring Cloud Alibaba 2023.0.1.0 BOM 锁定),Nacos 作 source of truth 规则数据源,Dashboard 仅观测 + 临时调试。

---

## 1. 总体策略

**双层防御**:
- **网关层(Spring Cloud Gateway adapter)**:按 API 分组对外暴露面整体限流,挡爬虫/暴破/突发洪峰。
- **业务服务层(Spring MVC SentinelWebInterceptor)**:按 URI 路径限流,**1.5× 网关阈值**作防御纵深 —— 网关绕过 / 内网直连 8081 时仍有兜底。

**响应契约**:命中限流统一返回 `HTTP 200` + body `{code:1003, message:"请求过于频繁", data:null, traceId:..., timestamp:...}`(契约 §2)。`traceId` 与正常请求同源(网关 32hex,Gateway 由 `RequestLogFilter` 注入 exchange attribute;业务层从 MDC 或 `X-Trace-Id` 兜底)。

**失效模式**:
- Sentinel **Dashboard 不可达** → 不影响业务(规则源是 Nacos),仅丢失实时监控视图。
- Sentinel **Nacos 不可达** → `sentinel-datasource-nacos` 用本地缓存的最后一次成功规则,不卡服务。
- Sentinel **客户端进程内异常** → fail-open(放行,等同无限流);需运维监控 Sentinel ERROR 日志。

---

## 2. 资源命名约定

| 来源 | 资源名(resource) | 备注 |
|---|---|---|
| Gateway(API 分组) | `api-user-login` / `api-user-register` / `api-user-behavior` | 路径 → 名字映射在 `SentinelGatewayConfig.API_GROUPS` Java 代码内,路径漂移即编译失败 |
| 业务服务(Spring MVC URI) | `/api/user/login` / `/api/user/register` / `/api/user/behavior` | `SentinelWebInterceptor` 默认 URI 作 resource;改路径必须同步改规则 |

**铁律**:改 Controller 路径 = 同步改 Sentinel rules(网关 API 分组 Java + Nacos JSON)。两侧名字未变,阈值仅在 Nacos 调即可。

---

## 3. Nacos 规则文件

| dataId | namespace | group | rule-type | 镜像位置 |
|---|---|---|---|---|
| `shopsphere-gateway-flow-rules.json` | dev | DEFAULT_GROUP | `gw-flow` | `docs/nacos/shopsphere-gateway-flow-rules.json` |
| `shopsphere-user-flow-rules.json` | dev | DEFAULT_GROUP | `flow` | `docs/nacos/shopsphere-user-flow-rules.json` |

**首次部署**:Nacos console → 对应 namespace → 配置管理 → 创建 → dataId 取上表名,类型选 JSON,内容粘贴 `docs/nacos/` 下镜像文件。

**变更阈值**:Dashboard 实时调试可写 Nacos 同步,也可直接 Nacos 编辑发布;客户端 `sentinel-datasource-nacos` 监听变更后秒级生效。**生产环境只走 Nacos 发布,Dashboard 直推 Sentinel client 的临时改动重启即丢**(Dashboard 1.8.x 不持久化)。

---

## 4. 初始规则

### 4.1 Gateway(`shopsphere-gateway-flow-rules.json`)

| 资源(API 分组) | 路径 | QPS | controlBehavior | 触发动作 |
|---|---|---|---|---|
| `api-user-login` | `/api/user/login` | 20 | 0 直接拒绝 | 1003 + traceId |
| `api-user-register` | `/api/user/register` | 5 | 0 直接拒绝 | 1003 + traceId |
| `api-user-behavior` | `/api/user/behavior` | 200 | 0 直接拒绝 | 1003 + traceId |

字段全集(GatewayFlowRule):
- `resource`: API 分组名(代码内 `GatewayApiDefinitionManager` 注册过)
- `resourceMode`: `1` = API 分组(非 route id)
- `grade`: `1` = QPS(`0` = 线程数,不用)
- `count`: 阈值
- `intervalSec`: `1` = 1 秒统计窗口
- `controlBehavior`: `0` = 直接拒绝(`1` 慢启动 / `2` 排队等待,本期不用)
- `burst`: `0` = 不允许突发(平滑限流)
- `limitApp`: `default` = 适用所有调用方

### 4.2 User(`shopsphere-user-flow-rules.json`)

| 资源(URI) | QPS | 备注 |
|---|---|---|
| `/api/user/login` | 30 | 1.5× 网关 |
| `/api/user/register` | 10 | 2× 网关 |
| `/api/user/behavior` | 300 | 1.5× 网关 |

字段全集(FlowRule):
- `resource`: URI 路径,不带 query
- `limitApp`: `default`
- `grade`: `1` = QPS
- `count`: 阈值
- `strategy`: `0` = 直接(`1` 关联 / `2` 链路)
- `controlBehavior`: `0` = 直接拒绝
- `clusterMode`: `false` = 单机限流(集群版需额外 Cluster Server)

---

## 5. 响应契约

```json
{
  "code": 1003,
  "message": "请求过于频繁",
  "data": null,
  "traceId": "abcd1234abcd1234abcd1234abcd1234",
  "timestamp": "2026-05-20T10:30:00.123Z"
}
```

**HTTP 状态固定 200**,错误码在 body。客户端按 `code === 1003` 识别限流,可显示"请求过于频繁,请稍后再试"并指数退避重试。

**traceId 来源链**:
- **Gateway**:`RequestLogFilter` 在最外层 WebFilter 生成 32-hex 写入 `exchange.attribute(X_TRACE_ID)`,`SentinelGatewayConfig#traceIdAwareBlockRequestHandler` 读取
- **User 服务**:`UserContextInterceptor` 从请求头 `X-Trace-Id` 读出 → MDC;`SentinelWebBlockConfig#sentinelBlockExceptionHandler` 优先 MDC、兜底再从 header 读

---

## 6. 与防爆破(`LoginAttemptService`,§10)的关系

两者**正交互补,无冲突**:

| 维度 | Sentinel 限流 | LoginAttemptService 防爆破 |
|---|---|---|
| 监控对象 | 整接口 QPS | 同一账号失败次数 |
| 触发阈值 | 20 QPS / 秒(网关 login) | 5 次失败 / 10 分钟(单 username) |
| 锁定时长 | 1 秒滚动窗口 | 30 分钟 |
| 错误码 | 1003 | 2002(message 区分锁定态) |
| 存储 | 内存(单机) | Redis(集群共享) |
| 攻击场景 | 突发洪峰(短时大量请求,不区分账号) | 慢速暴破(每账号 1 QPS,不同账号轮换) |

Sentinel 挡得住 50 VUs 瞬时打爆,挡不住 100 个账号每分钟 1 次的慢速暴破 — 由 LoginAttemptService 兜底。

---

## 7. 验证

详见 `scripts/perf/sentinel-login-load.k6.js` k6 脚本与 `shopsphere-user/README.md §7`,核心断言:
- 1000 请求 50 VUs → 至少 90% 返回 `code=1003`
- 命中 1003 的响应须含 `^[0-9a-f]{32}$` 形态 traceId
- HTTP 5xx 率 < 1%

热加载:Nacos 把 `api-user-login.count` 改成 100 → 等 5s → 重跑 k6,1003 占比应显著下降。
