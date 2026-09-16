package com.bloom.eventmanager.storage.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.storage.api.dto.AutoSyncRequest;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.ChangeSet;
import com.bloom.eventmanager.storage.sync.DriveSyncService;
import com.bloom.eventmanager.storage.sync.SyncSummary;

/** V1's sync routes. Response shapes are V1's; the React app's useDriveAuthStatus reads /status. */
@RestController
@RequestMapping("/api/v1/storage/sync")
public class SyncController {

    private final DriveSyncService sync;
    private final AutoSyncScheduler scheduler;
    private final StorageProperties properties;

    public SyncController(DriveSyncService sync, AutoSyncScheduler scheduler, StorageProperties properties) {
        this.sync = sync;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @PostMapping
    public Map<String, Object> syncNow() {
        SyncSummary summary = sync.syncAll();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("filesQueued", summary.filesQueued());
        body.put("filesSeen", summary.filesSeen());
        body.put("syncTime", summary.syncTime().toString());
        return body;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return statusBody(sync.status(), scheduler.isEnabled());
    }

    @GetMapping("/check")
    public Map<String, Object> check() {
        ChangeSet changes = sync.checkForUpdates();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("hasChanges", changes.hasChanges());
        body.put("changes", Map.of(
                "added", changes.added().stream().map(StorageFile::name).toList(),
                "modified", changes.modified().stream().map(StorageFile::name).toList(),
                "deleted", changes.deleted()));
        return body;
    }

    @PostMapping("/auto")
    public Map<String, Object> toggleAutoSync(@RequestBody(required = false) AutoSyncRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        Boolean enabled = request == null ? null : request.enabled();
        if (Boolean.TRUE.equals(enabled)) {
            long interval = request.intervalMs() != null ? request.intervalMs() : properties.syncIntervalMs();
            scheduler.start(interval);
            body.put("status", "success");
            body.put("message", "Auto sync enabled");
            body.put("intervalMs", interval);
        }
        else if (Boolean.FALSE.equals(enabled)) {
            scheduler.stop();
            body.put("status", "success");
            body.put("message", "Auto sync disabled");
        }
        else {
            body.put("status", "info");
            body.put("autoSyncEnabled", scheduler.isEnabled());
            body.put("message", scheduler.isEnabled() ? "Auto sync is running" : "Auto sync is stopped");
        }
        return body;
    }

    static Map<String, Object> statusBody(DriveSyncService.SyncStatus status, boolean autoSyncEnabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.status());
        body.put("isAuthenticated", status.isAuthenticated());
        body.put("lastSyncTime", status.lastSyncTime() == null ? null : status.lastSyncTime().toString());
        body.put("lastResult", status.lastResult());
        if (status.authUrl() != null) {
            body.put("authUrl", status.authUrl());
        }
        body.put("autoSyncEnabled", autoSyncEnabled);
        return body;
    }
}
