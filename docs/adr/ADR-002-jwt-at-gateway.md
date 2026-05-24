# ADR-002: 网关统一 JWT(RS256)

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-20(commit `b63e2d2` T1.2)
- 关联: [api-contracts §3](../api-contracts.md) / `shopsphere-gateway/src/main/java/.../GwAuthFilter.java` / `shopsphere-common/.../HeaderConstant.java`

## 背景

每个微服务都自己校验 JWT 会带来:
- 公钥分发 / 续期分散
- 业务代码混入鉴权,违反单一职责
- 修改 JWT 算法或 claim 时需改 N 处

选择验签位置 + 算法是关键设计点。

## 候选方案

- **A. 业务服务各自验签(HS256 共享密钥)** — 简单;密钥泄露面广、变更难
- **B. 网关验签 + 透传 token,业务也校验一次** — 双重保险;重复计算 + 密钥仍需下发
- **C. 网关唯一验签(RS256 非对称),透传 `X-User-Id/Name`** — 集中验签;业务零鉴权代码,但要求剥头(防伪造)

## 决策

选 **C**。

- 算法:**RS256**(私钥仅 User 服务签发;公钥下发到 Gateway 校验)
- 校验点:**仅 Gateway**(`GwAuthFilter`);业务服务不引入任何 JWT 库
- 透传契约:`X-User-Id` / `X-User-Name` / `X-Trace-Id`(全为字符串;固定见 `HeaderConstant.java`)
- 防伪造:**剥头逻辑** — 客户端请求头里若自带 `X-User-Id`,网关无条件覆盖/删除
- 公开接口白名单:在 `application.yml` 显式列(register/login/swagger 等)
- 业务服务读用户:`UserContextHolder.getUserId()`(由 `UserContextInterceptor` 从 header 注入到 ThreadLocal,Feign 调用通过 `FeignAuthInterceptor` 续传)

## 后果

**正面**
- 算法升级、claim 调整、token 撤销均在 Gateway 一处
- 业务服务测试不需要构造 JWT,只塞 header 即可
- 公私钥分离:即使公钥泄露也不能签发新 token

**负面 / 代价**
- Gateway 单点风险(已通过 Sentinel 限流 + 健康探活 + 多实例部署缓解)
- Feign 调用必须装拦截器透传上下文,否则下游拿不到 userId(`CommonFeignAutoConfiguration` 自动配)

**后续动作**
- 任何业务服务出现 `JwtUtil.verify()` 调用 → 立即报告违反本 ADR(`shopsphere-common/util/JwtUtil.java` 仅 User 签发用)
