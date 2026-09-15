package com.bloom.eventmanager.gateway;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preserves V1's Google OAuth callback URI.
 *
 * <p>The path {@code /api/rag/auth/google/callback} is registered in the Google
 * Cloud Console for this project. Changing it requires an external console
 * change, so the gateway keeps answering on it and forwards to the versioned
 * path with the query string — which carries the authorization {@code code} —
 * intact. 307 rather than 302 so the method is preserved.
 *
 * <p>Implemented as a controller rather than a RedirectTo filter because the
 * filter takes an absolute URL, and the redirect target here is relative.
 */
@RestController
public class LegacyOAuthRedirectController {

    private static final String TARGET_PATH = "/api/v1/rag/sync/auth/callback";

    @GetMapping("/api/rag/auth/google/callback")
    public ResponseEntity<Void> redirectLegacyCallback(ServerHttpRequest request) {
        String query = request.getURI().getRawQuery();
        String target = (query == null || query.isBlank()) ? TARGET_PATH : TARGET_PATH + "?" + query;

        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(target))
                .build();
    }
}
