package com.shopsphere.common.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记免登录（公开）接口。标注在 Controller 方法或类上。
 * <p>未标注的接口若无 {@code X-User-Id} → UserContextInterceptor 抛 UNAUTHORIZED。
 * 白名单语义由各业务服务自行声明，common 仅提供此标记。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {
}
