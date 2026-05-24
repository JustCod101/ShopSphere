# ADR-006: 取消 Java → Py Feign 调用

- 状态: Accepted(补录于 2026-05-24)
- 日期: 2026-05-23(commit `d059843` T4.1 拍板 / `4fbfbad` T4.3 上线)
- 关联: [api-contracts §4.1 / §4.2 / §C2](../api-contracts.md) / [ADR-005](ADR-005-recsys-own-db.md)

## 背景

初版设计草稿允许 Order 服务通过 Feign 调用 Recommendation 服务,用于"下单成功后实时获取相关商品"。引入 Java→Py Feign 客户端后,矛盾凸显:

- **契约冗余**:Feign 接口在 Java 侧需要手写 DTO,Py 侧 OpenAPI 自带契约 → 两边维护
- **熔断耦合**:Py 推荐冷启动或离线训练时 P99 抖动 → Java 侧 Feign 超时 → Sentinel 熔断,业务报错
- **越权风险**:Java 服务以服务账号调 Py,Py 难判断真实用户上下文
- **测试痛**:E2E 必须起 Py;单纯 Java 单测无法 mock 出 Py 行为

api-contracts §C2 在拍板阶段明确"取消 Java→Py Feign";本 ADR 记录决策依据。

## 候选方案

- **A. 保留 Java→Py Feign**(初版) — 一次调用直返;耦合见上述
- **B. Java 不直接调 Py,前端走 Gateway 两次请求**(订单接口 + 推荐接口) — 完全解耦;前端多一次往返
- **C. 后端 BFF 聚合**(网关插件聚合 Java + Py 结果) — 体验最好;开发成本最高,本项目阶段不必要

## 决策

选 **B**。

- **Java 侧零 Feign 依赖 Py**:全工程 `grep "RecommendationFeignClient"` 无匹配(`shopsphere-api/` 下只有 `user-api / product-api / order-api`)
- **前端调用**:浏览/下单完成后,前端**单独**调用 `GET /api/recommend/...` → Gateway 路由到 Py
- **Gateway 路由**:`spring.cloud.gateway.routes` 配 `id=recommend, uri=lb://shopsphere-recommendation`(Nacos discovery,Py 通过 nacos-sdk-python 注册)
- **Sentinel 隔离**:`/api/recommend/**` 单独限流规则;失败码桶分离

## 后果

**正面**
- Java 业务路径不被 Py 拖累;推荐故障 → 仅推荐位空 / fallback
- Feign 契约维护减少 1 套
- Py 可独立蓝绿发布 / 限流 / 摘流量,不影响 Java

**负面 / 代价**
- 前端多一次网络往返(可缓存推荐结果到端上)
- 跨 Java/Py 的端到端追踪需依赖 `X-Trace-Id` header 透传(已在 ADR-002 契约中规定)

**后续动作**
- 任何 Java 服务出现 `RecommendationFeignClient`、`@FeignClient(name="shopsphere-recommendation")` → 立即报告违反本 ADR
- 若后续需要后端聚合(C 方案),应新建 BFF 层而非让业务服务直调 Py
