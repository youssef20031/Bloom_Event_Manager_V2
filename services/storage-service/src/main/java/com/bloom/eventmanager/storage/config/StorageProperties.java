package com.bloom.eventmanager.storage.config;

import java.util.List;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Drive settings. Client id/secret may come from the environment (preferred)
 * or from V1's client_secret.json at {@code credentialsPath}. Neither present
 * means Drive is simply not configured — a normal state, not an error.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.storage")
public record StorageProperties(
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("./secrets/client_secret.json") String credentialsPath,
        /** Must be registered in Google Cloud Console. The gateway's legacy shim forwards it. */
        @DefaultValue("http://localhost:8080/api/rag/auth/google/callback") String redirectUri,
        @DefaultValue("") String rootFolderId,
        @DefaultValue("300000") @Min(10_000) long syncIntervalMs,
        /** Delay before the first poll at boot, so Eureka registration can finish. */
        @DefaultValue("60000") @Min(0) long syncInitialDelayMs,
        @DefaultValue("http://localhost:5173") String frontendUrl,
        @DefaultValue({ "https://www.googleapis.com/auth/drive.readonly",
                "https://www.googleapis.com/auth/drive.file" }) List<String> scopes,
        @DefaultValue("default") String accountId) {
}
