package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.Awaits;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.RedisClient;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case g:取消 → Cancel,locked 减、stock 加回、Redis 回补、t_stock_tcc_log 出现 CANCEL。 */
class F_OrderCancelTest extends E2eBase {

    @Test
    void cancelReleasesStock() {
        UserFactory.Account acc = UserFactory.register();
        long pid = cfg.seedProductId();

        // 下单
        Response create = given().spec(ApiClient.as(acc.token()))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(Map.of(
                        "items", List.of(Map.of("productId", pid, "quantity", 1)),
                        "addressId", cfg.seedAddressId()))
                .post("/api/order/create");
        long orderId = create.jsonPath().getLong("data.orderId");

        // 取消
        Response cancel = given().spec(ApiClient.as(acc.token())).post("/api/order/" + orderId + "/cancel");
        assertThat(cancel.jsonPath().getInt("code")).isEqualTo(0);
        assertThat(cancel.jsonPath().getString("data.status")).isEqualTo("CANCELLED");

        // Cancel 后:stock 回到 100, locked=0, Redis 也回补
        Awaits.defaultAwait().untilAsserted(() -> {
            assertThat(db.productStock(pid)).isEqualTo(cfg.seedStock());
            assertThat(db.productLockedStock(pid)).isEqualTo(0);
            assertThat(db.countTccLog(orderId, pid, "CANCEL")).isEqualTo(1);
        });

        try (RedisClient redis = new RedisClient()) {
            assertThat(redis.getLong("stock:product:" + pid)).isEqualTo((long) cfg.seedStock());
        }
    }
}
