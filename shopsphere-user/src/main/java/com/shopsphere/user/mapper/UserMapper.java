package com.shopsphere.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopsphere.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /** 按 username 查（注册唯一性兜底 + 登录），未命中返回 null。 */
    @Select("SELECT * FROM t_user WHERE username = #{username} LIMIT 1")
    UserEntity findByUsername(@Param("username") String username);
}
