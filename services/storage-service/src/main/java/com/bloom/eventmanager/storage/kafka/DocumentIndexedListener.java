package com.bloom.eventmanager.storage.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.storage.sync.DriveSyncService;

@Component
public class DocumentIndexedListener {

    private final DriveSyncService sync;
    private final JsonMapper json;

    public DocumentIndexedListener(DriveSyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    @KafkaListener(topics = BloomTopics.DOCUMENT_INDEXED, groupId = "storage-service")
    public void onDocumentIndexed(String payload) {
        sync.onIndexed(json.readValue(payload, DocumentIndexed.class));
    }
}
