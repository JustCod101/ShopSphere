package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case i + j:越权返 4001 / SHIPPED 调 cancel 返 4002。 */
class H_OrderForbiddenTest extends E2eBase {

    @Test
    void userBCannotReadUserAOrder() {
        UserFactory.Account a = UserFactory.register();
        UserFactory.Account b = UserFactory.register();
        long pid = cfg.seedProductId();

        Response create = given().spec(ApiClient.as(a.token()))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(Map.of(
                        "items", List.of(Map.of("productId", pid, "quantity", 1)),
                        "addressId", cfg.seedAddressId()))
                .post("/api/order/create");
        long aOrderId = create.jsonPath().getLong("data.orderId");

        // B 用 token 查 A 的单 → 4001 (不存在/非本人不区分,§6.3)
        Response peek = given().spec(ApiClient.as(b.token())).get("/api/order/" + aOrderId);
        assertThat(peek.jsonPath().getInt("code")).isEqualTo(4001);
    }

    @Test
    void cancelOnShippedReturns4002() {
        UserFactory.Account a = UserFactory.register();
        long pid = cfg.seedProductId();

        Response create = given().spec(ApiClient.as(a.token()))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(Map.of(
                        "items", List.of(Map.of("productId", pid, "quantity", 1)),
                        "addressId", cfg.seedAddressId()))
                .post("/api/order/create");
        long orderId = create.jsonPath().getLong("data.orderId");

        // 直接 UPDATE 到 SHIPPED(没有公开 /ship,E2E 测状态机用)
        db.forceOrderStatus(orderId, "SHIPPED");

        Response cancel = given().spec(ApiClient.as(a.token())).post("/api/order/" + orderId + "/cancel");
        assertThat(cancel.jsonPath().getInt("code")).isEqualTo(4002);
    }
}
