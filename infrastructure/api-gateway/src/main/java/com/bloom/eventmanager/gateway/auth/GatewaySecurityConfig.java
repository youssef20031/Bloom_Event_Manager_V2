package com.bloom.eventmanager.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    JwtKeys jwtKeys(AuthProperties properties) {
        return JwtKeys.from(properties);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtKeys keys) {
        return NimbusReactiveJwtDecoder.withPublicKey(keys.publicKey()).build();
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder, JsonMapper json) {
        ProblemDetailEntryPoint entryPoint = new ProblemDetailEntryPoint(json);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/auth/login", "/.well-known/jwks.json", "/actuator/health/**", "/actuator/health").permitAll()
                        // Google's browser redirect cannot carry a Bearer token.
                        .pathMatchers("/api/rag/auth/google/callback", "/api/v1/rag/sync/auth/callback").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(decoder))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .build();
    }
}
