package com.bloom.eventmanager.events;

import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkBatchReadyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void survivesJsonRoundTripIncludingTheEmbeddingVector() throws Exception {
        ChunkBatchReady event = new ChunkBatchReady(
                "handbook.pdf", "drive-file-1", 0, false,
                List.of(new ChunkPayload("c1", "hello world", "handbook.pdf", 3,
                        new float[] { 0.125f, -0.5f, 0.75f })));

        String json = mapper.writeValueAsString(event);
        ChunkBatchReady back = mapper.readValue(json, ChunkBatchReady.class);

        assertThat(back.source()).isEqualTo("handbook.pdf");
        assertThat(back.driveFileId()).isEqualTo("drive-file-1");
        assertThat(back.seq()).isZero();
        assertThat(back.last()).isFalse();
        assertThat(back.chunks()).hasSize(1);
        assertThat(back.chunks().get(0).page()).isEqualTo(3);
        assertThat(back.chunks().get(0).embedding()).containsExactly(0.125f, -0.5f, 0.75f);
    }

    @Test
    void rejectsBatchesLargerThanTheKafkaSizeGuard() {
        List<ChunkPayload> tooMany = IntStream.rangeClosed(0, ChunkBatchReady.MAX_CHUNKS_PER_BATCH)
                .mapToObj(i -> new ChunkPayload("c" + i, "t", "handbook.pdf", 1, new float[] { 0.1f }))
                .toList();

        assertThatThrownBy(() -> new ChunkBatchReady("handbook.pdf", "drive-file-1", 0, true, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    void exposesTheAgreedTopicNames() {
        assertThat(BloomTopics.DOCUMENT_DISCOVERED).isEqualTo("bloom.documents.discovered");
        assertThat(BloomTopics.CHUNK_BATCH_READY).isEqualTo("bloom.chunks.batch-ready");
        assertThat(BloomTopics.DOCUMENT_INDEXED).isEqualTo("bloom.documents.indexed");
        assertThat(BloomTopics.DOCUMENT_DELETED).isEqualTo("bloom.documents.deleted");
    }
}
