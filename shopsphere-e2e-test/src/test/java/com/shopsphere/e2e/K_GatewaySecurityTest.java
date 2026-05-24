package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.E2eBase;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case m + n + o:白名单 / /internal/** 拦截 / 头剥离。 */
class K_GatewaySecurityTest extends E2eBase {

    /** case m。 */
    @Test
    void whitelistAndAuthRequired() {
        // 公开商品详情 — 不带 token 也通(白名单)
        Response prod = given().spec(ApiClient.anon()).get("/api/product/" + cfg.seedProductId());
        assertThat(prod.statusCode()).isEqualTo(200);
        assertThat(prod.jsonPath().getInt("code")).isEqualTo(0);

        // 订单列表 — 不带 token 拒绝 1001
        Response orders = given().spec(ApiClient.anon()).get("/api/order/list");
        assertThat(orders.jsonPath().getInt("code")).isEqualTo(1001);
    }

    /** case n:/internal/** 不可外访,返 1004。 */
    @Test
    void internalRouteBlocked() {
        Response r = given().spec(ApiClient.anon()).get("/internal/product/" + cfg.seedProductId());
        // 契约 §4.1:Gateway 显式拒绝 /internal/**,返路由不存在 1004。
        // 若实际实现返 1001 等,以源码为准 — 报告会显示真实 code,届时按 S5 协议停下报告。
        assertThat(r.jsonPath().getInt("code"))
                .as("internal route should be rejected, got body=%s", r.asString())
                .isEqualTo(1004);
    }

    /** case o:伪造 X-User-Id 头被 Gateway 剥离,下游收不到。 */
    @Test
    void fakeUserIdHeaderStripped() {
        Response r = given().spec(ApiClient.anon())
                .header("X-User-Id", "999")
                .get("/api/order/list");
        // 若 Gateway 剥离生效:仍然 1001 未认证
        // 若剥离失败:下游会拿到 999 并返 200 + 空列表 → 测试失败
        assertThat(r.jsonPath().getInt("code"))
                .as("X-User-Id header must be stripped by gateway, got=%s", r.asString())
                .isEqualTo(1001);
    }
}
