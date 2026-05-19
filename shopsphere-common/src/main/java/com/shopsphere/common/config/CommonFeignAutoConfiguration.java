package com.shopsphere.common.config;

import com.shopsphere.common.feign.FeignAuthInterceptor;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Feign 透传自动装配：仅当引入方 classpath 有 OpenFeign 时激活（C2 Nacos 直连透传）。
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class CommonFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeignAuthInterceptor feignAuthInterceptor() {
        return new FeignAuthInterceptor();
    }
}
