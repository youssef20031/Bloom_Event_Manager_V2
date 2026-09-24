package com.bloom.eventmanager.storage.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;
import com.bloom.eventmanager.storage.oauth.OAuthStateStore;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.DriveSyncService;

@RestController
@RequestMapping("/api/v1/storage/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final DriveAuthManager auth;
    private final AutoSyncScheduler scheduler;
    private final DriveSyncService sync;
    private final StorageProperties properties;
    private final OAuthStateStore states;

    public AuthController(DriveAuthManager auth, AutoSyncScheduler scheduler, DriveSyncService sync,
            StorageProperties properties, OAuthStateStore states) {
        this.auth = auth;
        this.scheduler = scheduler;
        this.sync = sync;
        this.properties = properties;
        this.states = states;
    }

    /**
     * V1 redirected straight to Google on {@code force=true}. That cannot carry
     * the JWT Phase 5 adds, so this always answers JSON and the SPA navigates.
     */
    @GetMapping
    public Map<String, Object> initAuth(@RequestParam(name = "force", defaultValue = "false") boolean force) {
        if (!auth.isConfigured()) {
            auth.loadCredentials();
        }
        if (!auth.isConfigured()) {
            throw new DriveNotAuthenticatedException();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (auth.isAuthenticated() && !force) {
            body.put("status", "success");
            body.put("message", "Already authenticated");
            return body;
        }
        body.put("status", "pending");
        body.put("message", "Authentication required");
        body.put("authUrl", auth.authUrl());
        return body;
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state) {
        if (code == null || code.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "Authorization code missing");
        }
        // Checked before the code is exchanged: an unredeemable state means this
        // callback did not originate from a consent flow we started.
        if (!states.consume(state)) {
            log.warn("Rejected Google OAuth callback with a missing or unrecognised state parameter");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(properties.frontendUrl() + "/?auth=error")).build();
        }
        String target;
        try {
            auth.authenticate(code);
            scheduler.start(properties.syncIntervalMs());
            target = properties.frontendUrl() + "/?auth=success";
        }
        catch (RuntimeException ex) {
            log.error("Google OAuth callback failed", ex);
            target = properties.frontendUrl() + "/?auth=error";
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return SyncController.statusBody(sync.status(), scheduler.isEnabled());
    }
}
