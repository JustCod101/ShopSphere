在 $ARGUMENTS 模块添加 Feign 客户端。
- 接口放在 shopsphere-api/{target}-api 模块
- 必须配置 @FeignClient(name=..., fallback=...)
- 实现 fallback 类（Sentinel 降级）
- 在调用方 @EnableFeignClients basePackages 中确认已扫描