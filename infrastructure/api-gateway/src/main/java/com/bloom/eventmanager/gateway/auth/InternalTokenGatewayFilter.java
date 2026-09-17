package com.bloom.eventmanager.gateway.auth;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway is the trust boundary. Anything arriving with X-Internal-Token
 * is a forgery and is dropped; authenticated traffic leaving toward a service
 * gets the real one, so the service can accept it even when the original
 * caller had no JWT to forward (Kafka-driven hops).
 */
@Component
public class InternalTokenGatewayFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Internal-Token";

    private final String internalToken;

    public InternalTokenGatewayFilter(AuthProperties properties) {
        this.internalToken = properties.internalToken();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal().map(principal -> true).defaultIfEmpty(false).flatMap(authenticated -> {
            ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
                headers.remove(HEADER);
                if (authenticated) {
                    headers.add(HEADER, internalToken);
                }
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
