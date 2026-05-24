# shopsphere-gateway

## 职责

ShopSphere 流量入口。负责:
- **JWT 鉴权**(RS256,详见 [ADR-002](../docs/adr/ADR-002-jwt-at-gateway.md))
- **路由分发**(Nacos discovery,4 Java + 1 Python 服务)
- **限流 / 熔断**(Sentinel-Gateway,规则 Nacos 热加载)
- **请求上下文注入**(`X-User-Id` / `X-User-Name` / `X-Trace-Id`)
- **公开接口白名单**(register / login / actuator / docs)
- **内部接口拒绝**(`/internal/**` 强制 401,防外部直调 Feign 内部 API)

## 端口 / 依赖

| 项 | 值 |
|---|---|
| 业务端口 | **8080**(`GATEWAY_PORT`) |
| 管理端口 | **8090**(仅绑 127.0.0.1,防 DoS) |
| Sentinel transport | 8719 |
| 数据库 | 无 |
| 依赖 | Nacos(注册 + 配置)、Sentinel、JWT 公钥(scripts 产) |

## 本地启动

```bash
# 1. 起基础设施
docker compose up -d nacos sentinel
# 起 user 服务(才能登录拿 token)
docker compose up -d shopsphere-user

# 2. 启动 Gateway
cd shopsphere-gateway && mvn spring-boot:run
# 或 IDE 启动 GatewayApplication
```

## 关键配置(application.yml)

| key | 说明 |
|---|---|
| `spring.cloud.gateway.routes` | 路由规则(`/api/user/** → lb://shopsphere-user` 等;Nacos discovery) |
| `gateway.whitelist.paths` | 公开接口白名单(`/api/user/register`、`/api/user/login` 等) |
| `gateway.jwt.public-key-path` | RS256 公钥文件路径(由 `scripts/gen-jwt-keys.sh` 生成,挂载到容器) |
| `spring.cloud.sentinel.datasource.flow-rules` | 限流规则 Nacos 数据源 `sentinel-rules-gateway-flow.json` |
| `management.server.port` | 8090(管理端口,仅 127.0.0.1) |

Nacos dataIds:`gateway.yml` / `common-config.yml`

## 核心代码导航

| 路径 | 说明 |
|---|---|
| `GatewayApplication.java` | 启动类 |
| `filter/JwtAuthFilter.java` | JWT 校验 + claim 注入 header(剥头逻辑见 [ADR-002](../docs/adr/ADR-002-jwt-at-gateway.md)) |
| `filter/InternalAccessRejectFilter.java` | `/internal/**` 直接 401 |
| `filter/RequestLogFilter.java` | 请求日志 + `X-Trace-Id` 生成 |
| `config/GatewayFilterOrders.java` | 过滤器优先级常量 |
| `config/SentinelGatewayConfig.java` | Sentinel-Gateway 限流响应封装 |
| `config/WhitelistProperties.java` | 白名单读取 |
| `security/JwtPublicKeyProvider.java` | 公钥加载 + 自动 reload |

## 测试

```bash
# 单元测试
mvn -pl shopsphere-gateway test

# 端到端(通过 Gateway 调真实业务):
mvn -pl shopsphere-e2e-test test -Dtest=K_GatewaySecurityTest
```

## 关联文档

- 鉴权设计:[ADR-002](../docs/adr/ADR-002-jwt-at-gateway.md)
- 限流规则:[docs/sentinel-rules.md](../docs/sentinel-rules.md)
- API 契约:[docs/api-contracts.md](../docs/api-contracts.md)
- 排障:[docs/troubleshooting.md](../docs/troubleshooting.md) §1 / §7 / §8
