package com.shopsphere.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * 库存 Lua 脚本 bean（T2.3）。
 *
 * <p>脚本源放 classpath {@code scripts/}，{@link ResourceScriptSource} + {@link ClassPathResource}
 * 加载,工作目录无关、打进 jar。{@code resultType=Long}:Redis 整型回值映射为 Long。
 *
 * <p>返回码语义见 {@code com.shopsphere.product.service.StockRedisService}。
 */
@Configuration
public class RedisScriptConfig {

    @Bean
    public RedisScript<Long> stockPreDeductScript() {
        return luaScript("scripts/stock_prededuct.lua");
    }

    @Bean
    public RedisScript<Long> stockRestoreScript() {
        return luaScript("scripts/stock_restore.lua");
    }

    private RedisScript<Long> luaScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }
}
