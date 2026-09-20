package com.bloom.eventmanager.storage.sync;

import java.time.Duration;
import java.time.Instant;
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

    /** Operator-triggered start: the stack is already up, so poll straight away. */
    public synchronized void start(long intervalMs) {
        start(intervalMs, 0L);
    }

    /**
     * At boot the first poll must wait. Feign resolves knowledge-service through
     * Eureka, and registration plus the client's first cache refresh takes tens of
     * seconds -- longer than it takes this bean to run. Polling immediately lost
     * that race on every start: the sync failed, the breaker opened, and the
     * Settings page showed an error until the next poll five minutes later.
     */
    public synchronized void start(long intervalMs, long initialDelayMs) {
        if (handle != null) {
            log.info("AutoSync already running");
            return;
        }
        this.intervalMs = intervalMs;
        handle = scheduler.scheduleWithFixedDelay(sync::autoSync,
                Instant.now().plusMillis(initialDelayMs), Duration.ofMillis(intervalMs));
        log.info("AutoSync started (interval {}s, first poll in {}s)",
                intervalMs / 1000, initialDelayMs / 1000);
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
