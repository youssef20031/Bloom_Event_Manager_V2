package com.bloom.eventmanager.knowledge.kafka;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
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
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        // What the fleet actually runs with: config-repo/application.yml sets this for
        // every service. It has to be restated here because "spring.config.import="
        // above does not suppress the import declared *inside* this service's own
        // application.yml - the service still calls the Config Server, so whether
        // config-repo's value arrives depends on whether the Config Server happens to
        // be up. Left to Kafka's default of 'latest', the consumer seeks past anything
        // that reached the log before the group resolved its first position, and this
        // test becomes a coin flip on that ordering.
        "spring.kafka.consumer.auto-offset-reset=earliest",
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
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JsonMapper json;

    @Test
    void aChunkBatchOnTheTopicReachesTheRepository() {
        // Listener containers start asynchronously, and both of this service's
        // @KafkaListeners share the "knowledge-service" group, so they rebalance
        // against each other for a moment after the context reports Started.
        // Publishing into a group that has not settled is what made this test a coin
        // flip; it also means a silent pass would be indistinguishable from a listener
        // that never started at all.
        await().atMost(Duration.ofSeconds(30)).until(this::everyListenerOwnsPartitions);

        ChunkBatchReady batch = new ChunkBatchReady("wired.pdf", null, 0, false,
                List.of(new ChunkPayload("wired.pdf-0-0", "t", "wired.pdf", 0, new float[] { 1f })));

        kafkaTemplate.send(BloomTopics.CHUNK_BATCH_READY, "wired.pdf", json.writeValueAsString(batch));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(repository).storeBatch(any()));
    }

    private boolean everyListenerOwnsPartitions() {
        Collection<MessageListenerContainer> containers = listeners.getListenerContainers();
        return !containers.isEmpty() && containers.stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty());
    }
}
