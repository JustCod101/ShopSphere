package com.shopsphere.order.config;

import com.shopsphere.api.product.ProductFeignFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式注册 {@link ProductFeignFallback} 为 Bean。
 *
 * <p>该类在 {@code product-api} 模块（{@code @Component}），但 order 的组件扫描不覆盖
 * {@code com.shopsphere.api.product} 包，故此处显式声明 —— {@code feign.sentinel.enabled=true}
 * 时 SentinelFeign 按类型装配此 fallback（CLAUDE.md：Feign 必须有 fallback）。
 */
@Configuration
public class OrderFeignConfig {

    @Bean
    public ProductFeignFallback productFeignFallback() {
        return new ProductFeignFallback();
    }
}
