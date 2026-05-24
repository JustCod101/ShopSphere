package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.Awaits;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.RedisClient;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case d + e:下单幂等 + 下单后各侧面验证(状态/库存/Redis/本地消息/积分/payExpireAt)。 */
class D_OrderCreateIdempotentTest extends E2eBase {

    @Test
    void sameRequestIdReturnsSameOrder() {
        UserFactory.Account acc = UserFactory.register();
        String reqId = UUID.randomUUID().toString();

        long pid = cfg.seedProductId();
        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", pid, "quantity", 1)),
                "addressId", cfg.seedAddressId()
        );

        Response r1 = given().spec(ApiClient.as(acc.token()))
                .header("X-Request-Id", reqId)
                .body(body).post("/api/order/create");
        Response r2 = given().spec(ApiClient.as(acc.token()))
                .header("X-Request-Id", reqId)
                .body(body).post("/api/order/create");

        assertThat(r1.jsonPath().getInt("code")).isEqualTo(0);
        assertThat(r2.jsonPath().getInt("code")).isEqualTo(0);
        long oid1 = r1.jsonPath().getLong("data.orderId");
        long oid2 = r2.jsonPath().getLong("data.orderId");
        assertThat(oid1).isEqualTo(oid2);
        // DB 只有 1 条
        assertThat(db.countOrdersByUser(acc.userId())).isEqualTo(1);
    }

    @Test
    void createReservesTccAndOutboxAndPoints() {
        UserFactory.Account acc = UserFactory.register();
        long pid = cfg.seedProductId();
        String reqId = UUID.randomUUID().toString();

        Response r = given().spec(ApiClient.as(acc.token()))
                .header("X-Request-Id", reqId)
                .body(Map.of(
                        "items", List.of(Map.of("productId", pid, "quantity", 1)),
                        "addressId", cfg.seedAddressId()))
                .post("/api/order/create");

        assertThat(r.jsonPath().getInt("code")).isEqualTo(0);
        long orderId = r.jsonPath().getLong("data.orderId");
        String status = r.jsonPath().getString("data.status");
        assertThat(status).isEqualTo("CREATED");
        String totalAmount = r.jsonPath().getString("data.totalAmount");
        assertThat(totalAmount).matches("\\d+\\.\\d{2}");
        // payExpireAt 解析为 UTC OffsetDateTime,且 ≈ now + 30s 或 30min
        String payExpireAtStr = r.jsonPath().getString("data.payExpireAt");
        OffsetDateTime expire = OffsetDateTime.parse(payExpireAtStr);
        long deltaSec = java.time.Duration.between(OffsetDateTime.now(java.time.ZoneOffset.UTC), expire).getSeconds();
        // 允许 30s ± 10s 或 30min ± 60s
        boolean okShort = deltaSec >= 20 && deltaSec <= 40;
        boolean okLong  = deltaSec >= 30*60 - 60 && deltaSec <= 30*60 + 60;
        assertThat(okShort || okLong).as("payExpireAt ≈ now+30s or now+30min, got delta=%ds", deltaSec).isTrue();

        // TCC-Try 已 DB 落:可售 stock=99, locked=1
        assertThat(db.productStock(pid)).isEqualTo(cfg.seedStock() - 1);
        assertThat(db.productLockedStock(pid)).isEqualTo(1);

        // Redis 库存也被 Lua 预扣到 99
        try (RedisClient redis = new RedisClient()) {
            Long redisStock = redis.getLong("stock:product:" + pid);
            assertThat(redisStock).isEqualTo((long) cfg.seedStock() - 1);
        }

        // t_local_message 中 order.created 最终 CONFIRMED(outbox + publisher-confirm)
        String orderNo = db.orderNoOf(orderId);
        Awaits.defaultAwait().untilAsserted(() ->
                assertThat(db.countLocalMessageWithStatus(orderNo, "order.created", 2))
                        .as("order.created CONFIRMED").isEqualTo(1));

        // 用户积分被 PointsConsumer 累加(异步,等)
        Awaits.defaultAwait().untilAsserted(() ->
                assertThat(db.countUserPoints(acc.userId()))
                        .as("t_user_points has row").isGreaterThanOrEqualTo(1));
    }
}
