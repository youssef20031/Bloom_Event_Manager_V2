package com.bloom.eventmanager.gateway.auth;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenGatewayFilterTest {

    private final InternalTokenGatewayFilter filter = new InternalTokenGatewayFilter(
            new AuthProperties("u", "p", "", "", Duration.ofHours(1), "internal-secret"));

    @Test
    void stripsAForgedHeaderFromAnonymousTraffic() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("X-Internal-Token", "forged"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, ex -> { forwarded.set(ex); return Mono.empty(); }).block();

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Internal-Token")).isNull();
    }

    @Test
    void stampsTheRealTokenOnAuthenticatedTraffic() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/x").header("X-Internal-Token", "forged"))
                .principal(UsernamePasswordAuthenticationToken.authenticated("bloom", null, java.util.List.of()))
                .build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, ex -> { forwarded.set(ex); return Mono.empty(); }).block();

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Internal-Token")).containsExactly("internal-secret");
    }
}
