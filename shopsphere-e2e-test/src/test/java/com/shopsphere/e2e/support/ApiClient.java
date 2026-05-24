package com.shopsphere.e2e.support;

import com.shopsphere.e2e.E2eConfig;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * RestAssured 薄封装。两种入口：
 *   {@link #anon()} —— 不带 token，命中白名单或负面测试
 *   {@link #as(String)} —— 带 Bearer token，正常用户请求
 *
 * 不在这里做断言；返回 {@link Response} 让测试自己 JsonPath 提取。
 */
public final class ApiClient {

    private static final String GW = E2eConfig.get().gatewayBase();

    private ApiClient() {}

    public static RequestSpecification anon() {
        return new RequestSpecBuilder()
                .setBaseUri(GW)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    public static RequestSpecification as(String token) {
        return new RequestSpecBuilder()
                .setBaseUri(GW)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("Authorization", "Bearer " + token)
                .build();
    }

    public static RequestSpecification recoDirect() {
        return new RequestSpecBuilder()
                .setBaseUri(E2eConfig.get().recoBase())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }

    static { RestAssured.config = RestAssured.config(); }
}
