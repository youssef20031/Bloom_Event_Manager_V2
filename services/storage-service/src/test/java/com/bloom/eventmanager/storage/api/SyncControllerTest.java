package com.bloom.eventmanager.storage.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;
import com.bloom.eventmanager.storage.oauth.OAuthStateStore;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.ChangeSet;
import com.bloom.eventmanager.storage.sync.DriveSyncService;
import com.bloom.eventmanager.storage.sync.SyncSummary;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ SyncController.class, AuthController.class })
@EnableConfigurationProperties(StorageProperties.class)
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveSyncService sync;

    @MockitoBean
    private AutoSyncScheduler scheduler;

    @MockitoBean
    private DriveAuthManager auth;

    @MockitoBean
    private OAuthStateStore states;

    @Test
    void statusHasV1sShapeIncludingAuthUrlWhenUnauthenticated() throws Exception {
        when(sync.status()).thenReturn(new DriveSyncService.SyncStatus("idle", false, null, null, "https://accounts.google.com/x"));
        when(scheduler.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("idle"))
                .andExpect(jsonPath("$.isAuthenticated").value(false))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/x"))
                .andExpect(jsonPath("$.autoSyncEnabled").value(false));
    }

    @Test
    void manualSyncReportsQueuedFiles() throws Exception {
        when(sync.syncAll()).thenReturn(new SyncSummary(2, 5, Instant.parse("2026-09-15T10:00:00Z")));

        mockMvc.perform(post("/api/v1/storage/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.filesQueued").value(2))
                .andExpect(jsonPath("$.syncTime").value("2026-09-15T10:00:00Z"));
    }

    @Test
    void manualSyncWithoutDriveAuthIs401WithReauthUrl() throws Exception {
        when(sync.syncAll()).thenThrow(new DriveNotAuthenticatedException());

        mockMvc.perform(post("/api/v1/storage/sync"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DRIVE_NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"))
                .andExpect(jsonPath("$.error").value("Google Drive not authenticated."));
    }

    @Test
    void checkListsNamesForAddedAndModifiedAndIdsForDeleted() throws Exception {
        when(sync.checkForUpdates()).thenReturn(new ChangeSet(
                List.of(new StorageFile("a", "a.pdf", "application/pdf", "t", 1L)), List.of(), List.of("gone")));

        mockMvc.perform(get("/api/v1/storage/sync/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasChanges").value(true))
                .andExpect(jsonPath("$.changes.added[0]").value("a.pdf"))
                .andExpect(jsonPath("$.changes.deleted[0]").value("gone"));
    }

    @Test
    void autoSyncToggleStartsAndStops() throws Exception {
        mockMvc.perform(post("/api/v1/storage/sync/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true, \"intervalMs\": 60000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Auto sync enabled"))
                .andExpect(jsonPath("$.intervalMs").value(60000));
        verify(scheduler).start(60000L);

        mockMvc.perform(post("/api/v1/storage/sync/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Auto sync disabled"));
        verify(scheduler).stop();
    }

    @Test
    void authReturnsPendingWithUrlWhenNotAuthenticated() throws Exception {
        when(auth.isConfigured()).thenReturn(true);
        when(auth.isAuthenticated()).thenReturn(false);
        when(auth.authUrl()).thenReturn("https://accounts.google.com/x");

        mockMvc.perform(get("/api/v1/storage/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/x"));
    }

    @Test
    void authForceReturnsAFreshUrlEvenWhenAuthenticated() throws Exception {
        when(auth.isConfigured()).thenReturn(true);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.authUrl()).thenReturn("https://accounts.google.com/y");

        mockMvc.perform(get("/api/v1/storage/auth").param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/y"));
    }

    @Test
    void callbackAuthenticatesStartsAutoSyncAndRedirectsToTheFrontend() throws Exception {
        when(states.consume("good-state")).thenReturn(true);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "good-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/?auth=success"));

        verify(auth).authenticate("abc");
        verify(scheduler).start(300000L);
    }

    @Test
    void callbackWithoutCodeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/storage/auth/callback")).andExpect(status().isBadRequest());
    }
}
