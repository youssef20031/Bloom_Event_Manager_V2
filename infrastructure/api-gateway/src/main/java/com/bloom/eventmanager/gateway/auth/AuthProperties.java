package com.bloom.eventmanager.gateway.auth;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The successor to V1's APP_AUTH_USER / APP_AUTH_PASSWORD gate. One shared
 * login, as before; what changes is that the gateway now hands out a JWT the
 * services can verify on their own instead of re-checking Basic auth.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.auth")
public record AuthProperties(
        @NotBlank String user,
        @NotBlank String password,
        @DefaultValue("") String privateKeyPem,
        @DefaultValue("") String publicKeyPem,
        @DefaultValue("PT12H") Duration tokenTtl,
        @NotBlank String internalToken) {
}
