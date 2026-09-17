package com.bloom.eventmanager.storage.client;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeClientFallbackTest {

    private final KnowledgeClientFallback fallback = new KnowledgeClientFallback();

    @Test
    void everyMethodThrowsSoStorageDegradesOrReports502() {
        assertThatThrownBy(fallback::allDriveFiles)
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
        assertThatThrownBy(() -> fallback.driveFile("f1"))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
        assertThatThrownBy(() -> fallback.upsertDriveFile("f1", new DriveFileUpsert("a.pdf", "t1")))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
    }
}
