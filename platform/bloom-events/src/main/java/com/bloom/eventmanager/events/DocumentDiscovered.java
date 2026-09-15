package com.bloom.eventmanager.events;

/**
 * Published by storage-service when Drive polling finds a new or modified file.
 * Consumed by ingestion-service.
 */
public record DocumentDiscovered(
        String driveFileId,
        String name,
        String mimeType,
        String modifiedTime,
        String folderPath) {
}
