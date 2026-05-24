package com.shopsphere.e2e;

import com.shopsphere.e2e.support.ApiClient;
import com.shopsphere.e2e.support.E2eBase;
import com.shopsphere.e2e.support.UserFactory;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** case b:浏览商品列表 + 详情 ×3;验证缓存(粗判)、BigDecimal 价格精度、UTC OffsetDateTime 格式。 */
class B_ProductBrowseTest extends E2eBase {

    @Test
    void listAndDetailCacheAndUtc() {
        UserFactory.Account acc = UserFactory.register();

        // 1) list 公开:不带 token 也通(case m 验,这里走 token 路径)
        Response list = given().spec(ApiClient.as(acc.token()))
                .queryParam("page", 1).queryParam("size", 20)
                .get("/api/product/list");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.jsonPath().getInt("code")).isEqualTo(0);
        assertThat(list.jsonPath().getList("data.records").size()).isGreaterThan(0);
        // BigDecimal 价格保留两位小数(契约 §1.1 / 商品种子)
        String priceStr = list.jsonPath().getString("data.records[0].price");
        assertThat(priceStr).matches("\\d+\\.\\d{2}");

        // 2) detail ×3
        long pid = cfg.seedProductId();
        long t1 = timeDetail(acc.token(), pid);
        long t2 = timeDetail(acc.token(), pid);
        long t3 = timeDetail(acc.token(), pid);
        // 粗判:缓存命中后总耗时显著小于首次。本地 docker 抖动大,做软断言:三次中至少两次 < 200ms
        long fast = (t1 < 200 ? 1 : 0) + (t2 < 200 ? 1 : 0) + (t3 < 200 ? 1 : 0);
        assertThat(fast).as("cache hits make >=2 of 3 detail calls fast (<200ms)").isGreaterThanOrEqualTo(2);

        // 3) timestamp UTC offset 检查(契约 §1.1)
        Response detail = given().spec(ApiClient.as(acc.token())).get("/api/product/" + pid);
        String ts = detail.jsonPath().getString("timestamp");
        assertThat(ts).matches(".*(Z|[+-]\\d{2}:?\\d{2})$");
        // price 也带两位精度
        assertThat(detail.jsonPath().getString("data.price")).matches("\\d+\\.\\d{2}");
    }

    private long timeDetail(String token, long pid) {
        long s = System.currentTimeMillis();
        given().spec(ApiClient.as(token)).get("/api/product/" + pid).then().statusCode(200);
        return System.currentTimeMillis() - s;
    }
}
