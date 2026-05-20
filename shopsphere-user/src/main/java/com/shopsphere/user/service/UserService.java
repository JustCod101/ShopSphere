package com.shopsphere.user.service;

import com.shopsphere.api.user.dto.UserDTO;
import com.shopsphere.user.dto.LoginDTO;
import com.shopsphere.user.dto.LoginVO;
import com.shopsphere.user.dto.RegisterDTO;
import com.shopsphere.user.dto.UserVO;

public interface UserService {

    /**
     * 注册：BCrypt 编码密码 + 同事务写 t_user / t_user_profile（nickname 默认 = username）。
     * <p>username 冲突 → BusinessException(USERNAME_EXISTS, 2001)。
     */
    UserVO register(RegisterDTO req);

    /**
     * 登录：防爆破前置 → 校验密码 → 命中 clearOnSuccess / 失败 recordFail。
     * <p>用户不存在 → 2003；密码错 → 2002；锁定 → 2002 + 自定义 message。
     */
    LoginVO login(LoginDTO req);

    /** 取当前用户视图（/api/user/me，userId 从 UserContextHolder 读，禁止 Controller 直读 header）。 */
    UserVO me(Long userId);

    /** 内部接口 /internal/user/{id} 返回的 Feign DTO；不存在 → 2003。 */
    UserDTO getInternalById(Long id);
}
