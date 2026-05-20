package com.shopsphere.user.controller;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsphere.common.config.CommonWebAutoConfiguration;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.user.dto.ActionType;
import com.shopsphere.user.dto.BehaviorRequestDTO;
import com.shopsphere.user.dto.LoginDTO;
import com.shopsphere.user.dto.LoginVO;
import com.shopsphere.user.dto.RegisterDTO;
import com.shopsphere.user.dto.UserVO;
import com.shopsphere.user.mapper.UserMapper;
import com.shopsphere.user.mapper.UserBehaviorMapper;
import com.shopsphere.user.mapper.UserProfileMapper;
import com.shopsphere.user.service.BehaviorService;
import com.shopsphere.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserController HTTP 契约测试（api-contracts §6.1）。
 * <p>覆盖：
 * <ul>
 *   <li>注册参数校验（username/password）→ HTTP 200 + Result.code=1000</li>
 *   <li>注册成功响应 JSON 不含 passwordHash 铁律</li>
 *   <li>登录成功响应字段名严格 token/expiresIn</li>
 *   <li>登录锁定 message 透传（2002 + "账号已临时锁定，请稍后再试"）</li>
 *   <li>登录用户不存在 2003</li>
 *   <li>/me 未带 X-User-Id → 1001（UserContextInterceptor 兜底）</li>
 *   <li>/me 带 X-User-Id → service.me(userId) 被调用</li>
 *   <li>@PublicApi 通路：register 无 X-User-Id 也能进入</li>
 * </ul>
 * <p>注意：
 * <ul>
 *   <li>{@code @WebMvcTest} 默认不加载 common 的 {@code @AutoConfiguration}，
 *       故显式 {@link Import} 让 {@code UserContextInterceptor} 生效。</li>
 *   <li>排除 {@link DataSourceAutoConfiguration} / {@link MybatisPlusAutoConfiguration}
 *       并 {@code @MockBean} 两个 Mapper —— 避开 {@code @MapperScan} 在切片测试里
 *       仍要 SqlSessionFactory 的副作用。</li>
 * </ul>
 */
@WebMvcTest(controllers = UserController.class,
        excludeAutoConfiguration = {
                DataSourceAutoConfiguration.class,
                MybatisPlusAutoConfiguration.class
        })
@Import(CommonWebAutoConfiguration.class)
class UserControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockBean
    private UserService userService;

    @MockBean
    private BehaviorService behaviorService;

    // Mapper 因 @MapperScan 被注册为 bean 定义，未 mock 会要求 SqlSessionFactory
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private UserProfileMapper userProfileMapper;
    @MockBean
    private UserBehaviorMapper userBehaviorMapper;

    // ---------------------- 注册：参数校验 ----------------------

    @Test
    void register_usernameTooShort_returns1000_messageContainsUsername() throws Exception {
        RegisterDTO req = new RegisterDTO();
        req.setUsername("abc"); // 3 位 < min=4
        req.setPassword("Aa12345678");

        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("username")))
                .andExpect(jsonPath("$.timestamp", org.hamcrest.Matchers.endsWith("Z")));

        verify(userService, never()).register(any());
    }

    @Test
    void register_passwordMissingDigit_returns1000_messageContainsPassword() throws Exception {
        RegisterDTO req = new RegisterDTO();
        req.setUsername("alice");
        req.setPassword("AbcdEfghIj"); // 只有字母，无数字

        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("password")));

        verify(userService, never()).register(any());
    }

    // ---------------------- 注册：成功 ----------------------

    @Test
    void register_success_noPasswordHashInJson_andPublicApiPassesWithoutXUserId() throws Exception {
        UserVO vo = UserVO.builder()
                .id(100L)
                .username("alice")
                .nickname("alice")
                .email("a@x.com")
                .phone("13800000000")
                .status(1)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        when(userService.register(any(RegisterDTO.class))).thenReturn(vo);

        RegisterDTO req = new RegisterDTO();
        req.setUsername("alice");
        req.setPassword("Aa12345678");
        req.setEmail("a@x.com");
        req.setPhone("13800000000");

        // 验证 @PublicApi：不带 X-User-Id 也不被拦截器 1001 兜底
        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("alice"))
                // 铁律：响应不得暴露 passwordHash
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.timestamp", org.hamcrest.Matchers.endsWith("Z")));
    }

    // ---------------------- 登录 ----------------------

    @Test
    void login_success_returnsTokenAndExpiresInWithExactFieldNames() throws Exception {
        // 契约 §6.1：字段名严格 token / expiresIn（驼峰），不得为 expire_in / accessToken
        LoginVO vo = LoginVO.builder().token("TOKEN_XYZ").expiresIn(7200L).build();
        when(userService.login(any(LoginDTO.class))).thenReturn(vo);

        LoginDTO req = new LoginDTO();
        req.setUsername("alice");
        req.setPassword("Aa12345678");

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("TOKEN_XYZ"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200))
                // 不得出现下划线或别名
                .andExpect(jsonPath("$.data.expire_in").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    void login_locked_returns2002_withLockMessagePassThrough() throws Exception {
        when(userService.login(any(LoginDTO.class)))
                .thenThrow(new BusinessException(ErrorCode.PASSWORD_WRONG,
                        "账号已临时锁定，请稍后再试"));

        LoginDTO req = new LoginDTO();
        req.setUsername("alice");
        req.setPassword("Aa12345678");

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PASSWORD_WRONG.getCode()))
                .andExpect(jsonPath("$.message").value("账号已临时锁定，请稍后再试"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void login_userNotFound_returns2003() throws Exception {
        when(userService.login(any(LoginDTO.class)))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        LoginDTO req = new LoginDTO();
        req.setUsername("ghost");
        req.setPassword("Aa12345678");

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.USER_NOT_FOUND.getCode()));
    }

    // ---------------------- /me ----------------------

    @Test
    void me_withoutXUserId_returns1001_byUserContextInterceptor() throws Exception {
        mvc.perform(get("/api/user/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verify(userService, never()).me(any());
    }

    @Test
    void me_withXUserId_invokesServiceAndReturnsVOWithoutPasswordHash() throws Exception {
        UserVO vo = UserVO.builder()
                .id(100L)
                .username("alice")
                .nickname("Alice")
                .email("a@x.com")
                .status(1)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        when(userService.me(100L)).thenReturn(vo);

        mvc.perform(get("/api/user/me").header("X-User-Id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        verify(userService).me(100L);
    }

    // ---------------------- /behavior（T1.4） ----------------------

    @Test
    void behavior_withoutXUserId_returns1001_byUserContextInterceptor() throws Exception {
        BehaviorRequestDTO req = new BehaviorRequestDTO();
        req.setItemId(1001L);
        req.setActionType(ActionType.VIEW);

        mvc.perform(post("/api/user/behavior")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verify(behaviorService, never()).record(any(), any());
    }

    @Test
    void behavior_invalidActionType_returns1000_byHttpMessageNotReadableHandler() throws Exception {
        // 直接拼非法 JSON 触发 enum 反序列化失败
        String body = "{\"itemId\":1001,\"actionType\":\"hover\"}";

        mvc.perform(post("/api/user/behavior")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("view/cart/order")));

        verify(behaviorService, never()).record(any(), any());
    }

    @Test
    void behavior_missingItemId_returns1000_byBeanValidation() throws Exception {
        String body = "{\"actionType\":\"view\"}";

        mvc.perform(post("/api/user/behavior")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("itemId")));

        verify(behaviorService, never()).record(any(), any());
    }

    @Test
    void behavior_validRequest_withXUserId_invokesService_returns0() throws Exception {
        BehaviorRequestDTO req = new BehaviorRequestDTO();
        req.setItemId(1001L);
        req.setActionType(ActionType.VIEW);

        mvc.perform(post("/api/user/behavior")
                        .header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(behaviorService).record(eq(100L), any(BehaviorRequestDTO.class));
    }
}
