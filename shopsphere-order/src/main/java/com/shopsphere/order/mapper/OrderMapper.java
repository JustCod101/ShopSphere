package com.shopsphere.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.order.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 订单 CREATED(0) → PAID(1)，条件更新防并发。
     * 非 CREATED（已支付/已取消等）返回 0 行。
     */
    @Update("UPDATE t_order SET status = 1, paid_at = #{paidAt}, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 0")
    int markPaid(@Param("id") Long id, @Param("paidAt") OffsetDateTime paidAt,
                 @Param("now") OffsetDateTime now);

    /**
     * 人工取消：CREATED(0) 或 PAID(1) → CANCELLED(4)，条件更新防并发。
     * 非 CREATED/PAID（已发货/已完成/已取消）返回 0 行。
     */
    @Update("UPDATE t_order SET status = 4, updated_at = #{now} "
            + "WHERE id = #{id} AND status IN (0, 1)")
    int markCancelled(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /**
     * 超时取消：仅 CREATED(0) → CANCELLED(4)。
     * 用户在超时检查后完成支付时返回 0 行，避免误取消已支付订单。
     */
    @Update("UPDATE t_order SET status = 4, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 0")
    int markCancelledIfCreated(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
