package com.bloom.eventmanager.gateway.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 401 as RFC 9457 JSON, matching the ProblemDetails the services emit, instead of a WWW-Authenticate challenge. */
public class ProblemDetailEntryPoint implements ServerAuthenticationEntryPoint {

    private final JsonMapper json;

    public ProblemDetailEntryPoint(JsonMapper json) {
        this.json = json;
    }

    static ProblemDetail unauthenticated(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Authentication Required");
        problem.setType(URI.create("https://bloom.eventmanager/problems/unauthenticated"));
        problem.setProperty("errorCode", "UNAUTHENTICATED");
        return problem;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = json.writeValueAsString(unauthenticated("Authentication required")).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
