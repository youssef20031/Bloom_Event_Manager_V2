package com.bloom.eventmanager.storage.oauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.storage.config.StorageProperties;

@Component
public class CredentialsResolver {

    private static final Logger log = LoggerFactory.getLogger(CredentialsResolver.class);

    private final StorageProperties properties;
    private final JsonMapper json;

    public CredentialsResolver(StorageProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public Optional<OAuthClientCredentials> resolve() {
        if (hasText(properties.clientId()) && hasText(properties.clientSecret())) {
            return Optional.of(new OAuthClientCredentials(properties.clientId(), properties.clientSecret()));
        }
        return fromFile(Path.of(properties.credentialsPath()));
    }

    @SuppressWarnings("unchecked")
    private Optional<OAuthClientCredentials> fromFile(Path path) {
        String content;
        try {
            content = Files.readString(path);
        }
        catch (NoSuchFileException ex) {
            log.info("Google Drive credentials file not found at {} — Drive integration disabled", path.toAbsolutePath());
            return Optional.empty();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot read Drive credentials at " + path, ex);
        }

        Map<String, Object> root = json.readValue(content, Map.class);
        Object block = root.containsKey("web") ? root.get("web") : root.get("installed");
        if (!(block instanceof Map<?, ?> creds)) {
            log.warn("Drive credentials file at {} has neither 'web' nor 'installed' — Drive integration disabled", path);
            return Optional.empty();
        }
        Object id = creds.get("client_id");
        Object secret = creds.get("client_secret");
        if (!(id instanceof String clientId) || !(secret instanceof String clientSecret) || clientId.isBlank() || clientSecret.isBlank()) {
            log.warn("Drive credentials file at {} is missing client_id/client_secret — Drive integration disabled", path);
            return Optional.empty();
        }
        return Optional.of(new OAuthClientCredentials(clientId, clientSecret));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
