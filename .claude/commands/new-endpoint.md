为 $ARGUMENTS 服务添加一个新的 REST 接口。
步骤：
1. 在 controller 包下添加 Controller 方法
2. 在 service 包定义接口 + 实现
3. 在 dto 包添加请求/响应 DTO，加 @Valid 注解
4. 如需跨服务调用，在 shopsphere-api 对应模块加 Feign 接口
5. 编写 MockMvc 集成测试
6. 更新 docs/api-contracts.md

务必遵循根 CLAUDE.md 规范。