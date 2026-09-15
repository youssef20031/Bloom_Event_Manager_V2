package com.bloom.eventmanager.knowledge.kafka;

import java.time.Duration;
import java.util.List;

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
import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.events.ChunkPayload;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class KafkaWiringIntegrationTests {

    // 4.1.0, not the 3.9.0 of deploy/docker-compose.yml. Testcontainers 2.0.5's
    // KafkaContainer hands the image 0.0.0.0 advertised listeners, and 3.9.0's
    // StorageTool rejects those outright ("advertised.listeners cannot use the
    // nonroutable meta-address 0.0.0.0"), so the container dies before booting.
    // 4.x dropped that check. The client is kafka-clients 4.1.2 either way, and
    // the protocol is compatible, so a 4.x test broker exercises the same wire.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    // The repository is mocked, so no Neo4j is needed; the startup index
    // initializer runs against the mock and is a no-op.
    @MockitoBean
    private ChunkRepository repository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JsonMapper json;

    @Test
    void aChunkBatchOnTheTopicReachesTheRepository() {
        ChunkBatchReady batch = new ChunkBatchReady("wired.pdf", null, 0, false,
                List.of(new ChunkPayload("wired.pdf-0-0", "t", "wired.pdf", 0, new float[] { 1f })));

        kafkaTemplate.send(BloomTopics.CHUNK_BATCH_READY, "wired.pdf", json.writeValueAsString(batch));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(repository).storeBatch(any()));
    }
}
