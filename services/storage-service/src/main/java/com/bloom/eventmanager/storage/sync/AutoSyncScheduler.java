package com.bloom.eventmanager.storage.sync;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** V1's startAutoSync/stopAutoSync: runs immediately, then every interval. */
@Component
public class AutoSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoSyncScheduler.class);

    private final TaskScheduler scheduler;
    private final DriveSyncService sync;
    private ScheduledFuture<?> handle;
    private long intervalMs;

    public AutoSyncScheduler(TaskScheduler scheduler, DriveSyncService sync) {
        this.scheduler = scheduler;
        this.sync = sync;
    }

    public synchronized void start(long intervalMs) {
        if (handle != null) {
            log.info("AutoSync already running");
            return;
        }
        this.intervalMs = intervalMs;
        handle = scheduler.scheduleWithFixedDelay(sync::autoSync, Duration.ofMillis(intervalMs));
        log.info("AutoSync started (interval {}s)", intervalMs / 1000);
    }

    public synchronized void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
            log.info("AutoSync stopped");
        }
    }

    public synchronized boolean isEnabled() {
        return handle != null;
    }

    public synchronized long intervalMs() {
        return intervalMs;
    }
}
