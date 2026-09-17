package com.bloom.eventmanager.ingestion.client;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeClientFallbackTest {

    private final KnowledgeClientFallback fallback = new KnowledgeClientFallback();

    @Test
    void deleteDocumentThrowsSoTheKafkaListenerRetriesAndEventuallyDlts() {
        assertThatThrownBy(() -> fallback.deleteDocument("doc.pdf"))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("knowledge-service");
    }
}
