package com.bloom.eventmanager.knowledge.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentDeleted;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@Component
public class DocumentDeletedListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeletedListener.class);

    private final ChunkRepository repository;
    private final JsonMapper json;

    public DocumentDeletedListener(ChunkRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @KafkaListener(topics = BloomTopics.DOCUMENT_DELETED, groupId = "knowledge-service")
    public void onDocumentDeleted(String payload) {
        DocumentDeleted event = json.readValue(payload, DocumentDeleted.class);
        int removed = repository.deleteDocument(event.source());
        log.info("Deleted {} chunks for {} (Drive file {})", removed, event.source(), event.driveFileId());
    }
}
