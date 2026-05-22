package com.shopsphere.user.controller;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.shopsphere.api.user.dto.UserDTO;
import com.shopsphere.common.config.CommonWebAutoConfiguration;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.user.mapper.PointsLogMapper;
import com.shopsphere.user.mapper.UserBehaviorMapper;
import com.shopsphere.user.mapper.UserMapper;
import com.shopsphere.user.mapper.UserPointsMapper;
import com.shopsphere.user.mapper.UserProfileMapper;
import com.shopsphere.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InternalUserController HTTP 契约测试（api-contracts §4.2）。
 * <p>覆盖：
 * <ul>
 *   <li>{@code @PublicApi}：服务间 Feign 直连可能不带 X-User-Id，仍放行</li>
 *   <li>不存在 → 2003 USER_NOT_FOUND</li>
 *   <li>响应 JSON 不含 passwordHash（双保险）</li>
 * </ul>
 * 注：
 * <ul>
 *   <li>UserDTO 编译期就不含 passwordHash，此处再用 jsonPath 兜底防止未来字段污染。</li>
 *   <li>路径前缀 {@code /internal/user} 由类级 {@code @RequestMapping} 显式声明（修复历史漏洞：
 *       {@code @FeignClient(path=...)} 仅客户端消费，服务端 MVC 不识别；缺类级注解会让
 *       {@code @GetMapping("/{id}")} 退化为根路径 {@code /{id}}）。</li>
 * </ul>
 */
@WebMvcTest(controllers = InternalUserController.class,
        excludeAutoConfiguration = {
                DataSourceAutoConfiguration.class,
                MybatisPlusAutoConfiguration.class
        })
@Import(CommonWebAutoConfiguration.class)
class InternalUserControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private UserService userService;

    @MockBean
    private UserMapper userMapper;
    @MockBean
    private UserProfileMapper userProfileMapper;
    @MockBean
    private UserBehaviorMapper userBehaviorMapper;
    @MockBean
    private UserPointsMapper userPointsMapper;
    @MockBean
    private PointsLogMapper pointsLogMapper;

    @Test
    void getById_publicApi_withoutXUserId_isPassedThrough() throws Exception {
        UserDTO dto = UserDTO.builder()
                .id(100L)
                .username("alice")
                .nickname("Alice")
                .email("a@x.com")
                .status(1)
                .build();
        when(userService.getInternalById(100L)).thenReturn(dto);

        mvc.perform(get("/internal/user/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                // 双保险：UserDTO 不得携带 passwordHash 字段
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        verify(userService).getInternalById(100L);
    }

    @Test
    void getById_notFound_returns2003() throws Exception {
        when(userService.getInternalById(999L))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        mvc.perform(get("/internal/user/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
