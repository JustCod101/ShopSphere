package com.shopsphere.common.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 当前请求的用户上下文，由 Gateway 透传头解析得到（api-contracts §3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String userName;
    private String traceId;
}
