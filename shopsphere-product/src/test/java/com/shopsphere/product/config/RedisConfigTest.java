package com.shopsphere.product.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.product.dto.ProductDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RedisConfig 单测：序列化器装配 + ObjectMapper 行为（OffsetDateTime ISO-8601、未知字段容忍、@class 类型嵌入）。
 */
class RedisConfigTest {

    @Test
    void redisTemplate_usesStringKeyAndJsonValueSerializers() {
        RedisConnectionFactory cf = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> t = new RedisConfig().redisTemplate(cf);

        assertInstanceOf(StringRedisSerializer.class, t.getKeySerializer(),
                "key 用 StringRedisSerializer（redis-cli 可直读）");
        assertInstanceOf(StringRedisSerializer.class, t.getHashKeySerializer());
        assertInstanceOf(GenericJackson2JsonRedisSerializer.class, t.getValueSerializer());
        assertInstanceOf(GenericJackson2JsonRedisSerializer.class, t.getHashValueSerializer());
    }

    @Test
    void objectMapper_writesOffsetDateTimeAsIso8601_notEpoch() throws Exception {
        ObjectMapper m = RedisConfig.buildRedisObjectMapper();
        ProductDetailVO vo = ProductDetailVO.builder()
                .id(2001L).name("x").price(new BigDecimal("89.00"))
                .createdAt(OffsetDateTime.of(2026, 5, 21, 10, 30, 0, 0, ZoneOffset.UTC))
                .build();

        String json = m.writeValueAsString(vo);

        assertTrue(json.contains("2026-05-21T10:30"), "时间应为 ISO-8601 文本");
        assertFalse(json.matches(".*\"createdAt\"\\s*:\\s*\\d+.*"), "禁止 epoch 数字时间戳");
    }

    @Test
    void objectMapper_roundTripsProductDetailVO() throws Exception {
        ObjectMapper m = RedisConfig.buildRedisObjectMapper();
        OffsetDateTime created = OffsetDateTime.of(2026, 5, 21, 10, 30, 0, 0, ZoneOffset.UTC);
        ProductDetailVO vo = ProductDetailVO.builder()
                .id(2001L).name("深入理解 Java 虚拟机").categoryId(1005L)
                .price(new BigDecimal("89.00")).mainImage("img").description("desc")
                .status(1).createdAt(created)
                .build();

        String json = m.writeValueAsString(vo);
        ProductDetailVO back = m.readValue(json, ProductDetailVO.class);

        assertEquals(2001L, back.getId());
        assertEquals("深入理解 Java 虚拟机", back.getName());
        assertEquals(0, new BigDecimal("89.00").compareTo(back.getPrice()), "价格精度保留");
        assertEquals(created.toInstant(), back.getCreatedAt().toInstant(), "时间物理时刻一致");
    }

    @Test
    void objectMapper_embedsTypeInfo_forPolymorphicSafety() throws Exception {
        ObjectMapper m = RedisConfig.buildRedisObjectMapper();
        String json = m.writeValueAsString(ProductDetailVO.builder().id(1L).build());
        assertTrue(json.contains("@class"), "defaultTyping 嵌入 @class，反序列化强类型还原");
    }

    @Test
    void objectMapper_ignoresUnknownProperties() throws Exception {
        ObjectMapper m = RedisConfig.buildRedisObjectMapper();
        // 模拟旧缓存多了一个已删除字段，反序列化不应抛错（As.PROPERTY 用 @class 内嵌类型）
        String json = "{\"@class\":\"com.shopsphere.product.dto.ProductDetailVO\","
                + "\"id\":7,\"removedLegacyField\":\"v\"}";
        ProductDetailVO back = m.readValue(json, ProductDetailVO.class);
        assertEquals(7L, back.getId());
    }
}
