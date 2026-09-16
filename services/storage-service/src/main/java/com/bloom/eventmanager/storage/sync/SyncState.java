package com.bloom.eventmanager.storage.sync;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** V1's module-level lastSyncTime / syncStatus / lastSyncResult, made thread-safe. */
public class SyncState {

    public record LastResult(int filesQueued, int filesSeen, List<String> errors) {
    }

    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private volatile Instant lastSyncTime;
    private volatile LastResult lastResult;

    /** @return false when a sync is already running */
    public boolean tryStart() {
        return status.compareAndSet("idle", "syncing") || status.compareAndSet("error", "syncing");
    }

    public void start() {
        status.set("syncing");
    }

    public boolean isSyncing() {
        return "syncing".equals(status.get());
    }

    public void finish(int filesQueued, int filesSeen, List<String> errors) {
        lastSyncTime = Instant.now();
        lastResult = new LastResult(filesQueued, filesSeen, List.copyOf(errors));
        status.set("idle");
    }

    public void error() {
        status.set("error");
    }

    public String status() {
        return status.get();
    }

    public Instant lastSyncTime() {
        return lastSyncTime;
    }

    public LastResult lastResult() {
        return lastResult;
    }
}
