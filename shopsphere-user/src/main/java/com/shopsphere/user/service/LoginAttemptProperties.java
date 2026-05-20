package com.shopsphere.user.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 防爆破阈值（§10）。Nacos `shopsphere-user.yaml#login.attempt.*` 下发。
 */
@Data
@Component
@ConfigurationProperties(prefix = "login.attempt")
public class LoginAttemptProperties {

    /** 触发锁定的失败次数阈值 */
    private int maxFail = 5;

    /** 失败计数滚动窗口（秒） */
    private long windowSeconds = 600;

    /** 锁定时长（秒） */
    private long lockSeconds = 1800;

    /** 失败计数 Redis key 前缀（后接 username） */
    private String keyPrefix = "user:login:fail:";

    /** 锁定标记 Redis key 前缀（后接 username） */
    private String lockPrefix = "user:login:lock:";
}
