package com.shopsphere.user.support;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * OffsetDateTime ↔ DATETIME(3) UTC 强制 TypeHandler。
 *
 * <p><b>为什么必须自带</b>：MySQL DATETIME 不存时区；连接串虽然带 {@code serverTimezone=UTC}，
 * 但 MyBatis 内置 OffsetDateTime handler 默认按 JVM 时区组装 Timestamp，依赖 JVM 时区一致性脆弱。
 * 本 handler 显式 {@code odt.toInstant() ↔ Timestamp}，DB 永远存 UTC 物理时刻（契约 §1.1）。
 *
 * <p>由 {@code mybatis-plus.type-handlers-package=com.shopsphere.user.support} 自动注册。
 */
@MappedTypes(OffsetDateTime.class)
public class OffsetDateTimeTypeHandler extends BaseTypeHandler<OffsetDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, OffsetDateTime parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setTimestamp(i, Timestamp.from(parameter.toInstant()));
    }

    @Override
    public OffsetDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toOffsetDateTime(rs.getTimestamp(columnName));
    }

    @Override
    public OffsetDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toOffsetDateTime(rs.getTimestamp(columnIndex));
    }

    @Override
    public OffsetDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toOffsetDateTime(cs.getTimestamp(columnIndex));
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    }
}
