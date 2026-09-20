package com.bloom.eventmanager.storage.sync;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoSyncSchedulerTest {

    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final DriveSyncService sync = mock(DriveSyncService.class);
    private final AutoSyncScheduler autoSync = new AutoSyncScheduler(scheduler, sync);

    @Test
    void firstPollWaitsForTheInitialDelay() {
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(null);
        Instant before = Instant.now();

        autoSync.start(300_000L, 60_000L);

        ArgumentCaptor<Instant> startTime = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), startTime.capture(), any(Duration.class));
        // The whole point: the first run must not fire while peers are still
        // registering with Eureka. Anything at or near `before` is the bug.
        assertThat(startTime.getValue()).isAfterOrEqualTo(before.plusMillis(60_000L));
    }

    @Test
    void anOperatorTriggeredStartPollsImmediately() {
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(null);
        Instant before = Instant.now();

        autoSync.start(300_000L);

        ArgumentCaptor<Instant> startTime = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), startTime.capture(), any(Duration.class));
        // Turning auto-sync on by hand means "sync now" — the stack is already up.
        assertThat(startTime.getValue()).isBeforeOrEqualTo(before.plusMillis(1_000L));
    }
}
