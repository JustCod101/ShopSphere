package com.shopsphere.common.feign;

import com.shopsphere.common.context.HeaderConstant;
import com.shopsphere.common.context.UserContext;
import com.shopsphere.common.context.UserContextHolder;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 服务间 Feign 调用透传用户上下文（C2：Nacos 直连，不经 Gateway）。
 * <p>从 {@link UserContextHolder} 取上下文写入下游请求头。
 */
public class FeignAuthInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            return;
        }
        if (ctx.getUserId() != null) {
            template.header(HeaderConstant.X_USER_ID, String.valueOf(ctx.getUserId()));
        }
        if (StringUtils.hasText(ctx.getTraceId())) {
            template.header(HeaderConstant.X_TRACE_ID, ctx.getTraceId());
        }
        if (StringUtils.hasText(ctx.getUserName())) {
            template.header(HeaderConstant.X_USER_NAME,
                    URLEncoder.encode(ctx.getUserName(), StandardCharsets.UTF_8));
        }
    }
}
