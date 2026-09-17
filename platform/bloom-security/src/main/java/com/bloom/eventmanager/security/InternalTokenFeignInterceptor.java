package com.bloom.eventmanager.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Every outbound Feign call carries the internal token, plus the caller's JWT when one exists. */
public class InternalTokenFeignInterceptor implements RequestInterceptor {

    private final String internalToken;

    public InternalTokenFeignInterceptor(BloomSecurityProperties properties) {
        this.internalToken = properties.internalToken();
    }

    @Override
    public void apply(RequestTemplate template) {
        template.header(InternalTokenFilter.HEADER, internalToken);
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                template.header("Authorization", authorization);
            }
        }
    }
}
