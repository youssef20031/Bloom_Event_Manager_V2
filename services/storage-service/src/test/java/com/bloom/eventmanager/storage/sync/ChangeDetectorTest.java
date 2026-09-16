package com.bloom.eventmanager.storage.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.storage.drive.StorageFile;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeDetectorTest {

    @Test
    void classifiesAddedModifiedAndDeleted() {
        List<StorageFile> current = List.of(
                file("a", "a.pdf", "t1"),
                file("b", "b.pdf", "t2-new"),
                file("c", "c.pdf", "t3"));
        Map<String, String> known = Map.of("b", "t2-old", "c", "t3", "d", "t4");

        ChangeSet changes = ChangeDetector.detect(current, known);

        assertThat(changes.added()).extracting(StorageFile::id).containsExactly("a");
        assertThat(changes.modified()).extracting(StorageFile::id).containsExactly("b");
        assertThat(changes.deleted()).containsExactly("d");
        assertThat(changes.hasChanges()).isTrue();
    }

    @Test
    void noChangesWhenEverythingMatches() {
        ChangeSet changes = ChangeDetector.detect(List.of(file("a", "a.pdf", "t1")), Map.of("a", "t1"));

        assertThat(changes.hasChanges()).isFalse();
    }

    static StorageFile file(String id, String name, String modifiedTime) {
        return new StorageFile(id, name, "application/pdf", modifiedTime, 10L);
    }
}
