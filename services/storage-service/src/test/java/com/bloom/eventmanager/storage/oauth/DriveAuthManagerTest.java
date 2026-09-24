package com.bloom.eventmanager.storage.oauth;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.storage.config.StorageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveAuthManagerTest {

    private final StorageProperties properties = new StorageProperties("cid", "csecret", "",
            "http://localhost:8080/api/rag/auth/google/callback", "root", 300_000L, 60_000L, "http://localhost:5173",
            List.of("https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.file"), "default",
            600_000L);
    private final CredentialsResolver resolver = mock(CredentialsResolver.class);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final DriveAuthManager auth = new DriveAuthManager(properties, resolver, tokenStore);

    @Test
    void isUnconfiguredWhenNoCredentialsCanBeResolved() {
        when(resolver.resolve()).thenReturn(Optional.empty());

        auth.loadCredentials();

        assertThat(auth.isConfigured()).isFalse();
        assertThat(auth.isAuthenticated()).isFalse();
        assertThatThrownBy(auth::authUrl).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    @Test
    void restoresAStoredTokenIntoARefreshableCredential() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.of(new StoredToken("acc", "ref", "s", "Bearer", 123L)));

        auth.loadCredentials();

        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.credential().getAccessToken()).isEqualTo("acc");
        assertThat(auth.credential().getRefreshToken()).isEqualTo("ref");
        assertThat(auth.credential().getExpirationTimeMilliseconds()).isEqualTo(123L);
    }

    @Test
    void configuredWithoutATokenIsNotAuthenticated() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.empty());

        auth.loadCredentials();

        assertThat(auth.isConfigured()).isTrue();
        assertThat(auth.isAuthenticated()).isFalse();
        assertThatThrownBy(auth::credential).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    @Test
    void buildsV1sConsentUrl() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.empty());
        auth.loadCredentials();

        String url = auth.authUrl();

        // v2, not the library's legacy /o/oauth2/auth: the v1 endpoint rejects this
        // client's request with invalid_scope after sign-in. See DriveAuthManager.
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                .contains("client_id=cid")
                // GenericUrl leaves ':' and '/' unescaped in a query value — legal per
                // RFC 3986 section 3.4, and the exact form Google's own Java client sends.
                .contains("redirect_uri=http://localhost:8080/api/rag/auth/google/callback")
                .contains("access_type=offline")
                .contains("prompt=consent")
                .contains("drive.readonly")
                .contains("drive.file");
    }

    @Test
    void invalidGrantClearsTheTokenAndDeauthenticates() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.of(new StoredToken("acc", "ref", "s", "Bearer", 1L)));
        auth.loadCredentials();

        auth.handleAuthError(new RuntimeException("400 Bad Request: {\"error\": \"invalid_grant\"}"));

        assertThat(auth.isAuthenticated()).isFalse();
        verify(tokenStore).clear("default");
    }

    @Test
    void otherErrorsLeaveAuthenticationAlone() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.of(new StoredToken("acc", "ref", "s", "Bearer", 1L)));
        auth.loadCredentials();

        auth.handleAuthError(new RuntimeException("503 backend error"));

        assertThat(auth.isAuthenticated()).isTrue();
        verify(tokenStore, never()).clear("default");
    }

    @Test
    void notAuthenticatedExceptionCarriesTheReauthUrlForTheFrontend() {
        DriveNotAuthenticatedException ex = new DriveNotAuthenticatedException();

        assertThat(ex.properties()).containsEntry("authUrl", "/api/v1/rag/sync/auth?force=true")
                .containsEntry("error", "Google Drive not authenticated.")
                .containsEntry("message", "Google Drive not authenticated.");
    }
}
