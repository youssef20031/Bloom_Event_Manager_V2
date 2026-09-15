package com.bloom.eventmanager.knowledge.kafka;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

/**
 * Stores each batch as it arrives; after the batch flagged {@code last},
 * counts what is actually in the graph and announces the document as indexed.
 * Batches are keyed by source, so they arrive in order on one partition.
 */
@Component
public class ChunkBatchListener {

    private static final Logger log = LoggerFactory.getLogger(ChunkBatchListener.class);

    private final ChunkRepository repository;
    private final EventPublisher publisher;
    private final JsonMapper json;

    public ChunkBatchListener(ChunkRepository repository, EventPublisher publisher, JsonMapper json) {
        this.repository = repository;
        this.publisher = publisher;
        this.json = json;
    }

    @KafkaListener(topics = BloomTopics.CHUNK_BATCH_READY, groupId = "knowledge-service")
    public void onChunkBatch(String payload) {
        ChunkBatchReady batch = json.readValue(payload, ChunkBatchReady.class);

        if (!batch.chunks().isEmpty()) {
            List<ChunkRecord> records = batch.chunks().stream()
                    .map(c -> new ChunkRecord(c.id(), c.text(), c.source(), c.page(), c.embedding()))
                    .toList();
            repository.storeBatch(records);
        }
        log.debug("Stored batch {} ({} chunks) for {}", batch.seq(), batch.chunks().size(), batch.source());

        if (batch.last()) {
            int count = repository.countChunks(batch.source());
            publisher.publish(BloomTopics.DOCUMENT_INDEXED, batch.source(),
                    new DocumentIndexed(batch.source(), batch.driveFileId(), count, Instant.now()));
            log.info("Indexed {} ({} chunks)", batch.source(), count);
        }
    }
}
