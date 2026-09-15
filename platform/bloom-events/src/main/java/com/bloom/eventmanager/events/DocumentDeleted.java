package com.bloom.eventmanager.events;

/** Published by storage-service when a file disappears from Drive. */
public record DocumentDeleted(
        String driveFileId,
        String source) {
}
