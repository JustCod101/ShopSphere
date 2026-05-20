package com.shopsphere.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.user.entity.UserProfileEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {
}
