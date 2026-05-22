package com.shopsphere.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.order.entity.OrderRequestEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderRequestMapper extends BaseMapper<OrderRequestEntity> {

    /** 按 (userId, requestId) 查下单幂等记录（S5），未命中返回 null。 */
    @Select("SELECT * FROM t_order_request WHERE user_id = #{userId} AND request_id = #{requestId} LIMIT 1")
    OrderRequestEntity findByUserAndRequestId(@Param("userId") Long userId,
                                              @Param("requestId") String requestId);
}
