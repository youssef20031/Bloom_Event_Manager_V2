package com.bloom.eventmanager.storage.drive;

public record StorageFile(String id, String name, String mimeType, String modifiedTime, Long size) {
}
