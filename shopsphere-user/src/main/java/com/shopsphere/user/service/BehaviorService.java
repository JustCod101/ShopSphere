package com.shopsphere.user.service;

import com.shopsphere.user.dto.BehaviorRequestDTO;

public interface BehaviorService {

    /**
     * 落 t_user_behavior + 在事务提交后异步发 {@code shopsphere.behavior / user.behavior}。
     * <p>DB insert 失败 → {@code BusinessException(SERVER_ERROR)}（按 T1.4 规格）。
     */
    void record(Long userId, BehaviorRequestDTO req);
}
