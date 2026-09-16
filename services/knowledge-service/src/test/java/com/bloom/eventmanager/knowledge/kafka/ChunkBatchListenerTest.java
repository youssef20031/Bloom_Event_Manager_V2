package com.bloom.eventmanager.knowledge.kafka;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.events.ChunkPayload;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkBatchListenerTest {

    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ChunkBatchListener listener = new ChunkBatchListener(repository, publisher, json);

    @Test
    void storesTheBatchAndStaysQuietWhenMoreBatchesFollow() {
        ChunkBatchReady batch = new ChunkBatchReady("guide.pdf", "drive-1", 0, false,
                List.of(new ChunkPayload("guide.pdf-0-0", "hello", "guide.pdf", 0, new float[] { 0.1f })));

        listener.onChunkBatch(json.writeValueAsString(batch));

        ArgumentCaptor<List<ChunkRecord>> stored = ArgumentCaptor.captor();
        verify(repository).storeBatch(stored.capture());
        assertThat(stored.getValue()).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo("guide.pdf-0-0");
            assertThat(record.embedding()).containsExactly(0.1f);
        });
        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void publishesDocumentIndexedWithTheStoredCountAfterTheLastBatch() {
        when(repository.countChunks("guide.pdf")).thenReturn(37);
        ChunkBatchReady last = new ChunkBatchReady("guide.pdf", "drive-1", 2, true,
                List.of(new ChunkPayload("guide.pdf-0-36", "tail", "guide.pdf", 0, new float[] { 0.1f })));

        listener.onChunkBatch(json.writeValueAsString(last));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_INDEXED), eq("guide.pdf"), event.capture());
        DocumentIndexed indexed = (DocumentIndexed) event.getValue();
        assertThat(indexed.driveFileId()).isEqualTo("drive-1");
        assertThat(indexed.chunkCount()).isEqualTo(37);
        assertThat(indexed.indexedAt()).isNotNull();
    }

    @Test
    void anEmptyLastBatchStillCompletesTheDocument() {
        when(repository.countChunks("blank.pdf")).thenReturn(0);

        listener.onChunkBatch(json.writeValueAsString(new ChunkBatchReady("blank.pdf", null, 0, true, List.of())));

        verify(repository, never()).storeBatch(any());
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_INDEXED), eq("blank.pdf"), any());
    }
}
