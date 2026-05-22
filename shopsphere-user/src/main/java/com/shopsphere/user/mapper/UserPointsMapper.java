package com.shopsphere.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.user.entity.UserPointsEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

@Mapper
public interface UserPointsMapper extends BaseMapper<UserPointsEntity> {

    /**
     * 累加用户积分（upsert）。首次发放 INSERT 建行，后续 {@code ON DUPLICATE KEY UPDATE} 累加。
     * 单条原子 SQL，无需先查后写。
     */
    @Insert("INSERT INTO t_user_points(user_id, points, created_at, updated_at) "
            + "VALUES(#{userId}, #{delta}, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE points = points + #{delta}, updated_at = #{now}")
    int addPoints(@Param("userId") Long userId, @Param("delta") long delta,
                  @Param("now") OffsetDateTime now);
}
