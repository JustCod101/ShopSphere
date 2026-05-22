package com.shopsphere.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.order.entity.OrderItemEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {
}
