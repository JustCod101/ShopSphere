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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 注册/登录/查询核心实现。所有时间字段统一 OffsetDateTime(UTC)（CLAUDE.md / 契约 §1.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserProfileMapper profileMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtSigner jwtSigner;
    private final LoginAttemptService loginAttempt;

    @Override
    @Transactional
    public UserVO register(RegisterDTO req) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserEntity user = UserEntity.builder()
                .username(req.getUsername())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .email(req.getEmail())
                .phone(req.getPhone())
                .status(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // uk_username 冲突 → 2001（精确捕获，不污染全局 5xx）
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // 注册即建空 profile（nickname 默认 = username）
        UserProfileEntity profile = UserProfileEntity.builder()
                .userId(user.getId())
                .nickname(req.getUsername())
                .createdAt(now)
                .updatedAt(now)
                .build();
        profileMapper.insert(profile);

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(profile.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Override
    public LoginVO login(LoginDTO req) {
        // 1. 入口先拦锁定态
        loginAttempt.ensureNotLocked(req.getUsername());

        // 2. 查用户；不存在也走 recordFail（防枚举）
        UserEntity user = userMapper.findByUsername(req.getUsername());
        if (user == null) {
            loginAttempt.recordFail(req.getUsername());
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // 3. 校验密码
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            loginAttempt.recordFail(req.getUsername());
            throw new BusinessException(ErrorCode.PASSWORD_WRONG);
        }

        // 4. 成功：清失败计数 + 签发 token
        loginAttempt.clearOnSuccess(req.getUsername());
        String token = jwtSigner.sign(user.getId(), user.getUsername());
        return LoginVO.builder()
                .token(token)
                .expiresIn(jwtSigner.getExpireSeconds())
                .build();
    }

    @Override
    public UserVO me(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            // userId 由 Gateway 签发的 token 解出，对应用户应存在；不存在多半是数据删了
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        UserProfileEntity profile = profileMapper.selectById(userId);
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(profile != null ? profile.getNickname() : null)
                .email(user.getEmail())
                .phone(user.getPhone())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Override
    public UserDTO getInternalById(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        UserProfileEntity profile = profileMapper.selectById(id);
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(profile != null ? profile.getNickname() : null)
                .email(user.getEmail())
                .phone(user.getPhone())
                .status(user.getStatus())
                .build();
    }
}
