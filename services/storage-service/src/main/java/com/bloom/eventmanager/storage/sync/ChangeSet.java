package com.bloom.eventmanager.storage.sync;

import java.util.List;

import com.bloom.eventmanager.storage.drive.StorageFile;

public record ChangeSet(List<StorageFile> added, List<StorageFile> modified, List<String> deleted) {

    public boolean hasChanges() {
        return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
    }
}
