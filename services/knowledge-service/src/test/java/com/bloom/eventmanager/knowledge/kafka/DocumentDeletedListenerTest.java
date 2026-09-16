package com.bloom.eventmanager.knowledge.kafka;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.DocumentDeleted;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentDeletedListenerTest {

    @Test
    void deletesTheDocumentNamedInTheEvent() {
        ChunkRepository repository = mock(ChunkRepository.class);
        JsonMapper json = JsonMapper.builder().build();
        DocumentDeletedListener listener = new DocumentDeletedListener(repository, json);

        listener.onDocumentDeleted(json.writeValueAsString(new DocumentDeleted("drive-9", "Clients/old.pdf")));

        verify(repository).deleteDocument("Clients/old.pdf");
    }
}
