package com.bloom.eventmanager.storage.api.dto;

public record AutoSyncRequest(Boolean enabled, Long intervalMs) {
}
