# shopsphere-api

## 职责

跨服务 Feign 契约模块。**仅放接口和 DTO**,不放实现。

子模块:

| 子模块 | 内容 | 被谁依赖 |
|---|---|---|
| `user-api` | `UserFeignClient` + `UserFeignFallback` + `UserDTO` | order(查买家信息) |
| `product-api` | `ProductFeignClient` + `Fallback` + `StockTccDTO/StockItem/ProductDetailDTO` | order(库存 TCC + 商品快照) |
| `order-api` | `OrderFeignClient` + `Fallback` + 事件 payload(`OrderCreatedEvent` / `OrderItemPayload`) | 暂未被外部 Java 服务消费;事件 payload 被 reco(Py)消费,Java 侧仅作为 schema 参考 |

## 端口 / 依赖

| 项 | 值 |
|---|---|
| 端口 | 无(纯契约 lib) |
| 数据库 | 无 |
| 关键依赖 | Spring Cloud OpenFeign / Sentinel(`@SentinelResource` + fallback) |

## 设计原则

1. **`@FeignClient(name=)` 名称必须与 Nacos 注册名一致**(`shopsphere-user` / `shopsphere-product` / `shopsphere-order`)
2. **每个 client 必须有 `fallback` 类**(CLAUDE.md "Feign 调用必须有 fallback(Sentinel)")
3. **接口路径以 `/internal/**` 开头**(内部接口,Gateway 强制拒绝外部访问)
4. **DTO 字段保留向后兼容**(可加,不可改 / 不可删)— 跨服务版本不同步时不破坏调用
5. **不允许出现 `RecommendationFeignClient`**(见 [ADR-006](../docs/adr/ADR-006-no-java-py-feign.md))— 推荐服务前端直调,Java 不依赖

## 关键代码导航

| 路径 | 说明 |
|---|---|
| `user-api/.../UserFeignClient.java` | `GET /internal/user/{id}` |
| `user-api/.../UserFeignFallback.java` | 熔断/超时时返默认用户(脱敏) |
| `product-api/.../ProductFeignClient.java` | `POST /internal/product/stock/{try,confirm,cancel}` + `GET /internal/product/{id}` |
| `product-api/.../ProductFeignFallback.java` | Try 失败 → 抛 BusinessException(3002);Confirm/Cancel 失败 → 不吞,让 Seata 重试 |
| `product-api/.../StockTccDTO.java` | TCC 请求体(`orderId, items[{productId,quantity}]`) |
| `order-api/.../OrderFeignClient.java` | 跨服务查订单(暂无业务消费,留契约) |
| `order-api/.../event/OrderCreatedEvent.java` | 订单创建事件 schema(MQ 投递载荷,reco 消费) |
| `order-api/.../event/OrderItemPayload.java` | 订单明细 schema |

## 使用模式

业务服务 `pom.xml`:
```xml
<dependency>
    <groupId>com.shopsphere</groupId>
    <artifactId>product-api</artifactId>
    <version>${project.version}</version>
</dependency>
```

启动类:
```java
@EnableFeignClients(basePackages = "com.shopsphere.api")
public class OrderApplication { ... }
```

注入调用:
```java
@Autowired
private ProductFeignClient productClient;

Result<Void> r = productClient.stockTry(StockTccDTO.of(orderId, items));
if (r.getCode() != 0) throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH);
```

## 测试

API 模块本身不测试(纯接口),验证集中在被依赖方:
```bash
mvn -pl shopsphere-product test
mvn -pl shopsphere-e2e-test test
```

## 关联文档

- TCC 契约:[ADR-003](../docs/adr/ADR-003-stock-seata-tcc.md) / [api-contracts §4.3](../docs/api-contracts.md)
- 无 Java→Py Feign:[ADR-006](../docs/adr/ADR-006-no-java-py-feign.md)
