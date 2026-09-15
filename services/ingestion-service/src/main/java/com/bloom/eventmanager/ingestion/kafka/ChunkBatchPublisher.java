package com.bloom.eventmanager.ingestion.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.ChunkBatchReady;

/** Keyed by source so every batch of one document stays on one partition, in order. */
@Component
public class ChunkBatchPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public ChunkBatchPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publish(ChunkBatchReady batch) {
        kafka.send(BloomTopics.CHUNK_BATCH_READY, batch.source(), json.writeValueAsString(batch)).join();
    }
}
