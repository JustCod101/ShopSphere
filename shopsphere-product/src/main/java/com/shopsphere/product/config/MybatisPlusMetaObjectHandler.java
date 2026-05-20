package com.shopsphere.product.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * MyBatis-Plus 自动填充：{@code createdAt} / {@code updatedAt} 写 UTC {@link OffsetDateTime}。
 *
 * <p>对应实体字段需标 {@code @TableField(fill = FieldFill.INSERT / INSERT_UPDATE)}。
 * <p>严格模式（{@code strictInsertFill}）仅当字段存在且为 null 时填充，避免覆盖外部显式赋值。
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class,
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
