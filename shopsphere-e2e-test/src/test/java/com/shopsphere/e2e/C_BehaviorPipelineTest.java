package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.Awaits;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.UserFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case c:行为埋点 ×3 → User MQ → Reco 消费 → behavior_event 出现 3 条。 */
class C_BehaviorPipelineTest extends E2eBase {

    @Test
    void behaviorFlowsToRecoDb() {
        UserFactory.Account acc = UserFactory.register();

        long[] items = { cfg.seedProductId(), cfg.seedProductId2(), cfg.seedProductId3() };
        for (long itemId : items) {
            given().spec(ApiClient.as(acc.token()))
                    .body(Map.of("itemId", itemId, "actionType", "view"))
                    .post("/api/user/behavior")
                    .then().statusCode(200).body("code", org.hamcrest.Matchers.equalTo(0));
        }

        Awaits.defaultAwait().untilAsserted(() ->
                assertThat(db.countBehaviorEvent(acc.userId()))
                        .as("3 behavior events landed in shopsphere_reco.behavior_event")
                        .isEqualTo(3));
    }
}
