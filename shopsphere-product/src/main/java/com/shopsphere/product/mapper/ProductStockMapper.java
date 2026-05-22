package com.shopsphere.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.product.entity.ProductStockEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 库存表 Mapper。TCC 三段用条件更新（契约 §4.3）：
 * {@code stock} = 可售库存池，{@code locked_stock} = TCC 预留，真实总量 = 二者之和。
 * 各方法返回受影响行数，0 行表示条件不满足（库存/预留不足）。
 */
@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStockEntity> {

    /** TCC-Try：可售池扣减 + 预留增加；{@code stock} 不足返回 0 行。 */
    @Update("UPDATE t_product_stock SET stock = stock - #{qty}, "
            + "locked_stock = locked_stock + #{qty}, version = version + 1 "
            + "WHERE product_id = #{productId} AND stock >= #{qty}")
    int tryDeduct(@Param("productId") Long productId, @Param("qty") int qty);

    /** TCC-Confirm：预留转真实出库（仅减 {@code locked_stock}）；预留不足返回 0 行。 */
    @Update("UPDATE t_product_stock SET locked_stock = locked_stock - #{qty}, "
            + "version = version + 1 "
            + "WHERE product_id = #{productId} AND locked_stock >= #{qty}")
    int confirm(@Param("productId") Long productId, @Param("qty") int qty);

    /** TCC-Cancel：预留释放回可售池；预留不足返回 0 行。 */
    @Update("UPDATE t_product_stock SET stock = stock + #{qty}, "
            + "locked_stock = locked_stock - #{qty}, version = version + 1 "
            + "WHERE product_id = #{productId} AND locked_stock >= #{qty}")
    int cancel(@Param("productId") Long productId, @Param("qty") int qty);

    /**
     * 取消已 Confirm（已支付出库）的预留 —— 逆向补偿。
     * Confirm 后 {@code locked_stock} 已归零、{@code stock} 已实扣，故只把数量退回可售池
     * （不涉及 {@code locked_stock}）。用于 PAID 订单取消（契约 §6.3）。
     */
    @Update("UPDATE t_product_stock SET stock = stock + #{qty}, version = version + 1 "
            + "WHERE product_id = #{productId}")
    int cancelConfirmed(@Param("productId") Long productId, @Param("qty") int qty);
}
