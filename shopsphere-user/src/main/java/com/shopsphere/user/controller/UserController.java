package com.shopsphere.user.controller;

import com.shopsphere.common.context.PublicApi;
import com.shopsphere.common.context.UserContextHolder;
import com.shopsphere.common.result.Result;
import com.shopsphere.user.dto.BehaviorRequestDTO;
import com.shopsphere.user.dto.LoginDTO;
import com.shopsphere.user.dto.LoginVO;
import com.shopsphere.user.dto.RegisterDTO;
import com.shopsphere.user.dto.UserVO;
import com.shopsphere.user.service.BehaviorService;
import com.shopsphere.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外用户接口（契约 §6.1）。
 * <p>Controller 仅做参数校验 + 调 Service，业务逻辑禁止下沉到此处（CLAUDE.md）。
 * <p>{@code register} / {@code login} 标 {@link PublicApi} 免登录；{@code me} 默认需登录，
 * 由 common {@code UserContextInterceptor} 校验 X-User-Id。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final BehaviorService behaviorService;

    @PublicApi
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO req) {
        return Result.ok(userService.register(req));
    }

    @PublicApi
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO req) {
        return Result.ok(userService.login(req));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        // 铁律：不读 header，userId 从 UserContextHolder 取（拦截器已注入）
        Long userId = UserContextHolder.get().getUserId();
        return Result.ok(userService.me(userId));
    }

    /**
     * 行为埋点（契约 §6.1 / §7 / §8）。需登录；落 t_user_behavior 同步、MQ 发送异步（AFTER_COMMIT）。
     */
    @PostMapping("/behavior")
    public Result<Void> behavior(@Valid @RequestBody BehaviorRequestDTO req) {
        Long userId = UserContextHolder.get().getUserId();
        behaviorService.record(userId, req);
        return Result.ok();
    }
}
