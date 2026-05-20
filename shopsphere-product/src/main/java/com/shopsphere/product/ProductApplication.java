package com.shopsphere.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Product 服务入口（端口 8082）。
 * <p>对齐 docs/api-contracts.md §5：serviceName=shopsphere-product，库=shopsphere_product。
 *
 * <p>本期（T2.1）不开 {@code @EnableFeignClients}：product-api 在 classpath 仅用于
 * {@code InternalProductController implements ProductFeignClient} 的编译期契约对齐，
 * 无需 Feign 代理基础设施。后续 Order 服务接入下游 Feign 时再考虑。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.shopsphere.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
