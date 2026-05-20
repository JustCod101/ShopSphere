package com.shopsphere.product.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shopsphere.product.entity.CategoryEntity;
import com.shopsphere.product.entity.ProductEntity;
import com.shopsphere.product.entity.ProductStockEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MyBatis-Plus 自动填充处理器单测：验证 UTC OffsetDateTime 写入。
 * <p>{@code strictInsertFill/strictUpdateFill} 依赖 {@link TableInfoHelper} 已注册实体的
 * {@code TableInfo}，正常运行时由 MybatisSqlSessionFactoryBean 扫描注册；
 * 单元测试中显式预热一次即可。
 * <p>关键语义（参见 MP 3.5.x {@code strictFillStrategy}）：
 * 该策略遵循"有值不覆盖"——仅当字段当前为 {@code null} 时才填充。
 * 因此 updateFill 的测试也基于 updatedAt 为 null 的初始态。
 */
class MybatisPlusMetaObjectHandlerTest {

    private final MybatisPlusMetaObjectHandler handler = new MybatisPlusMetaObjectHandler();

    @BeforeAll
    static void registerTableInfo() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, ProductEntity.class);
        TableInfoHelper.initTableInfo(assistant, ProductStockEntity.class);
        TableInfoHelper.initTableInfo(assistant, CategoryEntity.class);
    }

    @Test
    void insertFill_setsBothTimestamps_inUtc() {
        ProductEntity entity = new ProductEntity();
        MetaObject mo = SystemMetaObject.forObject(entity);

        handler.insertFill(mo);

        assertNotNull(entity.getCreatedAt(), "createdAt 应被自动填充");
        assertNotNull(entity.getUpdatedAt(), "updatedAt 应被自动填充");
        assertEquals(ZoneOffset.UTC, entity.getCreatedAt().getOffset(), "createdAt 必须 UTC 偏移");
        assertEquals(ZoneOffset.UTC, entity.getUpdatedAt().getOffset(), "updatedAt 必须 UTC 偏移");
    }

    @Test
    void updateFill_setsOnlyUpdatedAt_whenNull_doesNotTouchCreatedAt() {
        // updatedAt 为 null（待填）；createdAt 预置以验证不被 updateFill 触碰
        OffsetDateTime original = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        ProductEntity entity = new ProductEntity();
        entity.setCreatedAt(original);
        // updatedAt 留空
        MetaObject mo = SystemMetaObject.forObject(entity);

        handler.updateFill(mo);

        assertEquals(original, entity.getCreatedAt(),
                "updateFill 不应覆盖 createdAt（handler 仅声明 updatedAt）");
        assertNotNull(entity.getUpdatedAt(), "updatedAt 应被填充");
        assertEquals(ZoneOffset.UTC, entity.getUpdatedAt().getOffset(), "updatedAt 必须 UTC 偏移");
    }

    @Test
    void updateFill_doesNotOverwriteExistingUpdatedAt() {
        // strictUpdateFill 策略：有值不覆盖（与 strictInsertFill 一致）
        OffsetDateTime preset = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        ProductEntity entity = new ProductEntity();
        entity.setCreatedAt(preset);
        entity.setUpdatedAt(preset);
        MetaObject mo = SystemMetaObject.forObject(entity);

        handler.updateFill(mo);

        assertEquals(preset, entity.getCreatedAt());
        assertEquals(preset, entity.getUpdatedAt(),
                "已显式赋值的 updatedAt 在 strictUpdateFill 下不应被覆盖");
    }

    @Test
    void insertFill_doesNotOverwriteExplicitlySetValues() {
        // strictInsertFill 语义：仅当字段为 null 才填充
        OffsetDateTime preset = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        ProductEntity entity = new ProductEntity();
        entity.setCreatedAt(preset);
        entity.setUpdatedAt(preset);
        MetaObject mo = SystemMetaObject.forObject(entity);

        handler.insertFill(mo);

        assertEquals(preset, entity.getCreatedAt(), "已显式赋值的 createdAt 不应被覆盖");
        assertEquals(preset, entity.getUpdatedAt(), "已显式赋值的 updatedAt 不应被覆盖");
    }
}
