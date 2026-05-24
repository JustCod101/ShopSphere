package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.Awaits;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case k:冷启动 — 新用户无任何 behavior → fallback=true、code=0(C1)。 */
class I_RecommendColdStartTest extends E2eBase {

    @Test
    void newUserFallbackTrue() {
        UserFactory.Account acc = UserFactory.register();

        // 触发训练让 model_ready=1(否则冷启动也会落 model_not_ready 分支,fallback 同样 true,但稳起见先 train)
        given().spec(ApiClient.recoDirect()).post("/internal/recommend/train");
        Awaits.defaultAwait().untilAsserted(() -> {
            Response h = given().spec(ApiClient.anon()).get("/api/recommend/health");
            assertThat(h.jsonPath().getString("data.status")).isEqualTo("UP");
        });

        // 冷启动用户查推荐
        Response r = given().spec(ApiClient.as(acc.token()))
                .get("/api/recommend/user/" + acc.userId() + "?topk=5");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.jsonPath().getInt("code")).isEqualTo(0);
        assertThat(r.jsonPath().getBoolean("data.fallback")).isTrue();
    }
}
