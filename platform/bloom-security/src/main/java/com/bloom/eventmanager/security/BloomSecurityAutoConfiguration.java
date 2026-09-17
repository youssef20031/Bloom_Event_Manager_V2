package com.bloom.eventmanager.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Applied to every servlet service via the auto-configuration imports file.
 * JWT validation uses Boot's resource-server auto-config against
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri (the gateway's JWKS).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(BloomSecurityProperties.class)
public class BloomSecurityAutoConfiguration {

    @Bean
    SecurityFilterChain bloomSecurityFilterChain(HttpSecurity http, BloomSecurityProperties properties) throws Exception {
        List<String> open = new ArrayList<>(List.of("/actuator/health", "/actuator/health/**"));
        open.addAll(properties.permitAll());

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(open.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new InternalTokenFilter(properties.internalToken()), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    InternalTokenFeignInterceptor internalTokenFeignInterceptor(BloomSecurityProperties properties) {
        return new InternalTokenFeignInterceptor(properties);
    }
}
