package com.bloom.eventmanager.storage.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

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
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JsonMapper json;

    @Test
    void anIndexedEventReachesTheSyncService() {
        // The listener container starts asynchronously, and publishing into the
        // "storage-service" group before it has claimed its partition is what made
        // this test a coin flip; it also means a silent pass would be
        // indistinguishable from a listener that never started at all.
        await().atMost(Duration.ofSeconds(30)).until(this::everyListenerOwnsPartitions);

        DocumentIndexed event = new DocumentIndexed("a.pdf", "drive-a", 5, Instant.now());

        kafkaTemplate.send(BloomTopics.DOCUMENT_INDEXED, "a.pdf", json.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> verify(syncService).onIndexed(any()));
    }

    private boolean everyListenerOwnsPartitions() {
        Collection<MessageListenerContainer> containers = listeners.getListenerContainers();
        return !containers.isEmpty() && containers.stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty());
    }
}
