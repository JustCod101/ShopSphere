# ShopSphere 项目约定

## 技术栈锁定
- Spring Boot 3.2.x（不要升级，Seata 兼容性敏感）
- Spring Cloud 2023.0.x + Spring Cloud Alibaba 2023.0.1.0
- JDK 17
- Python 3.11

## 编码规范
- 包名前缀：com.shopsphere.{service}
- Controller 只做参数校验和编排，业务逻辑必须在 Service 层
- 所有外部接口返回 Result<T> 统一包装
- 异常用 BusinessException + 全局 @RestControllerAdvice
- Feign 调用必须有 fallback（Sentinel）
- 数据库变更只能通过 Flyway，不允许手改

## 命名约定
- Mapper: XxxMapper（MyBatis-Plus BaseMapper）
- Service 接口: XxxService，实现: XxxServiceImpl
- DTO: XxxDTO (传输) / XxxVO (返回前端) / XxxBO (内部)
- Feign 接口: XxxFeignClient

## 必读文件
开始任何任务前先读：
- docs/architecture.md
- docs/api-contracts.md
- 对应服务的 README.md

## 禁止事项
- 禁止在业务服务中校验 JWT（网关已处理）
- 禁止直接 new Date()，统一使用 java.time；对外/跨服务字段用 OffsetDateTime(UTC)，纯进程内无歧义场景方可 LocalDateTime（详见 docs/api-contracts.md §1.1）
- 禁止在 Controller 直接调 Mapper
- 禁止跨服务直接访问对方数据库