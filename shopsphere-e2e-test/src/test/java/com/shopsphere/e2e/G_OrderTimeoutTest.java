package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.Awaits;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.MqAdminClient;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * case h:超时自动取消。
 * <p>**前置条件**:queueTtlMs=30000 已通过 Nacos 推到 Order,且 docker compose down -v
 * 重起过(队列以 30s TTL 重声明)。详见 scripts/e2e-set-timeout.sh + docs/deployment.md。
 *
 * <p>@Tag("timeout") → 仅 mvn -Pe2e,e2e-slow 才跑。
 */
@Tag("timeout")
class G_OrderTimeoutTest extends E2eBase {

    @BeforeAll
    static void checkQueueTtl() {
        // 校验 RabbitMQ 真的拿到了 30s TTL,失败直接 fail(避免空跑 60s)
        String raw = new MqAdminClient().queueRaw("shopsphere", "q.order.timeout.wait");
        if (raw == null) {
            throw new IllegalStateException("RabbitMQ /api/queues/shopsphere/q.order.timeout.wait 不可达,"
                    + "是否未启 stack 或 management 插件未启?");
        }
        // 简陋 string match;严格 JSON 解析对依赖太重
        if (!raw.contains("\"x-message-ttl\":30000")) {
            throw new IllegalStateException(
                    "q.order.timeout.wait 当前 TTL 不是 30000ms。请按 scripts/e2e-set-timeout.sh 推 Nacos 后 compose down -v 重起。raw=" + raw);
        }
    }

    @Test
    void autoCancelOnTimeout() {
        UserFactory.Account acc = UserFactory.register();
        long pid = cfg.seedProductId();

        Response create = given().spec(ApiClient.as(acc.token()))
                .header("X-Request-Id", UUID.randomUUID().toString())
                .body(Map.of(
                        "items", List.of(Map.of("productId", pid, "quantity", 1)),
                        "addressId", cfg.seedAddressId()))
                .post("/api/order/create");
        long orderId = create.jsonPath().getLong("data.orderId");

        // 30s TTL + DLX + consumer 处理 → 60s 内必定 CANCELLED
        Awaits.longAwait().untilAsserted(() ->
                assertThat(db.orderStatus(orderId)).isEqualTo("CANCELLED"));

        // 库存回补
        assertThat(db.productStock(pid)).isEqualTo(cfg.seedStock());
        assertThat(db.productLockedStock(pid)).isEqualTo(0);
    }
}
