package com.bloom.eventmanager.security;

import java.util.List;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenFeignInterceptorTest {

    private final InternalTokenFeignInterceptor interceptor = new InternalTokenFeignInterceptor(
            new BloomSecurityProperties("internal-secret", List.of()));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void alwaysAddsTheInternalToken() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("X-Internal-Token")).containsExactly("internal-secret");
        assertThat(template.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void forwardsTheCallersBearerTokenWhenThereIsARequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("Authorization")).containsExactly("Bearer abc");
    }
}
