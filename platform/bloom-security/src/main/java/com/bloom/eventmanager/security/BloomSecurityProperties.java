package com.bloom.eventmanager.security;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bloom.security")
public record BloomSecurityProperties(
        @NotBlank String internalToken,
        /** Extra open paths, e.g. storage-service's OAuth callback. */
        @DefaultValue List<String> permitAll) {
}
