# ShopSphere E2E 报告

> 模板。运行 `mvn -f shopsphere-e2e-test/pom.xml -Pe2e verify` 后由 `ReportListener` 自动覆盖。

- Suite 开始: _未执行_
- 总用例数: 0
- **PASS = 0 / FAIL = 0 / SKIP = 0**

| 用例 | 状态 | 耗时(ms) | 备注 |
|---|---|---:|---|
| _未执行_ |  |  |  |

## case 覆盖矩阵(参考)

| Case | 验证拍板项 | 类#方法 |
|---|---|---|
| a | RS256 + 注册/登录/me | `A_AuthFlowTest#registerLoginIssuesValidRs256Token` |
| b | 缓存命中 + BigDecimal + UTC OffsetDateTime | `B_ProductBrowseTest#listAndDetailCacheAndUtc` |
| c | 行为埋点 → MQ → reco | `C_BehaviorPipelineTest#behaviorFlowsToRecoDb` |
| d | S5 幂等(X-Request-Id) | `D_OrderCreateIdempotentTest#sameRequestIdReturnsSameOrder` |
| e | TCC-Try + 本地消息 + 积分 + payExpireAt | `D_OrderCreateIdempotentTest#createReservesTccAndOutboxAndPoints` |
| f | TCC-Confirm | `E_OrderPayConfirmTest#payConfirmsStock` |
| g | TCC-Cancel + Redis 回补 | `F_OrderCancelTest#cancelReleasesStock` |
| h | 超时自动取消(@Tag timeout) | `G_OrderTimeoutTest#autoCancelOnTimeout` |
| i | 越权 4001 | `H_OrderForbiddenTest#userBCannotReadUserAOrder` |
| j | SHIPPED 状态机 4002 | `H_OrderForbiddenTest#cancelOnShippedReturns4002` |
| k | 冷启动 fallback=true | `I_RecommendColdStartTest#newUserFallbackTrue` |
| l | 正常推荐非空 | `J_RecommendWarmTest#userWithBehaviorNonEmpty` |
| m | 白名单 + 1001 | `K_GatewaySecurityTest#whitelistAndAuthRequired` |
| n | /internal/** → 1004 | `K_GatewaySecurityTest#internalRouteBlocked` |
| o | 头剥离 | `K_GatewaySecurityTest#fakeUserIdHeaderStripped` |
