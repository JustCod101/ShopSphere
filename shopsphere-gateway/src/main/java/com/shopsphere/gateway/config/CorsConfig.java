package com.shopsphere.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;

import java.util.List;

/**
 * 全局 CORS 兜底。
 *
 * <p>开发期零配置即放开（{@code allowed-origin-patterns} 默认 {@code *}）；
 * 生产经 Nacos 覆盖 {@code gateway.cors.allowed-origin-patterns} 收敛为具体域名。
 * 用 originPatterns（非 origins）以兼容 {@code allowCredentials=true} 与通配。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${gateway.cors.allowed-origin-patterns:*}") List<String> allowedOriginPatterns) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.addAllowedMethod(CorsConfiguration.ALL);
        config.addAllowedHeader(CorsConfiguration.ALL);
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
