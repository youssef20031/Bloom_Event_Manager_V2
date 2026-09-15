# Phase 3a — Write Path: Ingestion over Kafka

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make documents flow into Neo4j through Kafka: `ingestion-service` parses PDFs (PDFBox, Tess4J OCR fallback), chunks and embeds them, and publishes `ChunkBatchReady` batches; `knowledge-service` consumes them, stores chunks, and publishes `DocumentIndexed`. Proven end to end with a local-directory ingest — no Google Drive yet (that is Phase 3b).

**Architecture:** Kafka carries JSON strings (Boot's default `StringSerializer`); each service serialises the `bloom-events` records with Jackson 3's `JsonMapper`. Messages are keyed by `source` so every batch of one document lands on one partition in order. Consumers use Spring Kafka's `DefaultErrorHandler` with exponential backoff and a dead-letter topic. Chunk ids are deterministic (`<source>-0-<n>`, V1's scheme) and `knowledge-service` writes with `MERGE`, so redelivery is harmless.

**Tech Stack:** Spring Boot 4.0.8, Spring Kafka 4.0.7, Apache PDFBox 3.0.8, Tess4J 5.20.0, OpenFeign, Testcontainers 2.0.5 (`testcontainers-kafka`), JUnit 5, Mockito, Awaitility.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` (sections 5.2 ingestion/knowledge, 6, 8, 13)

**Prerequisite:** Phase 2 complete.

## Global Constraints

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.**
- **Kafka:** `spring-boot-starter-kafka`. Serializers stay Boot's default `StringSerializer`/`StringDeserializer`; payloads are JSON strings produced and parsed with `tools.jackson.databind.json.JsonMapper`. Do not configure `JacksonJsonSerializer`/`JsonSerializer` type mapping — one payload type per topic, parsed explicitly, keeps consumers trivially testable.
- **Topics and records are the Phase 0 `bloom-events` contracts, unchanged:** `bloom.documents.discovered`, `bloom.chunks.batch-ready`, `bloom.documents.indexed`, `bloom.documents.deleted`; `ChunkBatchReady.MAX_CHUNKS_PER_BATCH = 20`.
- **Message key = `source`** for chunk batches and indexed events, so batches of one document are ordered on one partition.
- **Testcontainers 2.0.5:** artifact `org.testcontainers:testcontainers-kafka`; class `org.testcontainers.kafka.KafkaContainer` (Boot 4.0.8's `@ServiceConnection` factory binds that class); image `apache/kafka:3.9.0` as in `deploy/docker-compose.yml`. Integration tests are `*IntegrationTests` with `@Testcontainers(disabledWithoutDocker = true)`.
- **`@SpringBootTest` classes in Kafka-using services set `spring.kafka.listener.auto-startup=false` unless they provide a Kafka container** — otherwise listener containers hammer `localhost:9092` in the log for the whole test.
- **Chunking is V1's `lib/chunkText.ts`** (the one on V1's production paths): ids `<source>-0-<index>`, `start += chunkSize - overlap`, page 0. Not the uuid-based chunker in `IngestDocumentUseCase.ts`, which nothing in V1 production called.
- **OCR is Tess4J for both ingest paths.** V1 used Tesseract for Drive files and Gemini Vision for local-directory files; the spec maps OCR to Tess4J, so the local path now uses it too. One OCR strategy.
- **"File Name:" header:** V1 prepended `File Name: <name>\n\n` for Drive-sourced text only. Preserved as a per-call flag: `true` for Drive (Phase 3b), `false` for the local directory.
- **`eng.traineddata`** is copied from `E:\Bloom_Event_Manager\eng.traineddata` into `services/ingestion-service/src/main/resources/tessdata/` (5 MB, committed) and extracted at runtime with Tess4J's `LoadLibs.extractTessResources("tessdata")`. Override with `bloom.ingestion.ocr-data-path` to use a system install.
- **Commit with explicit paths, never `git add -A`.**

---

## File Structure

```
platform/bloom-common/.../error/ErrorCode.java              MODIFY: CONFLICT, DRIVE_NOT_AUTHENTICATED
platform/bloom-common/.../error/BloomException.java         MODIFY: extension properties
platform/bloom-common/.../error/GlobalExceptionHandler.java MODIFY: copy properties into ProblemDetail
config-repo/application.yml                                 MODIFY: shared Kafka settings

services/knowledge-service/
  pom.xml                                                   MODIFY: kafka, bloom-events, testcontainers-kafka
  .../domain/ChunkRepository.java                           MODIFY: countChunks
  .../neo4j/Neo4jChunkRepository.java                       MODIFY: countChunks
  .../kafka/KafkaConfig.java                                topic + error handler beans
  .../kafka/EventPublisher.java
  .../kafka/ChunkBatchListener.java
  .../kafka/DocumentDeletedListener.java
  test: kafka/ChunkBatchListenerTest, kafka/DocumentDeletedListenerTest,
        kafka/KafkaWiringIntegrationTests, neo4j/Neo4jChunkRepositoryIntegrationTests (MODIFY)

services/ingestion-service/
  pom.xml
  src/main/resources/tessdata/eng.traineddata               copied from V1
  src/main/resources/application.yml
  .../IngestionServiceApplication.java
  .../config/IngestionProperties.java
  .../config/KafkaConfig.java
  .../client/AiClient.java, KnowledgeClient.java, StorageClient.java (+ dto)
  .../domain/TextChunk.java, TextChunker.java, TextExtractor.java, OcrEngine.java,
             IngestOutcome.java, DocumentIngestionPipeline.java
  .../pdf/PdfBoxTextExtractor.java
  .../ocr/TesseractOcrEngine.java
  .../kafka/ChunkBatchPublisher.java, DocumentDiscoveredListener.java
  .../local/IngestJob.java, IngestJobRegistry.java, LocalDirectoryIngestService.java
  .../api/IngestController.java (+ dto)
  test: domain/TextChunkerTest, pdf/PdfBoxTextExtractorTest, ocr/TesseractOcrEngineTest,
        domain/DocumentIngestionPipelineTest, kafka/DocumentDiscoveredListenerIntegrationTests,
        local/LocalDirectoryIngestServiceTest, api/IngestControllerTest
config-repo/ingestion-service.yml
infrastructure/api-gateway/.../application.yml              MODIFY: ingest route
pom.xml                                                     MODIFY: module
```

---

### Task 1: Shared groundwork — error properties, new codes, Kafka defaults

**Files:**
- Modify: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/ErrorCode.java`
- Modify: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/BloomException.java`
- Modify: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/GlobalExceptionHandler.java`
- Modify: `platform/bloom-common/src/test/java/com/bloom/eventmanager/common/error/GlobalExceptionHandlerTest.java`
- Modify: `config-repo/application.yml`

**Interfaces:**
- Produces: `ErrorCode.CONFLICT` (409), `ErrorCode.DRIVE_NOT_AUTHENTICATED` (401); `BloomException.properties() -> Map<String, Object>` (overridable, default empty) whose entries are copied onto the ProblemDetail. Phase 3b's `DriveNotAuthenticatedException` uses it to carry `authUrl`, which the React app reads.

- [ ] **Step 1: Write the failing test** — append to `GlobalExceptionHandlerTest`:

```java
    @Test
    void copiesExceptionPropertiesOntoTheProblemDetail() {
        BloomException withProps = new BloomException(ErrorCode.DRIVE_NOT_AUTHENTICATED, "Google Drive not authenticated.") {
            @Override
            public java.util.Map<String, Object> properties() {
                return java.util.Map.of("authUrl", "/api/v1/rag/sync/auth?force=true");
            }
        };

        ProblemDetail problem = handler.handleBloom(withProps);

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getProperties()).containsEntry("authUrl", "/api/v1/rag/sync/auth?force=true")
                .containsEntry("errorCode", "DRIVE_NOT_AUTHENTICATED");
    }

    @Test
    void conflictMapsTo409() {
        ProblemDetail problem = handler.handleBloom(new BloomException(ErrorCode.CONFLICT, "Sync already in progress"));

        assertThat(problem.getStatus()).isEqualTo(409);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: COMPILATION FAILURE — `cannot find symbol: DRIVE_NOT_AUTHENTICATED`

- [ ] **Step 3: Implement**

`ErrorCode.java` — add two constants after `EXTERNAL_SERVICE_ERROR`:

```java
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    DRIVE_NOT_AUTHENTICATED(HttpStatus.UNAUTHORIZED, "Google Drive Not Authenticated"),
```

`BloomException.java` — add after `errorCode()`:

```java
    /**
     * Extra members for the RFC 9457 body. Subclasses override to add things
     * like a re-auth URL; the handler copies every entry onto the ProblemDetail.
     */
    public java.util.Map<String, Object> properties() {
        return java.util.Map.of();
    }
```

`GlobalExceptionHandler.handleBloom` becomes:

```java
    @ExceptionHandler(BloomException.class)
    public ProblemDetail handleBloom(BloomException ex) {
        ProblemDetail problem = toProblem(ex.errorCode(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return problem;
    }
```

`config-repo/application.yml` — append:

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      # ChunkBatchReady: up to 20 chunks x ~12 KB embeddings. Compress, and raise
      # the ceiling well above the 1 MB default. Keep in step with the broker's
      # KAFKA_MESSAGE_MAX_BYTES in deploy/docker-compose.yml.
      compression-type: lz4
      properties:
        max.request.size: 5242880
    consumer:
      auto-offset-reset: earliest
      properties:
        max.partition.fetch.bytes: 5242880
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: PASS — 7 tests in `GlobalExceptionHandlerTest`

- [ ] **Step 5: Commit**

```bash
git add platform/bloom-common config-repo/application.yml
git commit -m "feat(common): problem-detail extension properties; shared Kafka config"
```

---

### Task 2: knowledge-service consumes chunk batches and publishes DocumentIndexed

**Files:**
- Modify: `services/knowledge-service/pom.xml`
- Modify: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/domain/ChunkRepository.java`
- Modify: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/neo4j/Neo4jChunkRepository.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/kafka/KafkaConfig.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/kafka/EventPublisher.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/kafka/ChunkBatchListener.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/kafka/DocumentDeletedListener.java`
- Test: `.../kafka/ChunkBatchListenerTest.java`, `.../kafka/DocumentDeletedListenerTest.java`, `.../kafka/KafkaWiringIntegrationTests.java`
- Modify: `.../neo4j/Neo4jChunkRepositoryIntegrationTests.java` (one test for `countChunks`)

**Interfaces:**
- Consumes: `ChunkBatchReady`, `DocumentDeleted` from `bloom-events`; `ChunkRepository`
- Produces: `ChunkRepository.countChunks(String source) -> int`; `EventPublisher.publish(String topic, String key, Object payload)`; `DocumentIndexed` on `bloom.documents.indexed` after the `last` batch of a source.

- [ ] **Step 1: Write the failing tests**

Add to `Neo4jChunkRepositoryIntegrationTests`:

```java
    @Test
    void countsChunksPerDocument() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "y", "guide.pdf", 2, new float[] { 0f, 1f, 0f, 0f })));

        assertThat(repository.countChunks("guide.pdf")).isEqualTo(2);
        assertThat(repository.countChunks("missing.pdf")).isZero();
    }
```

`ChunkBatchListenerTest.java`:

```java
package com.bloom.eventmanager.knowledge.kafka;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.events.ChunkPayload;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkBatchListenerTest {

    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ChunkBatchListener listener = new ChunkBatchListener(repository, publisher, json);

    @Test
    void storesTheBatchAndStaysQuietWhenMoreBatchesFollow() {
        ChunkBatchReady batch = new ChunkBatchReady("guide.pdf", "drive-1", 0, false,
                List.of(new ChunkPayload("guide.pdf-0-0", "hello", "guide.pdf", 0, new float[] { 0.1f })));

        listener.onChunkBatch(json.writeValueAsString(batch));

        ArgumentCaptor<List<ChunkRecord>> stored = ArgumentCaptor.captor();
        verify(repository).storeBatch(stored.capture());
        assertThat(stored.getValue()).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo("guide.pdf-0-0");
            assertThat(record.embedding()).containsExactly(0.1f);
        });
        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void publishesDocumentIndexedWithTheStoredCountAfterTheLastBatch() {
        when(repository.countChunks("guide.pdf")).thenReturn(37);
        ChunkBatchReady last = new ChunkBatchReady("guide.pdf", "drive-1", 2, true,
                List.of(new ChunkPayload("guide.pdf-0-36", "tail", "guide.pdf", 0, new float[] { 0.1f })));

        listener.onChunkBatch(json.writeValueAsString(last));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_INDEXED), eq("guide.pdf"), event.capture());
        DocumentIndexed indexed = (DocumentIndexed) event.getValue();
        assertThat(indexed.driveFileId()).isEqualTo("drive-1");
        assertThat(indexed.chunkCount()).isEqualTo(37);
        assertThat(indexed.indexedAt()).isNotNull();
    }

    @Test
    void anEmptyLastBatchStillCompletesTheDocument() {
        when(repository.countChunks("blank.pdf")).thenReturn(0);

        listener.onChunkBatch(json.writeValueAsString(new ChunkBatchReady("blank.pdf", null, 0, true, List.of())));

        verify(repository, never()).storeBatch(any());
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_INDEXED), eq("blank.pdf"), any());
    }
}
```

`DocumentDeletedListenerTest.java`:

```java
package com.bloom.eventmanager.knowledge.kafka;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.events.DocumentDeleted;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentDeletedListenerTest {

    @Test
    void deletesTheDocumentNamedInTheEvent() {
        ChunkRepository repository = mock(ChunkRepository.class);
        JsonMapper json = JsonMapper.builder().build();
        DocumentDeletedListener listener = new DocumentDeletedListener(repository, json);

        listener.onDocumentDeleted(json.writeValueAsString(new DocumentDeleted("drive-9", "Clients/old.pdf")));

        verify(repository).deleteDocument("Clients/old.pdf");
    }
}
```

`KafkaWiringIntegrationTests.java`:

```java
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

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.0");

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
```

- [ ] **Step 2: Update the POM** — add to `<dependencies>` of `services/knowledge-service/pom.xml`:

```xml
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-events</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-kafka</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl services/knowledge-service test -Dtest='ChunkBatchListenerTest,DocumentDeletedListenerTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ChunkBatchListener`

- [ ] **Step 4: Implement**

Add to `ChunkRepository`:

```java
    /** Number of Chunk nodes hanging off the Document with this source; 0 if none. */
    int countChunks(String source);
```

Add to `Neo4jChunkRepository`:

```java
    @Override
    public int countChunks(String source) {
        String cypher = """
                OPTIONAL MATCH (:Document {id: $source})-[:HAS_CHUNK]->(c:Chunk)
                RETURN count(c) AS n
                """;
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of("source", source)).single().get("n").asInt();
        }
    }
```

`kafka/KafkaConfig.java`:

```java
package com.bloom.eventmanager.knowledge.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.bloom.eventmanager.events.BloomTopics;

@Configuration
public class KafkaConfig {

    /** knowledge-service owns this topic: it is the only producer. */
    @Bean
    NewTopic documentIndexedTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_INDEXED).partitions(3).replicas(1).build();
    }

    /**
     * Retry with backoff, then park the record on {@code <topic>.DLT}. Boot
     * wires any CommonErrorHandler bean into the listener container factory.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    }
}
```

`kafka/EventPublisher.java`:

```java
package com.bloom.eventmanager.knowledge.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** JSON-string publishing. {@code join()} surfaces broker failures to the caller. */
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public EventPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publish(String topic, String key, Object payload) {
        kafka.send(topic, key, json.writeValueAsString(payload)).join();
    }
}
```

`kafka/ChunkBatchListener.java`:

```java
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
```

`kafka/DocumentDeletedListener.java`:

```java
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
```

- [ ] **Step 5: Run all knowledge-service tests**

Run: `./mvnw -q -pl services/knowledge-service test`
Expected: PASS — previous 25 + 3 + 1 + 1 (Kafka IT) + 1 (count IT) = 31; Docker-dependent ones skip without Docker.

- [ ] **Step 6: Commit**

```bash
git add services/knowledge-service
git commit -m "feat(knowledge): consume chunk batches and deletions, publish DocumentIndexed"
```

---

### Task 3: ingestion-service — module, chunker, PDF text, OCR

**Files:**
- Create: `services/ingestion-service/pom.xml`
- Copy: `E:\Bloom_Event_Manager\eng.traineddata` → `services/ingestion-service/src/main/resources/tessdata/eng.traineddata`
- Create: `services/ingestion-service/src/main/resources/application.yml`
- Create: `.../ingestion/IngestionServiceApplication.java`
- Create: `.../ingestion/config/IngestionProperties.java`
- Create: `.../ingestion/domain/TextChunk.java`, `TextChunker.java`, `TextExtractor.java`, `OcrEngine.java`
- Create: `.../ingestion/pdf/PdfBoxTextExtractor.java`
- Create: `.../ingestion/ocr/TesseractOcrEngine.java`
- Test: `.../domain/TextChunkerTest.java`, `.../pdf/PdfBoxTextExtractorTest.java`, `.../ocr/TesseractOcrEngineTest.java`
- Modify: `pom.xml` (module)

**Interfaces:**
- Produces:
  - `IngestionProperties(String pdfDirectory, int chunkSize, int chunkOverlap, int ocrMinChars, float ocrDpi, String ocrLanguage, String ocrDataPath, int embeddingBatchSize)`
  - `TextChunk(String id, String text, String source, int page)`
  - `TextChunker.chunk(String text, String source) -> List<TextChunk>`
  - `TextExtractor.extract(byte[] pdf) -> String` ("" when unparseable)
  - `OcrEngine.recognize(byte[] pdf) -> String` ("" when nothing recognised) and `OcrEngine.available() -> boolean`

- [ ] **Step 1: Write the failing tests**

`TextChunkerTest.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    private static TextChunker chunker(int size, int overlap) {
        return new TextChunker(new IngestionProperties("./ISM_PDF", size, overlap, 100, 300f, "eng", "", 20));
    }

    @Test
    void slidesAWindowWithOverlapExactlyLikeV1() {
        List<TextChunk> chunks = chunker(4, 1).chunk("abcdefghij", "doc.pdf");

        assertThat(chunks).extracting(TextChunk::text).containsExactly("abcd", "defg", "ghij", "j");
        assertThat(chunks).extracting(TextChunk::id)
                .containsExactly("doc.pdf-0-0", "doc.pdf-0-1", "doc.pdf-0-2", "doc.pdf-0-3");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.source()).isEqualTo("doc.pdf");
            assertThat(c.page()).isZero();
        });
    }

    @Test
    void emptyTextYieldsNoChunks() {
        assertThat(chunker(1000, 200).chunk("", "doc.pdf")).isEmpty();
    }

    @Test
    void textShorterThanOneChunkIsOneChunk() {
        assertThat(chunker(1000, 200).chunk("short", "doc.pdf")).singleElement()
                .satisfies(c -> assertThat(c.text()).isEqualTo("short"));
    }

    @Test
    void refusesAnOverlapThatWouldNeverAdvance() {
        assertThatThrownBy(() -> chunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

`PdfBoxTextExtractorTest.java`:

```java
package com.bloom.eventmanager.ingestion.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfBoxTextExtractorTest {

    private final PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

    @Test
    void extractsTextFromARealPdf() throws IOException {
        assertThat(extractor.extract(pdfWithText("Team building on page one"))).contains("Team building on page one");
    }

    @Test
    void returnsEmptyForBytesThatAreNotAPdf() {
        assertThat(extractor.extract("definitely not a pdf".getBytes())).isEmpty();
    }

    static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 36);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
```

`TesseractOcrEngineTest.java`:

```java
package com.bloom.eventmanager.ingestion.ocr;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.pdf.PdfBoxTextExtractorTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TesseractOcrEngineTest {

    private final TesseractOcrEngine engine = new TesseractOcrEngine(
            new IngestionProperties("./ISM_PDF", 1000, 200, 100, 300f, "eng", "", 20));

    @Test
    void recognisesTextRenderedIntoAPageImage() throws IOException {
        // Tess4J bundles Windows natives; Linux needs libtesseract installed.
        // Skip rather than fail where the native library is absent.
        assumeTrue(engine.available(), "Tesseract native library not available");

        String text = engine.recognize(PdfBoxTextExtractorTest.pdfWithText("HELLO WORLD"));

        assertThat(text.toUpperCase()).contains("HELLO");
    }

    @Test
    void returnsEmptyForBytesThatAreNotAPdf() {
        assertThat(engine.recognize("nope".getBytes())).isEmpty();
    }
}
```

- [ ] **Step 2: Create the module**

`services/ingestion-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.bloom.eventmanager</groupId>
        <artifactId>bloom-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>ingestion-service</artifactId>
    <name>Ingestion Service</name>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-bom</artifactId>
                <version>${project.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-events</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
        </dependency>
        <dependency>
            <groupId>net.sourceforge.tess4j</groupId>
            <artifactId>tess4j</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Add `<module>services/ingestion-service</module>` to the root `pom.xml` after `services/retrieval-service`.

Copy the trained data:

```bash
mkdir -p services/ingestion-service/src/main/resources/tessdata
cp /e/Bloom_Event_Manager/eng.traineddata services/ingestion-service/src/main/resources/tessdata/
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: ingestion-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}

server:
  port: 8083
```

`IngestionServiceApplication.java`:

```java
package com.bloom.eventmanager.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
@EnableAsync
@EnableFeignClients(basePackages = "com.bloom.eventmanager.ingestion.client")
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
```

`config/IngestionProperties.java`:

```java
package com.bloom.eventmanager.ingestion.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Defaults are V1's CONSTANTS and .env defaults. */
@Validated
@ConfigurationProperties(prefix = "bloom.ingestion")
public record IngestionProperties(
        @DefaultValue("./ISM_PDF") String pdfDirectory,
        @DefaultValue("1000") @Min(50) int chunkSize,
        @DefaultValue("200") @Min(0) int chunkOverlap,
        /** Below this many extracted characters the PDF is treated as scanned and OCR runs. */
        @DefaultValue("100") @Min(0) int ocrMinChars,
        @DefaultValue("300") float ocrDpi,
        @DefaultValue("eng") String ocrLanguage,
        /** Empty means: extract the bundled tessdata from the classpath. */
        @DefaultValue("") String ocrDataPath,
        @DefaultValue("20") @Min(1) @Max(100) int embeddingBatchSize) {
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl services/ingestion-service test -Dtest=TextChunkerTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class TextChunker`

- [ ] **Step 4: Implement**

`domain/TextChunk.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

public record TextChunk(String id, String text, String source, int page) {
}
```

`domain/TextChunker.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;

/**
 * V1's {@code lib/chunkText.ts}: a fixed window sliding by (size - overlap),
 * ids {@code <source>-<page>-<index>} with page always 0. Deterministic ids
 * are what make re-ingestion and Kafka redelivery idempotent downstream.
 */
@Component
public class TextChunker {

    private final int chunkSize;
    private final int overlap;

    public TextChunker(IngestionProperties properties) {
        if (properties.chunkOverlap() >= properties.chunkSize()) {
            throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize");
        }
        this.chunkSize = properties.chunkSize();
        this.overlap = properties.chunkOverlap();
    }

    public List<TextChunk> chunk(String text, String source) {
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(new TextChunk(source + "-0-" + index, text.substring(start, end), source, 0));
            start += chunkSize - overlap;
            index++;
        }
        return chunks;
    }
}
```

`domain/TextExtractor.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

public interface TextExtractor {

    /** Text layer of the PDF; empty when there is none or the bytes are not a PDF. */
    String extract(byte[] pdf);
}
```

`domain/OcrEngine.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

public interface OcrEngine {

    boolean available();

    /** Recognised text across all pages; empty when nothing was recognised. */
    String recognize(byte[] pdf);
}
```

`pdf/PdfBoxTextExtractor.java`:

```java
package com.bloom.eventmanager.ingestion.pdf;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ingestion.domain.TextExtractor;

@Component
public class PdfBoxTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxTextExtractor.class);

    @Override
    public String extract(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
        catch (IOException ex) {
            // V1: a parse failure is not fatal — the OCR fallback gets its turn.
            log.warn("PDF text extraction failed ({}); OCR fallback will run", ex.getMessage());
            return "";
        }
    }
}
```

`ocr/TesseractOcrEngine.java`:

```java
package com.bloom.eventmanager.ingestion.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.domain.OcrEngine;

/**
 * Renders each page to an image and runs Tesseract on it.
 *
 * <p>V1 dug embedded images out of the PDF operator list and OCR'd those.
 * Rendering the whole page is simpler, catches text drawn as vector paths
 * too, and is the standard PDFBox + Tess4J approach.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    private final float dpi;
    private final String language;
    private final Path dataPath;

    public TesseractOcrEngine(IngestionProperties properties) {
        this.dpi = properties.ocrDpi();
        this.language = properties.ocrLanguage();
        this.dataPath = properties.ocrDataPath().isBlank()
                ? LoadLibs.extractTessResources("tessdata").toPath()
                : Path.of(properties.ocrDataPath());
    }

    @Override
    public boolean available() {
        try {
            newTesseract().doOCR(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
            return true;
        }
        catch (Throwable unavailable) {
            log.warn("Tesseract not available: {}", unavailable.toString());
            return false;
        }
    }

    @Override
    public String recognize(byte[] pdf) {
        StringBuilder text = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            Tesseract tesseract = newTesseract();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank()) {
                    text.append(pageText).append('\n');
                    log.info("OCR page {}: {} chars", page + 1, pageText.length());
                }
            }
        }
        catch (IOException | TesseractException ex) {
            log.warn("OCR failed ({}); continuing with what was recognised", ex.getMessage());
        }
        return text.toString();
    }

    private Tesseract newTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(dataPath.toString());
        tesseract.setLanguage(language);
        return tesseract;
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q -pl services/ingestion-service test -Dtest='TextChunkerTest,PdfBoxTextExtractorTest,TesseractOcrEngineTest'`
Expected: PASS — 4 + 2 + 2 (the OCR recognition test reports *skipped* where Tesseract's native library is missing; on Windows with Tess4J's bundled DLLs it runs).

- [ ] **Step 6: Commit**

```bash
git add pom.xml services/ingestion-service
git commit -m "feat(ingestion): module with V1 chunker, PDFBox extraction and Tess4J OCR"
```

---

### Task 4: The ingestion pipeline and Feign clients

**Files:**
- Create: `.../ingestion/client/AiClient.java` and `.../client/dto/EmbedBatchRequest.java`, `EmbedBatchResponse.java`
- Create: `.../ingestion/client/KnowledgeClient.java` and `.../client/dto/DeleteDocumentResponse.java`
- Create: `.../ingestion/client/StorageClient.java`
- Create: `.../ingestion/kafka/ChunkBatchPublisher.java`
- Create: `.../ingestion/domain/IngestOutcome.java`, `DocumentIngestionPipeline.java`
- Test: `.../domain/DocumentIngestionPipelineTest.java`

**Interfaces:**
- Consumes: `TextExtractor`, `OcrEngine`, `TextChunker`, `AiClient.embedBatch`, `KnowledgeClient.deleteDocument`, `ChunkBatchPublisher.publish(ChunkBatchReady)`
- Produces:
  - `DocumentIngestionPipeline.ingest(byte[] pdf, String source, String driveFileId, boolean includeFileNameHeader) -> IngestOutcome`
  - `IngestOutcome(String source, int chunkCount, boolean ocrUsed)`
  - `StorageClient.download(String fileId) -> byte[]` against `GET /api/v1/storage/files/{fileId}/content` (implemented in Phase 3b)

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.ingestion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.ingestion.client.AiClient;
import com.bloom.eventmanager.ingestion.client.KnowledgeClient;
import com.bloom.eventmanager.ingestion.client.dto.DeleteDocumentResponse;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.kafka.ChunkBatchPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionPipelineTest {

    private static final byte[] PDF = { 1, 2, 3 };

    private final TextExtractor extractor = mock(TextExtractor.class);
    private final OcrEngine ocr = mock(OcrEngine.class);
    private final AiClient ai = mock(AiClient.class);
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final ChunkBatchPublisher publisher = mock(ChunkBatchPublisher.class);
    // chunkSize 10 / overlap 2 keeps fixtures tiny; batch size 5 for embedding.
    private final IngestionProperties properties = new IngestionProperties("./ISM_PDF", 10, 2, 100, 300f, "eng", "", 5);
    private final DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
            extractor, ocr, new TextChunker(properties), ai, knowledge, publisher, properties);

    @Test
    void parsesChunksEmbedsDeletesOldChunksThenPublishesOrderedBatches() {
        when(extractor.extract(PDF)).thenReturn("x".repeat(400)); // 400 chars, window 10, step 8 -> 50 chunks
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));
        when(knowledge.deleteDocument("doc.pdf")).thenReturn(new DeleteDocumentResponse(3));

        IngestOutcome outcome = pipeline.ingest(PDF, "doc.pdf", "drive-1", false);

        assertThat(outcome.chunkCount()).isEqualTo(50);
        assertThat(outcome.ocrUsed()).isFalse();
        verify(ocr, never()).recognize(any());

        InOrder order = inOrder(knowledge, publisher);
        order.verify(knowledge).deleteDocument("doc.pdf");
        ArgumentCaptor<ChunkBatchReady> batches = ArgumentCaptor.forClass(ChunkBatchReady.class);
        order.verify(publisher, times(3)).publish(batches.capture());

        List<ChunkBatchReady> published = batches.getAllValues();
        assertThat(published).extracting(ChunkBatchReady::seq).containsExactly(0, 1, 2);
        assertThat(published).extracting(ChunkBatchReady::last).containsExactly(false, false, true);
        assertThat(published).extracting(b -> b.chunks().size()).containsExactly(20, 20, 10);
        assertThat(published.get(0).driveFileId()).isEqualTo("drive-1");
        assertThat(published.get(0).chunks().get(0).id()).isEqualTo("doc.pdf-0-0");
        assertThat(published.get(2).chunks().get(9).id()).isEqualTo("doc.pdf-0-49");
        assertThat(published.get(0).chunks().get(0).embedding()).containsExactly(0f);
    }

    @Test
    void embedsInSlicesOfTheConfiguredBatchSize() {
        when(extractor.extract(PDF)).thenReturn("x".repeat(96)); // 12 chunks -> 3 slices of 5,5,2
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        pipeline.ingest(PDF, "doc.pdf", null, false);

        ArgumentCaptor<EmbedBatchRequest> requests = ArgumentCaptor.forClass(EmbedBatchRequest.class);
        verify(ai, times(3)).embedBatch(requests.capture());
        assertThat(requests.getAllValues()).extracting(r -> r.texts().size()).containsExactly(5, 5, 2);
    }

    @Test
    void fallsBackToOcrWhenTheTextLayerIsTooShort() {
        when(extractor.extract(PDF)).thenReturn("   ");
        when(ocr.recognize(PDF)).thenReturn("scanned words here");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        IngestOutcome outcome = pipeline.ingest(PDF, "scan.pdf", null, false);

        assertThat(outcome.ocrUsed()).isTrue();
        assertThat(outcome.chunkCount()).isGreaterThan(0);
    }

    @Test
    void keepsTheShortTextLayerWhenOcrFindsNothing() {
        when(extractor.extract(PDF)).thenReturn("tiny");
        when(ocr.recognize(PDF)).thenReturn("");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        IngestOutcome outcome = pipeline.ingest(PDF, "tiny.pdf", null, false);

        assertThat(outcome.ocrUsed()).isFalse();
        assertThat(outcome.chunkCount()).isEqualTo(1);
    }

    @Test
    void prependsTheFileNameHeaderForDriveFilesOnly() {
        when(extractor.extract(PDF)).thenReturn("body text that is long enough to skip the ocr fallback for good measure, yes indeed it is long enough now");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        pipeline.ingest(PDF, "brief.pdf", "drive-1", true);

        ArgumentCaptor<ChunkBatchReady> batch = ArgumentCaptor.forClass(ChunkBatchReady.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publish(batch.capture());
        assertThat(batch.getAllValues().get(0).chunks().get(0).text()).startsWith("File Name: brief.pdf");
    }

    @Test
    void anUnreadableDocumentPublishesOneEmptyLastBatchSoItStillCountsAsProcessed() {
        when(extractor.extract(PDF)).thenReturn("");
        when(ocr.recognize(PDF)).thenReturn("");

        IngestOutcome outcome = pipeline.ingest(PDF, "blank.pdf", "drive-2", true);

        assertThat(outcome.chunkCount()).isZero();
        verify(ai, never()).embedBatch(any());
        ArgumentCaptor<ChunkBatchReady> batch = ArgumentCaptor.forClass(ChunkBatchReady.class);
        verify(publisher).publish(batch.capture());
        assertThat(batch.getValue().chunks()).isEmpty();
        assertThat(batch.getValue().last()).isTrue();
        assertThat(batch.getValue().driveFileId()).isEqualTo("drive-2");
    }

    private static EmbedBatchResponse embeddingsFor(EmbedBatchRequest request) {
        List<float[]> vectors = new ArrayList<>();
        IntStream.range(0, request.texts().size()).forEach(i -> vectors.add(new float[] { i }));
        return new EmbedBatchResponse(vectors);
    }
}
```

The first test's `embedding ... containsExactly(0f)` relies on each embed slice numbering from 0; the point is that embedding *i* of a slice lands on chunk *i* of that slice.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/ingestion-service test -Dtest=DocumentIngestionPipelineTest`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.ingestion.client does not exist`

- [ ] **Step 3: Implement**

`client/dto/EmbedBatchRequest.java`:

```java
package com.bloom.eventmanager.ingestion.client.dto;

import java.util.List;

public record EmbedBatchRequest(List<String> texts) {
}
```

`client/dto/EmbedBatchResponse.java`:

```java
package com.bloom.eventmanager.ingestion.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedBatchResponse(List<float[]> embeddings) {
}
```

`client/dto/DeleteDocumentResponse.java`:

```java
package com.bloom.eventmanager.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeleteDocumentResponse(int deletedCount) {
}
```

`client/AiClient.java`:

```java
package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchResponse;

@FeignClient(name = "ai-service")
public interface AiClient {

    @PostMapping("/api/v1/ai/embed/batch")
    EmbedBatchResponse embedBatch(@RequestBody EmbedBatchRequest request);
}
```

`client/KnowledgeClient.java`:

```java
package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bloom.eventmanager.ingestion.client.dto.DeleteDocumentResponse;

@FeignClient(name = "knowledge-service")
public interface KnowledgeClient {

    @DeleteMapping("/api/v1/documents")
    DeleteDocumentResponse deleteDocument(@RequestParam("source") String source);
}
```

`client/StorageClient.java`:

```java
package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Implemented by storage-service in Phase 3b. */
@FeignClient(name = "storage-service")
public interface StorageClient {

    @GetMapping(value = "/api/v1/storage/files/{fileId}/content", produces = "application/octet-stream")
    byte[] download(@PathVariable("fileId") String fileId);
}
```

`kafka/ChunkBatchPublisher.java`:

```java
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
```

`domain/IngestOutcome.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

public record IngestOutcome(String source, int chunkCount, boolean ocrUsed) {
}
```

`domain/DocumentIngestionPipeline.java`:

```java
package com.bloom.eventmanager.ingestion.domain;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.events.ChunkPayload;
import com.bloom.eventmanager.ingestion.client.AiClient;
import com.bloom.eventmanager.ingestion.client.KnowledgeClient;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.kafka.ChunkBatchPublisher;

/**
 * V1's ingest steps in order: text layer → OCR fallback → (header) → chunk →
 * embed → clear the old document → publish batches of at most 20 chunks.
 *
 * <p>The old document is always deleted first (V1's local path did this).
 * Chunk ids are deterministic, so the MERGE downstream would overwrite
 * matching ids anyway — the delete exists to remove chunks a shrunken
 * document no longer has.
 */
@Service
public class DocumentIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionPipeline.class);

    private final TextExtractor extractor;
    private final OcrEngine ocr;
    private final TextChunker chunker;
    private final AiClient ai;
    private final KnowledgeClient knowledge;
    private final ChunkBatchPublisher publisher;
    private final IngestionProperties properties;

    public DocumentIngestionPipeline(TextExtractor extractor, OcrEngine ocr, TextChunker chunker, AiClient ai,
            KnowledgeClient knowledge, ChunkBatchPublisher publisher, IngestionProperties properties) {
        this.extractor = extractor;
        this.ocr = ocr;
        this.chunker = chunker;
        this.ai = ai;
        this.knowledge = knowledge;
        this.publisher = publisher;
        this.properties = properties;
    }

    public IngestOutcome ingest(byte[] pdf, String source, String driveFileId, boolean includeFileNameHeader) {
        String text = extractor.extract(pdf);
        boolean ocrUsed = false;
        if (text.trim().length() < properties.ocrMinChars()) {
            log.info("{}: text layer has {} chars, running OCR", source, text.trim().length());
            String recognised = ocr.recognize(pdf);
            if (!recognised.isBlank()) {
                text = recognised;
                ocrUsed = true;
            }
        }

        List<TextChunk> chunks = List.of();
        if (!text.isBlank()) {
            String full = includeFileNameHeader ? "File Name: " + source + "\n\n" + text : text;
            chunks = chunker.chunk(full, source);
        }

        List<float[]> embeddings = embedAll(chunks);

        try {
            knowledge.deleteDocument(source);
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Could not clear previous chunks for " + source, ex);
        }

        publishBatches(source, driveFileId, chunks, embeddings);
        log.info("Ingested {}: {} chunks{}", source, chunks.size(), ocrUsed ? " (OCR)" : "");
        return new IngestOutcome(source, chunks.size(), ocrUsed);
    }

    private List<float[]> embedAll(List<TextChunk> chunks) {
        List<float[]> embeddings = new ArrayList<>(chunks.size());
        int batchSize = properties.embeddingBatchSize();
        for (int from = 0; from < chunks.size(); from += batchSize) {
            List<String> texts = chunks.subList(from, Math.min(from + batchSize, chunks.size()))
                    .stream().map(TextChunk::text).toList();
            try {
                embeddings.addAll(ai.embedBatch(new EmbedBatchRequest(texts)).embeddings());
            }
            catch (RuntimeException ex) {
                throw new ExternalServiceException("Embedding failed via ai-service", ex);
            }
        }
        return embeddings;
    }

    private void publishBatches(String source, String driveFileId, List<TextChunk> chunks, List<float[]> embeddings) {
        int max = ChunkBatchReady.MAX_CHUNKS_PER_BATCH;
        int batchCount = Math.max(1, (chunks.size() + max - 1) / max);
        for (int seq = 0; seq < batchCount; seq++) {
            int from = seq * max;
            int to = Math.min(from + max, chunks.size());
            List<ChunkPayload> payloads = new ArrayList<>();
            for (int i = from; i < to; i++) {
                TextChunk chunk = chunks.get(i);
                payloads.add(new ChunkPayload(chunk.id(), chunk.text(), chunk.source(), chunk.page(), embeddings.get(i)));
            }
            publisher.publish(new ChunkBatchReady(source, driveFileId, seq, seq == batchCount - 1, payloads));
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl services/ingestion-service test -Dtest=DocumentIngestionPipelineTest`
Expected: PASS — 6 tests

- [ ] **Step 5: Commit**

```bash
git add services/ingestion-service
git commit -m "feat(ingestion): pipeline from PDF bytes to ordered ChunkBatchReady batches"
```

---

### Task 5: Kafka listener, local-directory ingest, REST, config, gateway route

**Files:**
- Create: `.../ingestion/config/KafkaConfig.java`
- Create: `.../ingestion/kafka/DocumentDiscoveredListener.java`
- Create: `.../ingestion/local/IngestJob.java`, `IngestJobRegistry.java`, `LocalDirectoryIngestService.java`
- Create: `.../ingestion/api/dto/IngestRequest.java`, `IngestAccepted.java`
- Create: `.../ingestion/api/IngestController.java`
- Create: `config-repo/ingestion-service.yml`
- Modify: `infrastructure/api-gateway/src/main/resources/application.yml`
- Test: `.../kafka/DocumentDiscoveredListenerIntegrationTests.java`, `.../local/LocalDirectoryIngestServiceTest.java`, `.../api/IngestControllerTest.java`

**Interfaces:**
- Produces: `POST /api/v1/ingest {source?}` → 202 `{jobId, status: "running"}`; `GET /api/v1/ingest/{jobId}` → `IngestJob`; gateway `POST /api/v1/rag/ingest` (the React `PdfUploader` calls this).

- [ ] **Step 1: Write the failing tests**

`LocalDirectoryIngestServiceTest.java`:

```java
package com.bloom.eventmanager.ingestion.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.domain.DocumentIngestionPipeline;
import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDirectoryIngestServiceTest {

    @TempDir
    Path dir;

    @Test
    void ingestsEveryPdfInTheDirectoryWithoutTheDriveHeader() throws IOException {
        Files.write(dir.resolve("a.pdf"), new byte[] { 1 });
        Files.write(dir.resolve("b.pdf"), new byte[] { 2 });
        Files.write(dir.resolve("notes.txt"), new byte[] { 3 });
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        when(pipeline.ingest(any(), any(), isNull(), eq(false)))
                .thenAnswer(inv -> new IngestOutcome(inv.getArgument(1), 4, false));
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(pipeline, registry,
                new IngestionProperties(dir.toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start(null);
        service.run(job.jobId(), null);

        IngestJob done = registry.get(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(IngestJob.Status.COMPLETED);
        assertThat(done.results()).extracting(IngestOutcome::source).containsExactlyInAnyOrder("a.pdf", "b.pdf");
        verify(pipeline).ingest(new byte[] { 1 }, "a.pdf", null, false);
    }

    @Test
    void restrictsToOneSourceWhenAsked() throws IOException {
        Files.write(dir.resolve("a.pdf"), new byte[] { 1 });
        Files.write(dir.resolve("b.pdf"), new byte[] { 2 });
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        when(pipeline.ingest(any(), any(), isNull(), eq(false)))
                .thenAnswer(inv -> new IngestOutcome(inv.getArgument(1), 1, false));
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(pipeline, registry,
                new IngestionProperties(dir.toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start("b.pdf");
        service.run(job.jobId(), "b.pdf");

        assertThat(registry.get(job.jobId()).orElseThrow().results()).extracting(IngestOutcome::source)
                .containsExactly("b.pdf");
    }

    @Test
    void aFailingFileIsRecordedAndTheJobContinues() throws IOException {
        Files.write(dir.resolve("bad.pdf"), new byte[] { 1 });
        Files.write(dir.resolve("good.pdf"), new byte[] { 2 });
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        when(pipeline.ingest(eq(new byte[] { 1 }), eq("bad.pdf"), isNull(), eq(false)))
                .thenThrow(new RuntimeException("boom"));
        when(pipeline.ingest(eq(new byte[] { 2 }), eq("good.pdf"), isNull(), eq(false)))
                .thenReturn(new IngestOutcome("good.pdf", 2, false));
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(pipeline, registry,
                new IngestionProperties(dir.toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start(null);
        service.run(job.jobId(), null);

        IngestJob done = registry.get(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(IngestJob.Status.COMPLETED);
        assertThat(done.errors()).singleElement().asString().contains("bad.pdf").contains("boom");
        assertThat(done.results()).extracting(IngestOutcome::source).containsExactly("good.pdf");
    }

    @Test
    void aMissingDirectoryFailsTheJob() {
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(mock(DocumentIngestionPipeline.class),
                registry, new IngestionProperties(dir.resolve("nope").toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start(null);
        service.run(job.jobId(), null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(registry.get(job.jobId()).orElseThrow().status()).isEqualTo(IngestJob.Status.FAILED));
    }
}
```

`IngestControllerTest.java`:

```java
package com.bloom.eventmanager.ingestion.api;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.ingestion.local.IngestJob;
import com.bloom.eventmanager.ingestion.local.IngestJobRegistry;
import com.bloom.eventmanager.ingestion.local.LocalDirectoryIngestService;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalDirectoryIngestService ingestService;

    @MockitoBean
    private IngestJobRegistry registry;

    @Test
    void startsAJobAndReturns202() throws Exception {
        when(ingestService.start(isNull())).thenReturn(IngestJob.running("job-1"));

        mockMvc.perform(post("/api/v1/ingest").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(ingestService).run("job-1", null);
    }

    @Test
    void reportsAKnownJob() throws Exception {
        when(registry.get("job-1")).thenReturn(Optional.of(IngestJob.running("job-1").completed(List.of(), List.of())));

        mockMvc.perform(get("/api/v1/ingest/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void unknownJobIs404() throws Exception {
        when(registry.get("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/ingest/nope")).andExpect(status().isNotFound());
    }
}
```

`DocumentDiscoveredListenerIntegrationTests.java`:

```java
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
        "spring.cloud.discovery.enabled=false"
})
class DocumentDiscoveredListenerIntegrationTests {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.0");

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
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/ingestion-service test -Dtest='LocalDirectoryIngestServiceTest,IngestControllerTest'`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.ingestion.local does not exist`

- [ ] **Step 3: Implement**

`config/KafkaConfig.java`:

```java
package com.bloom.eventmanager.ingestion.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.bloom.eventmanager.events.BloomTopics;

@Configuration
public class KafkaConfig {

    /** ingestion-service owns this topic: it is the only producer. */
    @Bean
    NewTopic chunkBatchReadyTopic() {
        return TopicBuilder.name(BloomTopics.CHUNK_BATCH_READY).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(2_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    }
}
```

`kafka/DocumentDiscoveredListener.java`:

```java
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
```

`local/IngestJob.java`:

```java
package com.bloom.eventmanager.ingestion.local;

import java.time.Instant;
import java.util.List;

import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

public record IngestJob(
        String jobId,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        List<IngestOutcome> results,
        List<String> errors) {

    public enum Status { RUNNING, COMPLETED, FAILED }

    public static IngestJob running(String jobId) {
        return new IngestJob(jobId, Status.RUNNING, Instant.now(), null, List.of(), List.of());
    }

    public IngestJob completed(List<IngestOutcome> results, List<String> errors) {
        return new IngestJob(jobId, Status.COMPLETED, startedAt, Instant.now(), List.copyOf(results), List.copyOf(errors));
    }

    public IngestJob failed(String error) {
        return new IngestJob(jobId, Status.FAILED, startedAt, Instant.now(), results, List.of(error));
    }
}
```

`local/IngestJobRegistry.java`:

```java
package com.bloom.eventmanager.ingestion.local;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** In-memory, like V1's sync bookkeeping. Restarting the service forgets jobs; that is acceptable. */
@Component
public class IngestJobRegistry {

    private final Map<String, IngestJob> jobs = new ConcurrentHashMap<>();

    public void put(IngestJob job) {
        jobs.put(job.jobId(), job);
    }

    public Optional<IngestJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
```

`local/LocalDirectoryIngestService.java`:

```java
package com.bloom.eventmanager.ingestion.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.domain.DocumentIngestionPipeline;
import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

/**
 * V1's {@code npm run ingest} / {@code POST /ingest}: every PDF in the
 * configured directory, no "File Name:" header (V1's local path never added
 * one), each file independent so one bad PDF cannot sink the batch.
 */
@Service
public class LocalDirectoryIngestService {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryIngestService.class);

    private final DocumentIngestionPipeline pipeline;
    private final IngestJobRegistry registry;
    private final Path directory;

    public LocalDirectoryIngestService(DocumentIngestionPipeline pipeline, IngestJobRegistry registry,
            IngestionProperties properties) {
        this.pipeline = pipeline;
        this.registry = registry;
        this.directory = Path.of(properties.pdfDirectory());
    }

    public IngestJob start(String onlySource) {
        IngestJob job = IngestJob.running(UUID.randomUUID().toString());
        registry.put(job);
        return job;
    }

    @Async
    public void run(String jobId, String onlySource) {
        IngestJob job = registry.get(jobId).orElseThrow();
        List<IngestOutcome> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        List<Path> pdfs;
        try (Stream<Path> entries = Files.list(directory)) {
            pdfs = entries.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> onlySource == null || p.getFileName().toString().equals(onlySource))
                    .sorted()
                    .toList();
        }
        catch (IOException ex) {
            registry.put(job.failed("Cannot read " + directory + ": " + ex.getMessage()));
            return;
        }

        for (Path pdf : pdfs) {
            String source = pdf.getFileName().toString();
            try {
                results.add(pipeline.ingest(Files.readAllBytes(pdf), source, null, false));
            }
            catch (IOException | RuntimeException ex) {
                log.error("Failed to ingest {}", source, ex);
                errors.add(source + ": " + ex.getMessage());
            }
        }
        registry.put(job.completed(results, errors));
        log.info("Local ingest {} complete: {} files, {} errors", jobId, results.size(), errors.size());
    }
}
```

`api/dto/IngestRequest.java`:

```java
package com.bloom.eventmanager.ingestion.api.dto;

/** {@code source} restricts the run to one file name in the directory. */
public record IngestRequest(String source) {
}
```

`api/dto/IngestAccepted.java`:

```java
package com.bloom.eventmanager.ingestion.api.dto;

public record IngestAccepted(String jobId, String status) {
}
```

`api/IngestController.java`:

```java
package com.bloom.eventmanager.ingestion.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.ingestion.api.dto.IngestAccepted;
import com.bloom.eventmanager.ingestion.api.dto.IngestRequest;
import com.bloom.eventmanager.ingestion.local.IngestJob;
import com.bloom.eventmanager.ingestion.local.IngestJobRegistry;
import com.bloom.eventmanager.ingestion.local.LocalDirectoryIngestService;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final LocalDirectoryIngestService ingestService;
    private final IngestJobRegistry registry;

    public IngestController(LocalDirectoryIngestService ingestService, IngestJobRegistry registry) {
        this.ingestService = ingestService;
        this.registry = registry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestAccepted start(@RequestBody(required = false) IngestRequest request) {
        String onlySource = request == null ? null : request.source();
        IngestJob job = ingestService.start(onlySource);
        ingestService.run(job.jobId(), onlySource);
        return new IngestAccepted(job.jobId(), job.status().name());
    }

    @GetMapping("/{jobId}")
    public IngestJob status(@PathVariable String jobId) {
        return registry.get(jobId).orElseThrow(() -> new NotFoundException("No ingest job " + jobId));
    }
}
```

`config-repo/ingestion-service.yml`:

```yaml
server:
  port: 8083

bloom:
  ingestion:
    pdf-directory: ${PDF_DIRECTORY:./ISM_PDF}
    chunk-size: ${CHUNK_SIZE:1000}
    chunk-overlap: ${CHUNK_OVERLAP:200}
    ocr-min-chars: 100
    ocr-dpi: 300
    ocr-language: eng
    # Empty: use the eng.traineddata bundled in the jar. Set to a directory
    # containing *.traineddata to use a system Tesseract install instead.
    ocr-data-path: ${TESSDATA_PATH:}
    embedding-batch-size: 20
```

Gateway route — add after `retrieve` in `infrastructure/api-gateway/src/main/resources/application.yml`:

```yaml
            - id: ingest
              uri: lb://ingestion-service
              predicates:
                - Path=/api/v1/rag/ingest,/api/v1/rag/ingest/**
              filters:
                - RewritePath=/api/v1/rag/ingest(?<rest>.*),/api/v1/ingest$\{rest}
```

The `$\{rest}` spelling is deliberate: an unescaped `${rest}` is consumed as a Spring property placeholder before the gateway ever sees it.

- [ ] **Step 4: Run every ingestion-service test**

Run: `./mvnw -q -pl services/ingestion-service test`
Expected: PASS — 4 + 2 + 2 + 6 + 4 + 3 + 1 = 22 (Kafka IT and the OCR recognition test skip without Docker / native Tesseract).

- [ ] **Step 5: Commit**

```bash
git add services/ingestion-service config-repo/ingestion-service.yml infrastructure/api-gateway/src/main/resources/application.yml
git commit -m "feat(ingestion): Drive listener, local-directory ingest job, REST, gateway route"
```

---

### Task 6: End-to-end — a local PDF lands in Neo4j through Kafka

**Files:** none.

- [ ] **Step 1: Build**

Run: `./mvnw clean install`
Expected: BUILD SUCCESS, eleven reactor projects.

- [ ] **Step 2: Start everything**

Environment as in Phase 1 (`GEMINI_API_KEY`, `NEO4J_*`, `REDIS_URL`). Point `PDF_DIRECTORY` at a folder with one or two small PDFs that are **not** already in V1's corpus, e.g. `export PDF_DIRECTORY=/e/Bloom_Event_Manager/event_proposals`.

```bash
docker compose -f deploy/docker-compose.yml --profile infra up -d
./mvnw -pl infrastructure/discovery-server spring-boot:run
./mvnw -pl infrastructure/config-server spring-boot:run
./mvnw -pl services/knowledge-service spring-boot:run
./mvnw -pl services/ai-service spring-boot:run
./mvnw -pl services/ingestion-service spring-boot:run
./mvnw -pl infrastructure/api-gateway spring-boot:run
```

- [ ] **Step 3: Confirm topics exist**

```bash
docker exec bloom-v2-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Expected: `bloom.chunks.batch-ready` and `bloom.documents.indexed` (created by the services' `NewTopic` beans).

- [ ] **Step 4: Trigger a local ingest through the gateway**

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/ingest -H "Content-Type: application/json" -d '{}'
```

Expected: `{"jobId":"…","status":"RUNNING"}`. Then:

```bash
curl -s http://localhost:8080/api/v1/rag/ingest/<jobId> | jq
```

Expected within a minute: `status: "COMPLETED"`, one result per PDF with `chunkCount > 0`, `errors: []`.

- [ ] **Step 5: Watch the pipeline**

`ingestion-service` log: `Ingested <file>: N chunks`. `knowledge-service` log: `Stored batch 0 …`, …, `Indexed <file> (N chunks)`.

```bash
docker exec bloom-v2-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic bloom.documents.indexed --from-beginning --max-messages 1
```

Expected: a JSON `DocumentIndexed` with `driveFileId: null` and the chunk count.

- [ ] **Step 6: Confirm in Neo4j and via retrieval**

```bash
curl -s http://localhost:8082/api/v1/sources | jq
```

Expected: the new file names are present.

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/retrieve -H "Content-Type: application/json" \
     -d '{"query": "<a phrase from the new PDF>", "topK": 3, "useAI": false}' | jq '.chunks[].source'
```

Expected: the new file among the sources.

- [ ] **Step 7: Prove idempotence and the DLT**

Run Step 4 again. Expected: same chunk count; `knowledge-service` logs a delete followed by fresh batches; no duplicate chunks (`countChunks` equals the first run's count).

Stop `knowledge-service`, publish a garbage message, restart it:

```bash
echo "not json" | docker exec -i bloom-v2-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic bloom.chunks.batch-ready
```

Expected after restart: three retries logged with growing delays, then the record appears on `bloom.chunks.batch-ready.DLT`:

```bash
docker exec bloom-v2-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep DLT
```

- [ ] **Step 8: Record and commit**

Append the ingested file names and chunk counts to the README verification section:

```bash
git add README.md
git commit -m "docs: Phase 3a local ingest verified through Kafka"
```

---

## Phase 3a Exit Criteria

- [ ] `./mvnw clean install` green, eleven reactor projects
- [ ] `POST /api/v1/rag/ingest` through the gateway ingests a directory of PDFs; chunks appear in Neo4j with V1-style ids and no manual step
- [ ] `bloom.documents.indexed` carries a `DocumentIndexed` per file
- [ ] Re-running the ingest does not duplicate chunks
- [ ] A poison message ends on the `.DLT` topic after three retries
- [ ] A scanned PDF (no text layer) produces chunks via OCR on a machine with Tesseract available

## What Phase 3a Does Not Do

- No Google Drive — `StorageClient.download` has no implementation yet; `DocumentDiscovered` events are only produced by Phase 3b
- No `DocumentDeleted` producer — the consumer exists; Phase 3b's sync publishes
- No metadata recording for Drive files — Phase 3b's `DocumentIndexed` consumer
