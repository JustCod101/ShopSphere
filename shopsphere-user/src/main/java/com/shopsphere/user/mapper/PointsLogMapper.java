package com.shopsphere.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.user.entity.PointsLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * t_points_log Mapper。{@code insert} 命中 {@code uk_order} 唯一约束 → {@code DuplicateKeyException}，
 * 由 {@code PointsConsumer} 据此判定「已处理」并幂等 ack。
 */
@Mapper
public interface PointsLogMapper extends BaseMapper<PointsLogEntity> {
}
