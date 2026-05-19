package com.shopsphere.api.user;

import com.shopsphere.api.user.dto.UserDTO;
import com.shopsphere.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * User 服务内部接口（api-contracts §4.2）。服务间 Nacos 直连，不经 Gateway（C2）。
 */
@FeignClient(name = "shopsphere-user", path = "/internal/user",
        fallback = UserFeignFallback.class)
public interface UserFeignClient {

    @GetMapping("/{id}")
    Result<UserDTO> getById(@PathVariable("id") Long id);
}
