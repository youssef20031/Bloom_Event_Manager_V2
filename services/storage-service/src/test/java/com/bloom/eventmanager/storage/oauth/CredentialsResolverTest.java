package com.bloom.eventmanager.storage.oauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.storage.config.StorageProperties;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsResolverTest {

    @TempDir
    Path dir;

    @Test
    void prefersExplicitClientIdAndSecret() {
        CredentialsResolver resolver = new CredentialsResolver(props("id-1", "secret-1", dir.resolve("missing.json")), JsonMapper.builder().build());

        assertThat(resolver.resolve()).contains(new OAuthClientCredentials("id-1", "secret-1"));
    }

    @Test
    void readsV1sClientSecretJsonWebBlock() throws IOException {
        Path file = dir.resolve("client_secret.json");
        Files.writeString(file, "{\"web\": {\"client_id\": \"web-id\", \"client_secret\": \"web-secret\", \"redirect_uris\": []}}");

        CredentialsResolver resolver = new CredentialsResolver(props("", "", file), JsonMapper.builder().build());

        assertThat(resolver.resolve()).contains(new OAuthClientCredentials("web-id", "web-secret"));
    }

    @Test
    void acceptsTheInstalledBlockToo() throws IOException {
        Path file = dir.resolve("client_secret.json");
        Files.writeString(file, "{\"installed\": {\"client_id\": \"i-id\", \"client_secret\": \"i-secret\"}}");

        assertThat(new CredentialsResolver(props("", "", file), JsonMapper.builder().build()).resolve())
                .contains(new OAuthClientCredentials("i-id", "i-secret"));
    }

    @Test
    void aMissingFileMeansNotConfiguredNotAnError() {
        assertThat(new CredentialsResolver(props("", "", dir.resolve("nope.json")), JsonMapper.builder().build()).resolve())
                .isEmpty();
    }

    @Test
    void aFileWithoutIdOrSecretMeansNotConfigured() throws IOException {
        Path file = dir.resolve("client_secret.json");
        Files.writeString(file, "{\"web\": {\"client_id\": \"only-id\"}}");

        assertThat(new CredentialsResolver(props("", "", file), JsonMapper.builder().build()).resolve()).isEmpty();
    }

    private static StorageProperties props(String id, String secret, Path credentialsPath) {
        return new StorageProperties(id, secret, credentialsPath.toString(),
                "http://localhost:8080/api/rag/auth/google/callback", "root", 300_000L, 60_000L,
                "http://localhost:5173", List.of("https://www.googleapis.com/auth/drive.file"), "default", 600_000L);
    }
}
