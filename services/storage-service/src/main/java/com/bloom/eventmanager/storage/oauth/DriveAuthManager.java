package com.bloom.eventmanager.storage.oauth;

import java.io.IOException;
import java.util.Optional;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.CredentialRefreshListener;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleOAuthConstants;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.config.StorageProperties;

/**
 * V1's GoogleDriveAuthManager: owns the OAuth2 lifecycle for one account.
 * The Credential refreshes itself with the stored refresh token; every
 * refreshed access token is written back so a restart picks up the newest.
 */
@Component
public class DriveAuthManager {

    private static final Logger log = LoggerFactory.getLogger(DriveAuthManager.class);

    /**
     * The v2 consent endpoint, not the google-api-client default
     * {@link GoogleOAuthConstants#AUTHORIZATION_SERVER_URL} ({@code /o/oauth2/auth}).
     *
     * <p>Phase 3b verification: the legacy v1 endpoint answers {@code Error 400:
     * invalid_scope} after sign-in for this OAuth client, reporting a garbled
     * scope ({@code invalid=[ht]}) even though the URL carries both Drive scopes
     * intact. The identical request against {@code /o/oauth2/v2/auth} completes.
     * V1's google-auth-library has always used v2 ({@code oauth2AuthBaseUrl}),
     * which is why V1 never hit this. Do not revert to the library default.
     */
    static final String AUTHORIZATION_SERVER_URL = "https://accounts.google.com/o/oauth2/v2/auth";

    private final StorageProperties properties;
    private final CredentialsResolver resolver;
    private final TokenStore tokenStore;
    private final OAuthStateStore states;
    private final NetHttpTransport transport = new NetHttpTransport();
    private final GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private volatile OAuthClientCredentials client;
    private volatile Credential credential;
    private volatile boolean authenticated;

    public DriveAuthManager(StorageProperties properties, CredentialsResolver resolver, TokenStore tokenStore,
            OAuthStateStore states) {
        this.properties = properties;
        this.resolver = resolver;
        this.tokenStore = tokenStore;
        this.states = states;
    }

    public void loadCredentials() {
        Optional<OAuthClientCredentials> resolved = resolver.resolve();
        if (resolved.isEmpty()) {
            log.info("Google Drive integration disabled: no client credentials configured");
            return;
        }
        client = resolved.get();
        Optional<StoredToken> token = tokenStore.load(properties.accountId());
        if (token.isPresent()) {
            credential = buildCredential(token.get());
            authenticated = true;
            log.info("Loaded existing Google Drive token");
        }
        else {
            log.info("No existing Google Drive token; authentication required");
        }
    }

    public boolean isConfigured() {
        return client != null;
    }

    public boolean isAuthenticated() {
        return authenticated && credential != null;
    }

    public String authUrl() {
        requireConfigured();
        GoogleAuthorizationCodeRequestUrl url = new GoogleAuthorizationCodeRequestUrl(
                AUTHORIZATION_SERVER_URL, client.clientId(), properties.redirectUri(), properties.scopes());
        url.setAccessType("offline");
        url.set("prompt", "consent");
        // Google echoes this back to the callback unchanged. Without it the
        // callback cannot tell our own consent flow from an attacker's.
        url.setState(states.mint());
        return url.build();
    }

    public void authenticate(String code) {
        requireConfigured();
        try {
            GoogleTokenResponse response = new GoogleAuthorizationCodeTokenRequest(transport, jsonFactory,
                    client.clientId(), client.clientSecret(), code, properties.redirectUri()).execute();
            StoredToken token = new StoredToken(response.getAccessToken(), response.getRefreshToken(),
                    response.getScope(), response.getTokenType(), expiryOf(response));
            tokenStore.save(properties.accountId(), token);
            credential = buildCredential(token);
            authenticated = true;
            log.info("Google Drive authentication successful, token saved");
        }
        catch (IOException ex) {
            throw new ExternalServiceException("Google token exchange failed", ex);
        }
    }

    public Credential credential() {
        if (!isAuthenticated()) {
            throw new DriveNotAuthenticatedException();
        }
        return credential;
    }

    /** invalid_grant means the refresh token is dead: drop it so a restart does not resurrect it. */
    public void handleAuthError(Throwable error) {
        if (!isInvalidGrant(error)) {
            return;
        }
        log.warn("Google Drive token invalid or expired; invalidating session");
        authenticated = false;
        tokenStore.clear(properties.accountId());
    }

    static boolean isInvalidGrant(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("invalid_grant")) {
                return true;
            }
        }
        return false;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new DriveNotAuthenticatedException();
        }
    }

    private Credential buildCredential(StoredToken token) {
        Credential built = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .setTokenServerEncodedUrl(GoogleOAuthConstants.TOKEN_SERVER_URL)
                .setClientAuthentication(new ClientParametersAuthentication(client.clientId(), client.clientSecret()))
                .addRefreshListener(new PersistRefreshedToken(token))
                .build();
        built.setAccessToken(token.accessToken());
        built.setRefreshToken(token.refreshToken());
        built.setExpirationTimeMilliseconds(token.expiryDate());
        return built;
    }

    private static Long expiryOf(TokenResponse response) {
        Long expiresIn = response.getExpiresInSeconds();
        return expiresIn == null ? null : System.currentTimeMillis() + expiresIn * 1000;
    }

    /** Writes each refreshed access token back so the stored token never goes stale. */
    private final class PersistRefreshedToken implements CredentialRefreshListener {

        private final StoredToken original;

        PersistRefreshedToken(StoredToken original) {
            this.original = original;
        }

        @Override
        public void onTokenResponse(Credential refreshed, TokenResponse response) {
            String refreshToken = response.getRefreshToken() != null ? response.getRefreshToken() : original.refreshToken();
            tokenStore.save(properties.accountId(), new StoredToken(response.getAccessToken(), refreshToken,
                    original.scope(), response.getTokenType(), expiryOf(response)));
        }

        @Override
        public void onTokenErrorResponse(Credential refreshed, TokenErrorResponse errorResponse) {
            if (errorResponse != null && "invalid_grant".equals(errorResponse.getError())) {
                handleAuthError(new IllegalStateException("invalid_grant"));
            }
        }
    }
}
