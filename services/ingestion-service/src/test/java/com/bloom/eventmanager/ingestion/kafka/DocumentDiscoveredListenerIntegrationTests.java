package com.bloom.eventmanager.ingestion.kafka;

import java.time.Duration;

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
import com.bloom.eventmanager.events.DocumentDiscovered;
import com.bloom.eventmanager.ingestion.client.StorageClient;
import com.bloom.eventmanager.ingestion.domain.DocumentIngestionPipeline;
import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
})
class DocumentDiscoveredListenerIntegrationTests {

    // 4.1.0, not docker-compose's former 3.9.0: Testcontainers 2.0.5 starts the
    // image with 0.0.0.0 advertised listeners, which 3.9's StorageTool rejects.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @MockitoBean
    private StorageClient storage;

    @MockitoBean
    private DocumentIngestionPipeline pipeline;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper json;

    @Test
    void downloadsAndIngestsADiscoveredDriveFileWithTheHeader() {
        when(storage.download("drive-7")).thenReturn(new byte[] { 9 });
        when(pipeline.ingest(new byte[] { 9 }, "Clients/brief.pdf", "drive-7", true))
                .thenReturn(new IngestOutcome("Clients/brief.pdf", 3, false));
        DocumentDiscovered event = new DocumentDiscovered("drive-7", "Clients/brief.pdf", "application/pdf",
                "2026-09-01T00:00:00.000Z", "Clients");

        kafkaTemplate.send(BloomTopics.DOCUMENT_DISCOVERED, "drive-7", json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                verify(pipeline).ingest(new byte[] { 9 }, "Clients/brief.pdf", "drive-7", true));
    }
}
