package com.shopsphere.user.service;

import com.shopsphere.api.user.dto.UserDTO;
import com.shopsphere.common.exception.BusinessException;
import com.shopsphere.common.result.ErrorCode;
import com.shopsphere.user.dto.LoginDTO;
import com.shopsphere.user.dto.LoginVO;
import com.shopsphere.user.dto.RegisterDTO;
import com.shopsphere.user.dto.UserVO;
import com.shopsphere.user.entity.UserEntity;
import com.shopsphere.user.entity.UserProfileEntity;
import com.shopsphere.user.mapper.UserMapper;
import com.shopsphere.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单测（纯 Mockito，不起 Spring）。覆盖：
 * <ol>
 *   <li>注册成功路径 + 唯一冲突 → 2001</li>
 *   <li>登录：用户不存在 → 2003 + recordFail；密码错 → 2002 + recordFail；成功 → clearOnSuccess</li>
 *   <li>me：当前用户视图不含 passwordHash 字段</li>
 *   <li>getInternalById：不存在 → 2003</li>
 * </ol>
 */
class UserServiceImplTest {

    private UserMapper userMapper;
    private UserProfileMapper profileMapper;
    private PasswordEncoder passwordEncoder;
    private JwtSigner jwtSigner;
    private LoginAttemptService loginAttempt;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        profileMapper = mock(UserProfileMapper.class);
        // 真实 BCrypt，避免 mock matches() 时漏判
        passwordEncoder = new BCryptPasswordEncoder();
        jwtSigner = mock(JwtSigner.class);
        loginAttempt = mock(LoginAttemptService.class);
        service = new UserServiceImpl(userMapper, profileMapper, passwordEncoder, jwtSigner, loginAttempt);
    }

    private RegisterDTO buildReg() {
        RegisterDTO r = new RegisterDTO();
        r.setUsername("alice");
        r.setPassword("Aa12345678");
        r.setEmail("a@x.com");
        r.setPhone("13800000000");
        return r;
    }

    @Test
    void register_success_returnsVO_writesUserAndProfile() {
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity e = inv.getArgument(0);
            e.setId(100L);
            return 1;
        });
        when(profileMapper.insert(any(UserProfileEntity.class))).thenReturn(1);

        UserVO vo = service.register(buildReg());

        assertEquals(100L, vo.getId());
        assertEquals("alice", vo.getUsername());
        assertEquals("alice", vo.getNickname(), "nickname 默认 = username");
        assertEquals(1, vo.getStatus());
        assertNotNull(vo.getCreatedAt());
        verify(userMapper).insert(any(UserEntity.class));
        verify(profileMapper).insert(any(UserProfileEntity.class));
    }

    @Test
    void register_duplicateUsername_throws2001() {
        when(userMapper.insert(any(UserEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_username"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(buildReg()));
        assertEquals(ErrorCode.USERNAME_EXISTS, ex.getErrorCode());
        // MP 3.5.7 BaseMapper 重载 insert(T)/insert(Collection<T>)，须显式参数类型消歧
        verify(profileMapper, never()).insert(any(UserProfileEntity.class));
    }

    @Test
    void login_userNotFound_throws2003_andRecordFail() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);
        LoginDTO req = new LoginDTO();
        req.setUsername("ghost");
        req.setPassword("Aa12345678");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.login(req));
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
        verify(loginAttempt).ensureNotLocked("ghost");
        verify(loginAttempt).recordFail("ghost");
        verify(loginAttempt, never()).clearOnSuccess(anyString());
    }

    @Test
    void login_wrongPassword_throws2002_andRecordFail() {
        UserEntity u = UserEntity.builder()
                .id(1L).username("alice")
                .passwordHash(passwordEncoder.encode("Aa12345678"))
                .status(1).build();
        when(userMapper.findByUsername("alice")).thenReturn(u);
        LoginDTO req = new LoginDTO();
        req.setUsername("alice");
        req.setPassword("WRONG_PWD_99");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.login(req));
        assertEquals(ErrorCode.PASSWORD_WRONG, ex.getErrorCode());
        verify(loginAttempt).recordFail("alice");
        verify(jwtSigner, never()).sign(any(), any());
    }

    @Test
    void login_success_clearsFailures_returnsToken() {
        UserEntity u = UserEntity.builder()
                .id(1L).username("alice")
                .passwordHash(passwordEncoder.encode("Aa12345678"))
                .status(1).build();
        when(userMapper.findByUsername("alice")).thenReturn(u);
        when(jwtSigner.sign(1L, "alice")).thenReturn("TOKEN_XYZ");
        when(jwtSigner.getExpireSeconds()).thenReturn(7200L);
        LoginDTO req = new LoginDTO();
        req.setUsername("alice");
        req.setPassword("Aa12345678");

        LoginVO vo = service.login(req);
        assertEquals("TOKEN_XYZ", vo.getToken());
        assertEquals(7200L, vo.getExpiresIn());
        verify(loginAttempt).clearOnSuccess("alice");
        verify(loginAttempt, never()).recordFail(anyString());
    }

    @Test
    void me_existingUser_returnsVO_noPasswordHashField() {
        UserEntity u = UserEntity.builder()
                .id(1L).username("alice").passwordHash("HASH_X")
                .email("a@x").status(1)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        UserProfileEntity p = UserProfileEntity.builder().userId(1L).nickname("Alice").build();
        when(userMapper.selectById(1L)).thenReturn(u);
        when(profileMapper.selectById(1L)).thenReturn(p);

        UserVO vo = service.me(1L);
        assertEquals("alice", vo.getUsername());
        assertEquals("Alice", vo.getNickname());
        // 编译期保障：UserVO 类无 passwordHash 字段（见 UserVO.java）
        assertFalse(hasField(vo.getClass(), "passwordHash"),
                "UserVO 不得有 passwordHash 字段（铁律）");
    }

    @Test
    void me_userMissing_throws2003() {
        when(userMapper.selectById(9999L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.me(9999L));
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getInternalById_existing_returnsDTO_noPasswordHash() {
        UserEntity u = UserEntity.builder()
                .id(5L).username("bob").passwordHash("HASH_Y").status(1).build();
        UserProfileEntity p = UserProfileEntity.builder().userId(5L).nickname("Bob").build();
        when(userMapper.selectById(5L)).thenReturn(u);
        when(profileMapper.selectById(5L)).thenReturn(p);

        UserDTO dto = service.getInternalById(5L);
        assertEquals("bob", dto.getUsername());
        assertEquals("Bob", dto.getNickname());
        assertFalse(hasField(dto.getClass(), "passwordHash"));
    }

    @Test
    void getInternalById_missing_throws2003() {
        when(userMapper.selectById(404L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getInternalById(404L));
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
    }

    private static boolean hasField(Class<?> cls, String name) {
        try {
            cls.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
