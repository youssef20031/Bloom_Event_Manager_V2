package com.bloom.eventmanager.storage.sync;

import java.time.Instant;

public record SyncSummary(int filesQueued, int filesSeen, Instant syncTime) {
}
