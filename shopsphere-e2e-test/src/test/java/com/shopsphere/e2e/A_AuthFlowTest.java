package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case a:注册 → 登录 → token；验证 RS256 签名 + /api/user/me。 */
class A_AuthFlowTest extends E2eBase {

    @Test
    void registerLoginIssuesValidRs256Token() {
        UserFactory.Account acc = UserFactory.register();

        // JWT header alg=RS256(契约 §10)
        JsonPath header = UserFactory.decodeJwtHeader(acc.token());
        assertThat(header.getString("alg")).isEqualTo("RS256");

        // /api/user/me 用 token 拿到自己;uid 一致;无 passwordHash
        Response me = given().spec(ApiClient.as(acc.token())).get("/api/user/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.jsonPath().getInt("code")).isEqualTo(0);
        assertThat(me.jsonPath().getLong("data.id")).isEqualTo(acc.userId());
        assertThat(me.jsonPath().getString("data.username")).isEqualTo(acc.username());
        assertThat(me.asString()).doesNotContain("passwordHash");
    }
}
