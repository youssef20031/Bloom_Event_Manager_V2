package com.bloom.eventmanager.storage.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bloom.eventmanager.storage.drive.StorageFile;

/** V1's detectChanges: compare Drive's current listing with id -> modifiedTime we last recorded. */
public final class ChangeDetector {

    private ChangeDetector() {
    }

    public static ChangeSet detect(List<StorageFile> current, Map<String, String> known) {
        List<StorageFile> added = new ArrayList<>();
        List<StorageFile> modified = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();

        for (StorageFile file : current) {
            currentIds.add(file.id());
            String knownModified = known.get(file.id());
            if (knownModified == null) {
                added.add(file);
            }
            else if (!knownModified.equals(file.modifiedTime())) {
                modified.add(file);
            }
        }

        List<String> deleted = new ArrayList<>();
        for (String knownId : known.keySet()) {
            if (!currentIds.contains(knownId)) {
                deleted.add(knownId);
            }
        }
        return new ChangeSet(added, modified, deleted);
    }
}
