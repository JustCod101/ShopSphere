package com.shopsphere.e2e.support;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * 用 UUID 用户名注册 + 登录，返回 {@link Account}(uid, token, username, password)。
 * 每个 @Test 调一次即可——DbFixtures 已 truncate 用户表，所以 UUID 不会撞已存数据。
 */
public final class UserFactory {

    public record Account(long userId, String token, String username, String password) {}

    public static Account register() {
        String username = "e2e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String password = "Passw0rd!";
        return register(username, password);
    }

    public static Account register(String username, String password) {
        // 1) /api/user/register
        Response reg = given().spec(ApiClient.anon())
                .body(Map.of(
                        "username", username,
                        "password", password,
                        "email",    username + "@e2e.local",
                        "phone",    "13800" + String.format("%06d", (int)(Math.random() * 1_000_000))
                ))
                .post("/api/user/register");
        if (reg.statusCode() != 200 || reg.jsonPath().getInt("code") != 0) {
            throw new IllegalStateException("register failed: " + reg.asString());
        }
        long uid = reg.jsonPath().getLong("data.id");

        // 2) /api/user/login
        Response login = given().spec(ApiClient.anon())
                .body(Map.of("username", username, "password", password))
                .post("/api/user/login");
        if (login.statusCode() != 200 || login.jsonPath().getInt("code") != 0) {
            throw new IllegalStateException("login failed: " + login.asString());
        }
        String token = login.jsonPath().getString("data.token");
        return new Account(uid, token, username, password);
    }

    /** 仅取 JWT header 用于断言 alg=RS256 等。 */
    public static JsonPath decodeJwtHeader(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("not a JWT: " + token);
        String headerJson = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
        return new JsonPath(headerJson);
    }
}
