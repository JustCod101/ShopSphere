package com.shopsphere.api.user;

import com.shopsphere.api.user.dto.UserDTO;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.common.result.Result;
import org.springframework.stereotype.Component;

/**
 * Sentinel 降级兜底（CLAUDE.md：Feign 必须有 fallback）。
 * 生效需消费方引入 spring-cloud-starter-alibaba-sentinel 且 feign.sentinel.enabled=true。
 */
@Component
public class UserFeignFallback implements UserFeignClient {

    @Override
    public Result<UserDTO> getById(Long id) {
        return Result.fail(ErrorCode.USER_NOT_FOUND, "用户服务不可用，降级");
    }
}
