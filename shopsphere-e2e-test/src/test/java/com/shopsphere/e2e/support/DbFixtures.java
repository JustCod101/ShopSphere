package com.shopsphere.e2e.support;

import com.shopsphere.e2e.E2eConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 4 库 JDBC 直连工具。提供：
 *   - {@link #truncateAndReset()}: 每 @Test 前清掉全部"运行期产生"的表，并把 t_product_stock 重置到种子值
 *   - 一系列 query 方法供断言用
 *
 * 直连 DB 是 E2E 模块的特权；保持只读+truncate，不写业务表（业务行为由 API 触发）。
 */
public final class DbFixtures {

    private final E2eConfig cfg = E2eConfig.get();

    public Connection connect(String schema) throws SQLException {
        String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                cfg.mysqlHost(), cfg.mysqlPort(), schema);
        return DriverManager.getConnection(url, cfg.mysqlUser(), cfg.mysqlPass());
    }

    /** 每个 @Test 前调用，幂等。商品/类目种子数据保留。 */
    public void truncateAndReset() {
        execEach("shopsphere_user",
                "SET FOREIGN_KEY_CHECKS=0",
                "TRUNCATE TABLE t_user",
                "TRUNCATE TABLE t_user_profile",
                "TRUNCATE TABLE t_user_behavior",
                "TRUNCATE TABLE t_user_points",
                "TRUNCATE TABLE t_points_log",
                "SET FOREIGN_KEY_CHECKS=1");

        execEach("shopsphere_order",
                "SET FOREIGN_KEY_CHECKS=0",
                "TRUNCATE TABLE t_order",
                "TRUNCATE TABLE t_order_item",
                "TRUNCATE TABLE t_order_request",
                "TRUNCATE TABLE t_local_message",
                "SET FOREIGN_KEY_CHECKS=1");

        execEach("shopsphere_product",
                "TRUNCATE TABLE t_stock_tcc_log",
                "UPDATE t_product_stock SET stock=" + cfg.seedStock() + ", locked_stock=0, version=0");

        execEach("shopsphere_reco",
                "TRUNCATE TABLE behavior_event");
    }

    private void execEach(String schema, String... sqls) {
        try (Connection c = connect(schema); Statement s = c.createStatement()) {
            for (String sql : sqls) s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("truncate failed on " + schema + ": " + e.getMessage(), e);
        }
    }

    // ===== 断言查询 =====

    public int productStock(long productId) {
        return scalarInt("shopsphere_product",
                "SELECT stock FROM t_product_stock WHERE product_id=?", productId);
    }

    public int productLockedStock(long productId) {
        return scalarInt("shopsphere_product",
                "SELECT locked_stock FROM t_product_stock WHERE product_id=?", productId);
    }

    public String orderStatus(long orderId) {
        return scalarString("shopsphere_order",
                "SELECT status FROM t_order WHERE id=?", orderId);
    }

    public long countOrdersByUser(long userId) {
        return scalarLong("shopsphere_order",
                "SELECT COUNT(*) FROM t_order WHERE user_id=?", userId);
    }

    public long countBehaviorEvent(long userId) {
        return scalarLong("shopsphere_reco",
                "SELECT COUNT(*) FROM behavior_event WHERE user_id=?", userId);
    }

    public long countUserPoints(long userId) {
        return scalarLong("shopsphere_user",
                "SELECT COUNT(*) FROM t_user_points WHERE user_id=?", userId);
    }

    public int countLocalMessageWithStatus(String orderNo, String routingKey, int status) {
        return scalarInt("shopsphere_order",
                "SELECT COUNT(*) FROM t_local_message WHERE biz_key=? AND routing_key=? AND status=?",
                orderNo, routingKey, status);
    }

    public int countTccLog(long orderId, long productId, String phase) {
        return scalarInt("shopsphere_product",
                "SELECT COUNT(*) FROM t_stock_tcc_log WHERE order_id=? AND product_id=? AND phase=?",
                orderId, productId, phase);
    }

    public String orderNoOf(long orderId) {
        return scalarString("shopsphere_order",
                "SELECT order_no FROM t_order WHERE id=?", orderId);
    }

    /** 测试用：强制把订单状态置为指定值（case j 需要先到 SHIPPED）。 */
    public void forceOrderStatus(long orderId, String status) {
        execEach("shopsphere_order",
                "UPDATE t_order SET status='" + status + "' WHERE id=" + orderId);
    }

    // ===== 标量执行器 =====

    private int scalarInt(String schema, String sql, Object... params) {
        return (int) scalarLong(schema, sql, params);
    }

    private long scalarLong(String schema, String sql, Object... params) {
        try (Connection c = connect(schema);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
                return 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed on " + schema + ": " + sql, e);
        }
    }

    private String scalarString(String schema, String sql, Object... params) {
        try (Connection c = connect(schema);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed on " + schema + ": " + sql, e);
        }
    }
}
