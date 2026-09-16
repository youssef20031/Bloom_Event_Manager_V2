package com.bloom.eventmanager.storage.kafka;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.storage.mongo.DriveTokenRepository;
import com.bloom.eventmanager.storage.sync.DriveSyncService;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class DocumentIndexedListenerIntegrationTests {

    // 4.1.0, as in ingestion-service: Testcontainers 2.0.5 starts the image with
    // 0.0.0.0 advertised listeners, which 3.9's StorageTool rejects.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @MockitoBean
    private DriveSyncService syncService;

    // No Mongo container: the repository is mocked so the context does not need a database.
    @MockitoBean
    private DriveTokenRepository tokenRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper json;

    @Test
    void anIndexedEventReachesTheSyncService() {
        DocumentIndexed event = new DocumentIndexed("a.pdf", "drive-a", 5, Instant.now());

        kafkaTemplate.send(BloomTopics.DOCUMENT_INDEXED, "a.pdf", json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(syncService).onIndexed(any()));
    }
}
