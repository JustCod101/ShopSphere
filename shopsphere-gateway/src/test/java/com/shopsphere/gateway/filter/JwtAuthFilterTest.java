package com.shopsphere.gateway.filter;

import com.shopsphere.common.util.JwtUtil;
import com.shopsphere.gateway.config.WhitelistProperties;
import com.shopsphere.gateway.security.JwtPublicKeyProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * T1.2 鉴权链测试（WebTestClient）。
 *
 * <p>串真实 {@code RequestLogFilter}（剥离三头+生成 traceId）→ 真实 {@code InternalAccessRejectFilter}
 * （/internal 1004）→ {@code JwtAuthFilter}（GlobalFilter 经 WebFilter 适配器）→ echo handler
 * （回 200，并把收到的 X-User-* 反射进响应头以便断言剥离/注入）。
 */
class JwtAuthFilterTest {

    private static KeyPair keyPair;
    private static String validToken;     // userId=10, userName=alice
    private static String expiredToken;   // userId=10, 已过期

    @BeforeAll
    static void keys() {
        keyPair = JwtUtil.generateRsaKeyPair(2048);
        validToken = JwtUtil.signWithPrivateKey(keyPair.getPrivate(),
                Map.of("userId", 10, "userName", "alice"), Duration.ofMinutes(5));
        expiredToken = JwtUtil.signWithPrivateKey(keyPair.getPrivate(),
                Map.of("userId", 10, "userName", "alice"), Duration.ofSeconds(-10));
    }

    private WebTestClient client(PublicKey pubKey) {
        WhitelistProperties wl = new WhitelistProperties();
        wl.setWhitelist(List.of(
                "/api/user/register", "/api/user/login", "/api/product/**",
                "/api/recommend/similar/**", "/api/recommend/health"));
        JwtPublicKeyProvider keyProvider = new JwtPublicKeyProvider(null, "d", "g", 1) {
            @Override
            public PublicKey publicKey() {
                return pubKey;
            }
        };
        JwtAuthFilter jwt = new JwtAuthFilter(wl, keyProvider);
        WebFilter jwtAsWebFilter = (exchange, chain) ->
                jwt.filter(exchange, (GatewayFilterChain) chain::filter);

        return WebTestClient.bindToWebHandler(exchange -> {
            HttpHeaders in = exchange.getRequest().getHeaders();
            HttpHeaders out = exchange.getResponse().getHeaders();
            if (in.getFirst("X-User-Id") != null) {
                out.set("Echo-User-Id", in.getFirst("X-User-Id"));
            }
            if (in.getFirst("X-User-Name") != null) {
                out.set("Echo-User-Name", in.getFirst("X-User-Name"));
            }
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        }).webFilter(new RequestLogFilter(), new InternalAccessRejectFilter(), jwtAsWebFilter).build();
    }

    /** A：白名单无 token → 放行 200，未注入 X-User-Id。 */
    @Test
    void whitelist_passthrough_noUserHeader() {
        client(keyPair.getPublic()).get().uri("/api/product/123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Echo-User-Id")
                .expectHeader().doesNotExist("X-Trace-Id"); // §3 不回客户端
    }

    /** B：非白名单无 token → HTTP 200 + code 1001 + traceId。 */
    @Test
    void protected_noToken_1001() {
        client(keyPair.getPublic()).get().uri("/api/order/list")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(1001)
                .jsonPath("$.traceId").isNotEmpty();
    }

    /** C：非白名单 + 过期 token → 1001。 */
    @Test
    void protected_expiredToken_1001() {
        client(keyPair.getPublic()).get().uri("/api/order/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(1001);
    }

    /** D：伪造 X-User-Id=999 + 合法 token(userId=10) → 下游收到 X-User-Id=10。 */
    @Test
    void forgedHeaderStripped_realIdInjected() {
        client(keyPair.getPublic()).get().uri("/api/order/list")
                .header("X-User-Id", "999")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Echo-User-Id", "10")
                .expectHeader().valueEquals("Echo-User-Name", "alice");
    }

    /** E：/internal/** 任何情况 → HTTP 403 + 1004（T1.1 拒绝优先于鉴权）。 */
    @Test
    void internal_rejected_1004_evenWithToken() {
        client(keyPair.getPublic()).get().uri("/internal/product/stock/try")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(1004);
    }

    /** L4：大小写变体 /INTERNAL/** 仍 → 403 + 1004（防御纵深归一）。 */
    @Test
    void internalUpperCase_rejected_1004() {
        client(keyPair.getPublic()).get().uri("/INTERNAL/product/stock/try")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(1004);
    }

    /** B1：受保护路径的 CORS 预检 OPTIONS 无 token → 放行（不被判 1001）。 */
    @Test
    void protectedOptionsPreflight_passthrough() {
        client(keyPair.getPublic()).options().uri("/api/order/list")
                .header(HttpHeaders.ORIGIN, "http://x.test")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Echo-User-Id");
    }

    /** S6：伪造头大小写/多值变体 → 全部剥离，下游仅网关注入的真实 userId。 */
    @Test
    void forgedHeaderCaseAndDuplicateStripped() {
        client(keyPair.getPublic()).get().uri("/api/order/list")
                .header("x-user-id", "999")
                .header("X-USER-ID", "888")
                .header("X-User-Name", "evil")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Echo-User-Id", "10")
                .expectHeader().valueEquals("Echo-User-Name", "alice");
    }

    /** 公钥未就绪（fail-closed）→ 受保护路径 1001。 */
    @Test
    void noPublicKey_failClosed_1001() {
        client(null).get().uri("/api/order/list")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.code").isEqualTo(1001);
    }
}
