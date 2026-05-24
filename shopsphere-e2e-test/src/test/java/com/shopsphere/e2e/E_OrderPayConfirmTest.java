package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.Awaits;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case f:支付 → Confirm,locked_stock 减、stock 不变、t_stock_tcc_log 出现 CONFIRM。 */
class E_OrderPayConfirmTest extends E2eBase {

    @Test
    void payConfirmsStock() {
        UserFactory.Account acc = UserFactory.register();
        long pid = cfg.seedProductId();

        long orderId = createOrder(acc.token(), pid);

        // Try 后:stock=99, locked=1
        assertThat(db.productStock(pid)).isEqualTo(cfg.seedStock() - 1);
        assertThat(db.productLockedStock(pid)).isEqualTo(1);

        Response pay = given().spec(ApiClient.as(acc.token())).post("/api/order/" + orderId + "/pay");
        assertThat(pay.jsonPath().getInt("code")).isEqualTo(0);
        assertThat(pay.jsonPath().getString("data.status")).isEqualTo("PAID");

        // Confirm 后:stock 不变,locked 归零
        Awaits.defaultAwait().untilAsserted(() -> {
            assertThat(db.productStock(pid)).isEqualTo(cfg.seedStock() - 1);
            assertThat(db.productLockedStock(pid)).isEqualTo(0);
            assertThat(db.countTccLog(orderId, pid, "CONFIRM")).isEqualTo(1);
        });
    }

    private long createOrder(String token, long pid) {
        Response r = given().spec(ApiClient.as(token))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(Map.of(
                        "items", List.of(Map.of("productId", pid, "quantity", 1)),
                        "addressId", cfg.seedAddressId()))
                .post("/api/order/create");
        assertThat(r.jsonPath().getInt("code")).isEqualTo(0);
        return r.jsonPath().getLong("data.orderId");
    }
}
