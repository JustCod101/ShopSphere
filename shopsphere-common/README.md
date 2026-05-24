# shopsphere-common

## 职责

跨服务共享的基础组件库,**仅被 Java 业务服务依赖**(不被 Gateway 或 Python 服务依赖)。提供:
- **统一响应**:`Result<T>` / `PageResult<T>` / `ErrorCode` 枚举
- **全局异常**:`BusinessException` + `GlobalExceptionHandler`
- **用户上下文**:`UserContextHolder` / `UserContextInterceptor`(从 Gateway 注入的 header 读 userId/userName)
- **Feign 上下文透传**:`FeignAuthInterceptor`(`X-User-Id` / `X-Trace-Id` 自动续传到下游)
- **JWT 签发工具**:`JwtUtil`(**仅 User 服务签发用,任何服务不得用于校验** — 见 [ADR-002](../docs/adr/ADR-002-jwt-at-gateway.md))
- **Header 常量**:`HeaderConstant`(`X-User-Id` / `X-User-Name` / `X-Trace-Id`)
- **公开接口标记**:`@PublicApi`(被标的 controller 方法跳过 `UserContextInterceptor`)
- **自动装配**:`CommonWebAutoConfiguration` / `CommonFeignAutoConfiguration`(`spring.factories` 注册)

## 端口 / 依赖

| 项 | 值 |
|---|---|
| 端口 | 无(纯 lib) |
| 数据库 | 无 |
| 关键依赖 | Spring Boot Web / Feign / Validation / Lombok / jjwt(签发 only) |

## 本地构建

```bash
mvn -pl shopsphere-common -am install
```

## 关键代码导航

| 路径 | 说明 |
|---|---|
| `result/Result.java` | `{code, message, data, timestamp}` 统一响应壳 |
| `result/ErrorCode.java` | 错误码枚举(`1xxx` 通用 / `2xxx` 用户 / `3xxx` 商品 / `4xxx` 订单 / `5xxx` 监控) |
| `result/PageResult.java` | 分页响应 |
| `exception/BusinessException.java` | 业务异常(带 ErrorCode) |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`,所有异常 → `Result.error()` |
| `context/HeaderConstant.java` | header 名称常量(**禁止散在代码里写字符串**) |
| `context/UserContextHolder.java` | `ThreadLocal<UserContext>`,业务侧读 userId |
| `context/UserContextInterceptor.java` | 拦截请求,从 header 注入 ThreadLocal |
| `context/PublicApi.java` | 标注 controller 方法跳过用户上下文校验 |
| `feign/FeignAuthInterceptor.java` | Feign 请求自动续传 header |
| `util/JwtUtil.java` | RS256 签发(私钥);**不要在业务服务调** |
| `config/CommonWebAutoConfiguration.java` | 自动注册拦截器 |
| `config/CommonFeignAutoConfiguration.java` | 自动注册 Feign 拦截器 |
| `META-INF/spring/...AutoConfiguration.imports` | Spring Boot 3 自动装配入口 |

## 使用模式

业务服务 `pom.xml`:
```xml
<dependency>
    <groupId>com.shopsphere</groupId>
    <artifactId>shopsphere-common</artifactId>
    <version>${project.version}</version>
</dependency>
```

Controller 写法:
```java
@RestController
public class XxxController {
    @PostMapping("/xxx")
    public Result<XxxVO> doIt(@RequestBody @Valid XxxDTO dto) {
        Long uid = UserContextHolder.requireUserId();   // 自动从 X-User-Id 读
        return Result.ok(service.doIt(uid, dto));
    }
}
```

抛业务异常:
```java
throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
// 由 GlobalExceptionHandler 转 Result.error(3002, "库存不足")
```

## 测试

```bash
mvn -pl shopsphere-common test
# 覆盖:ResultAndErrorCodeTest / UserContextInterceptorTest
```

## 关联文档

- 网关鉴权契约:[ADR-002](../docs/adr/ADR-002-jwt-at-gateway.md)
- API 契约:[docs/api-contracts.md](../docs/api-contracts.md) §1 §2 §3
- CLAUDE.md 编码规范(项目约定)
