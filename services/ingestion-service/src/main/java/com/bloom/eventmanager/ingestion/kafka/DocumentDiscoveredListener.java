package com.bloom.eventmanager.ingestion.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentDiscovered;
import com.bloom.eventmanager.ingestion.client.StorageClient;
import com.bloom.eventmanager.ingestion.domain.DocumentIngestionPipeline;
import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

/**
 * Drive-sourced ingestion. A thrown exception here means "retry, then DLT" —
 * that is the DefaultErrorHandler's job, so nothing is swallowed.
 */
@Component
public class DocumentDiscoveredListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentDiscoveredListener.class);

    private final StorageClient storage;
    private final DocumentIngestionPipeline pipeline;
    private final JsonMapper json;

    public DocumentDiscoveredListener(StorageClient storage, DocumentIngestionPipeline pipeline, JsonMapper json) {
        this.storage = storage;
        this.pipeline = pipeline;
        this.json = json;
    }

    @KafkaListener(topics = BloomTopics.DOCUMENT_DISCOVERED, groupId = "ingestion-service")
    public void onDocumentDiscovered(String payload) {
        DocumentDiscovered event = json.readValue(payload, DocumentDiscovered.class);
        log.info("Ingesting from Drive: {} ({})", event.name(), event.driveFileId());
        byte[] pdf = storage.download(event.driveFileId());
        IngestOutcome outcome = pipeline.ingest(pdf, event.name(), event.driveFileId(), true);
        log.info("Queued {} chunks for {}", outcome.chunkCount(), outcome.source());
    }
}
