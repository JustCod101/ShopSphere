package com.shopsphere.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ShopSphere 统一网关入口。
 *
 * <p>对外唯一入口：仅 {@code /api/**} 经此路由至下游 {@code lb://shopsphere-*}；
 * {@code /internal/**} 由 {@code InternalAccessRejectFilter} 在路由前显式拒绝（§4.1）。
 * 路由表 / CORS / 白名单从 Nacos {@code shopsphere-gateway.yaml} 加载，本地不写死。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
