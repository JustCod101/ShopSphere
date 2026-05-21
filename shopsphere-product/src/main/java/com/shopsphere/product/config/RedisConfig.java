package com.shopsphere.product.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置（T2.2）。
 *
 * <p><b>RedisTemplate&lt;String,Object&gt;</b>：key/hashKey 用 {@link StringRedisSerializer}（裸字符串，
 * 便于 redis-cli 直读 / 与 Lua 脚本 KEYS 对齐）；value 用 {@link GenericJackson2JsonRedisSerializer}
 * 配自定义 ObjectMapper。
 *
 * <p><b>ObjectMapper 要点</b>：
 * <ul>
 *   <li>{@link JavaTimeModule} + 关闭 {@code WRITE_DATES_AS_TIMESTAMPS} → OffsetDateTime 写 ISO-8601（契约 §1.1）</li>
 *   <li>关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES} → VO 增删字段时旧缓存仍可反序列化，不抛错</li>
 *   <li>{@code activateDefaultTyping(NON_FINAL)} → 嵌入 {@code @class}，反序列化强类型还原，
 *       避免读出 LinkedHashMap 造成 ClassCastException</li>
 * </ul>
 *
 * <p><b>不污染 MVC ObjectMapper</b>：此 mapper 仅供 Redis 序列化；MVC 的 Jackson 由
 * {@code application.yml spring.jackson.*} 单独管控，二者隔离（defaultTyping 不可进 MVC 响应）。
 *
 * <p>{@code StringRedisTemplate}（stock key 纯字符串读写 + T2.3 Lua 兼容）由 Spring Boot
 * {@code RedisAutoConfiguration} 自动装配，此处不重复声明。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(buildRedisObjectMapper());
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /** 供测试复用，保证与生产序列化语义一致。 */
    static ObjectMapper buildRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
