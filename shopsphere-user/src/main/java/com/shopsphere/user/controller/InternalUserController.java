package com.shopsphere.user.controller;

import com.shopsphere.api.user.UserFeignClient;
import com.shopsphere.api.user.dto.UserDTO;
import com.shopsphere.common.context.PublicApi;
import com.shopsphere.common.result.Result;
import com.shopsphere.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部 Feign 端点（契约 §4.2）。直接 {@code implements UserFeignClient} —
 * 路径/方法/返回与 Feign 契约编译期对齐，契约漂移即编译失败。
 *
 * <p><b>路径前缀须类级显式声明</b>：{@code @FeignClient(path="/internal/user")} 仅客户端消费，
 * 服务端 MVC 不识别；缺失类级 {@code @RequestMapping} 会导致方法 {@code @GetMapping("/{id}")}
 * 退化为根路径 {@code GET /{id}}，拦截任意单段路径请求。
 *
 * <p><b>鉴权策略</b>：
 * <ul>
 *   <li>Gateway 显式拒绝外部 {@code /internal/**}（T1.1/T1.2）</li>
 *   <li>Feign 服务间走 Nacos 直连，不经 Gateway，可能不带 {@code X-User-Id}</li>
 *   <li>故标 {@link PublicApi} 跳过 UserContext 鉴权兜底；网络边界由部署形态保证（Phase 2 治理）</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/user")
@PublicApi
@RequiredArgsConstructor
public class InternalUserController implements UserFeignClient {

    private final UserService userService;

    @Override
    public Result<UserDTO> getById(Long id) {
        return Result.ok(userService.getInternalById(id));
    }
}
