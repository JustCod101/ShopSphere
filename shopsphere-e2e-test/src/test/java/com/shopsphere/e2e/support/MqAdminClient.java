package com.shopsphere.e2e.support;

import com.shopsphere.e2e.E2eConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RabbitMQ HTTP API 极简包装。
 * 仅用于 case h 前置：读 {@code q.order.timeout.wait} 的 x-message-ttl 验证 queueTtlMs 已生效。
 */
public final class MqAdminClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String base = E2eConfig.get().rabbitApi();
    private final String auth;

    public MqAdminClient() {
        String tok = E2eConfig.get().rabbitUser() + ":" + E2eConfig.get().rabbitPass();
        this.auth = "Basic " + Base64.getEncoder().encodeToString(tok.getBytes(StandardCharsets.UTF_8));
    }

    /** 读队列 raw JSON（vhost=shopsphere）。失败返 null。 */
    public String queueRaw(String vhost, String queueName) {
        try {
            String url = base + "/api/queues/" + java.net.URLEncoder.encode(vhost, StandardCharsets.UTF_8)
                    + "/" + queueName;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", auth)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
