# 推荐服务集成指南(integration-recommend)

> 适用范围:**前端 / BFF** 接入 ShopSphere 推荐服务。Java 后端服务 **不** 应调用推荐接口(见 §2 C2 拍板)。
> 对应任务:T4.4。契约权威源:`docs/api-contracts.md` §6.4。

---

## 1. 总览

推荐服务由 FastAPI 实现(`shopsphere-recommendation`),通过 Nacos 注册到服务注册表,经 Spring Cloud Gateway(`lb://shopsphere-recommendation`)对前端暴露。**数据流**:

```
浏览器 / 移动端
   │
   ▼  (HTTPS + Authorization: Bearer <JWT>)
Spring Cloud Gateway (:8080)
   │  - JwtAuthFilter 校验 JWT,X-User-Id 注入下游
   │  - RequestLogFilter 剥离 + 重生成 X-Trace-Id (UUID 32hex)
   │  - 白名单匹配(security.whitelist),命中跳过 JWT
   │  - lb://shopsphere-recommendation 负载均衡到 Python 实例
   ▼
FastAPI 推荐服务(默认 :8000)
   │  - UserContextMiddleware:X-User-Id / X-Trace-Id → contextvar
   │  - /api/recommend/user/{uid}:个性化召回(ItemCF)
   │  - /api/recommend/similar/{iid}:i2i 相似
   │  - /api/recommend/health:Nacos 探活
   │  - /internal/recommend/train:**仅内部**(经 Gateway 必 403)
```

---

## 2. C2 拍板:为什么 Java 侧不调推荐?

**契约来源**:`docs/api-contracts.md` §4.2 C2(2026 Q1 已拍)。

**原文**:「推荐接口由前端经 Gateway 直连 Python(`/api/recommend/**`,带用户 JWT,Gateway 校验)。原 architecture.md T4.4「Java 经 Feign 调推荐」**取消**(无明确业务调用方,徒增鉴权矛盾)。」

**决策原因**:

| 维度 | Java Feign 调推荐的代价 | 前端直连的收益 |
|---|---|---|
| 鉴权 | Python 无 Sentinel,Java Feign 需在调用方独立实现 fallback,跨语言双轨 | Gateway 已统一 JWT,Python 信任 X-User-Id 即可 |
| 数据时效 | 推荐结果属"猜你喜欢"软场景,无强一致需求,后端再聚合徒增链路延迟 | 前端拿到 itemId 列表后,自取商品详情即可 |
| 调用方 | 没有真实业务场景需要 Java 侧聚合推荐(电商页面拼装是 UI 职责) | 前端 UI 编排自然 |
| 维护 | 多一个 `recommendation-api` 模块、Feign 接口、fallback 类 | 仓库零新增 Java 代码 |

**禁区**(违反即应在 code review 被拒):

- ❌ 不创建 `recommendation-api` 模块(对应 §4.2 Feign 表中确实缺席)
- ❌ 不在 `shopsphere-user / -product / -order` 任何 Service 里调推荐接口
- ❌ 不创建 `RecommendationFeignClient` 或等价类
- ❌ 不在 Java 侧任何 Result 字段里塞推荐结果

**未来确需 Java 侧消费推荐怎么办?**:

走 `/internal/recommend/**` + Nacos 服务发现直连(不复用 Feign 体系),并在 `recommendation-api`(届时新建)补 `RecommendationInternalClient`,fallback 由调用方实现。本期不预制。

---

## 3. 接入路径

### 3.1 个性化推荐(需登录)

**端点**:`GET /api/recommend/user/{userId}?topk=10`

**鉴权**:Gateway 强制 JWT;**path `userId` 必须等于 JWT 解出的 `X-User-Id`,否则 1001**(防越权)。

**topk**:范围 `[1, 50]`,默认 10。超界 → `code=1000`。

**请求**:

```http
GET /api/recommend/user/12345?topk=10 HTTP/1.1
Host: gateway.example.com
Authorization: Bearer eyJhbGc...
```

**响应示例(正常召回)**:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 12345,
    "topk": 10,
    "items": [
      { "itemId": 301, "score": 1.234 },
      { "itemId": 302, "score": 0.987 }
    ],
    "fallback": false
  },
  "traceId": "abc123...",
  "timestamp": "2026-05-24T03:21:00+00:00"
}
```

**响应示例(冷启动 / 模型未就绪 fallback)**:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 12345, "topk": 10,
    "items": [ { "itemId": 11, "score": 9.0 }, ... ],
    "fallback": true
  },
  "traceId": "...", "timestamp": "..."
}
```

**前端处理建议**:`fallback=true` 时正常渲染,UI 上可加"热门推荐"标识;`fallback=false` 时打"猜你喜欢"。**两者 `code` 都是 `0`**(C1 拍板,5001/5002 不进 code,仅作内部 metrics)。

### 3.2 i2i 相似推荐(公开)

**端点**:`GET /api/recommend/similar/{itemId}?topk=10`

**鉴权**:白名单放行(`/api/recommend/similar/**`),**无需 JWT**。

**用途**:商品详情页"相似商品"模块、购物车"猜你还喜欢"。

**响应**:与 §3.1 结构一致(`data.itemId` 替代 `data.userId`)。

```json
{
  "code": 0,
  "data": {
    "itemId": 100, "topk": 10,
    "items": [ { "itemId": 201, "score": 0.9 }, ... ],
    "fallback": false
  }, ...
}
```

### 3.3 健康检查(运维)

**端点**:`GET /api/recommend/health`

**鉴权**:白名单。

**响应**:

```json
{
  "code": 0,
  "data": { "status": "UP", "model_ready": true }
}
```

前端不直接调;运维 / 监控用。`model_ready=true` 表示 ItemCF 训练已产出索引(Redis key `reco:model:ready` 存在)。

### 3.4 内部触发训练(禁止经 Gateway)

**端点**:`POST /internal/recommend/train` —— 仅推荐服务自身可达。

经 Gateway 调将被 `InternalAccessRejectFilter` 拦截,返 HTTP 403 + `code=1004`。
本地调试 / Cron 测试时,直连 Python(默认 `:8000`)。

---

## 4. 错误码处理

| code | 场景 | 前端动作 |
|---|---|---|
| `0` | 成功 / fallback 兜底 | 渲染 `data.items`。`fallback=true` 加标识 |
| `1000` | 参数错(`topk` 越界、path 类型不对) | 修正请求 |
| `1001` | 未登录 / 越权(JWT 缺失,或 path userId ≠ JWT userId) | 跳登录页;或拒绝越权请求 |
| `1004` | 路径不存在(`/internal/**` 经 Gateway 时) | 不应到达此分支;若发生,审查路由 |
| `1500` | 服务端未捕获异常 | 提示稍后重试,上报 traceId |

**重要**:5001(冷启动)、5002(模型未就绪)**不会**出现在 `code` 字段 —— 这两种情况返 `code=0 + fallback=true`,不影响前端正常流。两者仅作 Python 侧结构化日志事件(`reco.cold_start` / `reco.model_not_ready`),由 ELK/Loki 聚合统计。

---

## 5. 本地联调指引

### 5.1 起依赖

```bash
# Nacos / Redis / RabbitMQ / MySQL —— 任选 docker 或本地安装
docker compose up -d nacos redis rabbitmq mysql
```

### 5.2 起 Python 推荐服务

```bash
cd shopsphere-recommendation
.venv/bin/python -m alembic upgrade head      # 首次建表
export NACOS_REGISTER_IP=127.0.0.1            # Gateway 在 host 时
export APP_PROFILE=dev
.venv/bin/uvicorn app.main:app --port 8000
```

启动日志看到 `nacos register ok ip=127.0.0.1 port=8000` 才算注册成功。Nacos 控制台 → 服务列表 → 应能看到 `shopsphere-recommendation` 实例。

### 5.3 起 Gateway + 其他 Java 服务

正常 Spring Boot 启动顺序(参考 `architecture.md`):Nacos → User / Product / Order → Gateway。

### 5.4 跑端到端验证脚本

```bash
bash scripts/e2e-recommend.sh
```

期望末尾 `================== E2E PASS ==================`。

环境变量覆盖示例:

```bash
GW=http://localhost:8080 \
RECO=http://localhost:8000 \
MYSQL_PASSWORD=yourpass \
MAX_TRAIN_WAIT_SEC=120 \
bash scripts/e2e-recommend.sh
```

---

## 6. 常见故障与排查

| 现象 | 可能原因 | 排查路径 |
|---|---|---|
| Gateway 调推荐返 503 / `Connect refused` / 路由超时 | Python 没在 Nacos 注册成功 | Python 启动日志看 `register_ip`;Nacos 控制台看实例数;Gateway 容器内能否 `curl <register_ip>:8000/api/recommend/health` |
| Nacos 控制台有实例,但 Gateway 仍 503 | 注册 IP 容器外不可达(如 Python 在 host,注册了 127.0.0.1,Gateway 在容器) | `export NACOS_REGISTER_IP=host.docker.internal`(macOS)或显式 LAN IP |
| `/api/recommend/health` `model_ready=false` 永远不变 | 训练失败 / 未触发 | `SELECT * FROM shopsphere_reco.t_train_log ORDER BY id DESC LIMIT 1`;若 status='FAILED' 看 `error` 列 |
| `/api/recommend/user/{uid}` 一直返 `fallback=true` | 行为没消费到 / Redis 没写 user:behavior | (a) `SELECT COUNT(*) FROM behavior_event WHERE user_id=?` 看 MQ 是否落库;(b) `redis-cli ZRANGE user:behavior:{uid} 0 -1 WITHSCORES` 看 Redis 是否有最近行为 |
| `code=1001` 但已带 Authorization 头 | JWT 解析失败(过期/签名错)或 path userId ≠ JWT userId | 看 Gateway 日志 JwtAuthFilter 输出;前端 path 用 JWT 解出的 userId(不要硬编码或从外部参数读) |
| `/internal/recommend/train` 直连 Python 返 `triggered=false, reason=already_running` | 当前已有训练在跑(cron 触发或上次未完) | `SELECT * FROM t_train_log WHERE status='RUNNING'`;等其结束或人工 `redis-cli DEL reco:training` |

---

## 7. 未来扩展(预留,本期不做)

- **多路召回**:itemcf + content-based + popularity 多路并行,加权合并(T5)
- **Prometheus metrics**:`reco_cold_start_total` / `reco_recall_latency_seconds` 暴露到 `/metrics`(T5)
- **过滤一致性**:`behavior_consumer` 处理 `order.created` 时主动 `DEL reco:purchased:user:{uid}`,避免 30min 缓存窗内仍推已购(T5)
- **Java 内部聚合**(若真有场景):`/internal/recommend/**` 走 Nacos 直连,**不复用 Feign 体系**

---

## 8. 变更记录

| 日期 | 任务 | 内容 |
|---|---|---|
| 2026-05-24 | T4.4 | 文档创建。Gateway 路由 + 白名单在 T1.1 已超前就位;本期仅落 e2e 脚本 + 本文档。 |
