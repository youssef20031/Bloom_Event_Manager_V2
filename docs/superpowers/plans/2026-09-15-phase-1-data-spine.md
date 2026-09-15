# Phase 1 — Data Spine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the two services everything else depends on — `knowledge-service` owning Neo4j and `ai-service` owning Gemini plus the Redis embedding cache — and prove them against the data V1 already ingested: embed a query with `ai-service`, search with `knowledge-service`, get real chunks back.

**Architecture:** `knowledge-service` wraps the Neo4j Java Driver behind a `ChunkRepository` port using the exact graph shape V1's `Neo4jService` wrote (`Document -[:HAS_CHUNK]-> Chunk`, Drive metadata on `Document`), exposes it over REST, and creates the vector index on startup. `ai-service` wires Spring AI's Gemini core modules by hand (no starter), puts a Redis cache in front of embeddings that is byte-compatible with V1's cache entries, and ports V1's model-fallback chain and thought/answer splitting for generation. Neither service calls the other; that composition is Phase 2.

**Tech Stack:** Spring Boot 4.0.8, Spring Cloud 2025.1.3, Java 25, Neo4j Java Driver 6.1.0, Spring AI 2.0.1 core modules, Spring Data Redis (Lettuce), Testcontainers 2.0.5, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` (sections 5.2, 7, 8, 9.3, 9.4, 13)

**Prerequisite:** Phase 0 complete — `./mvnw clean install` green, `knowledge-service` skeleton registered in Eureka, Compose `infra` profile running.

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec or verified against Maven Central / jar metadata on 2026-09-15.

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.** Same as Phase 0.
- **Boot 4.0 split its test support into separate modules.** Use exactly these, as Phase 0 did:
  - `spring-boot-webmvc-test` → `@WebMvcTest` lives at `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`
  - `spring-boot-testcontainers` → `@ServiceConnection` at `org.springframework.boot.testcontainers.service.connection.ServiceConnection`
  - `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`) replaces the removed `@MockBean`.
- **Testcontainers is `2.0.5`** (managed by Boot). Artifacts were renamed: `org.testcontainers:testcontainers-neo4j` and `org.testcontainers:testcontainers-junit-jupiter`. The 1.x names `neo4j` / `junit-jupiter` do not exist at 2.0.5. Container classes moved to new packages — use `org.testcontainers.neo4j.Neo4jContainer`. Boot 4.0.8's `@ServiceConnection` factory binds **that** class; the old `org.testcontainers.containers.Neo4jContainer` only matches a deprecated factory.
- **Neo4j:** `spring-boot-starter-neo4j` (driver only — not `spring-boot-starter-data-neo4j`; there are no entities). Properties `spring.neo4j.uri`, `spring.neo4j.authentication.username`, `spring.neo4j.authentication.password`.
- **Redis:** `spring-boot-starter-data-redis`. Property `spring.data.redis.url`.
- **Spring AI `2.0.1` core modules only:** `spring-ai-google-genai` and `spring-ai-google-genai-embedding`, versions from `spring-ai-bom` (already imported by `bloom-bom`). **Never** add `spring-ai-starter-model-google-genai` or `spring-ai-autoconfigure-model-google-genai` — they depend on Boot 4.1.1 and break the 4.0.8 baseline. Verified API surface:
  - `com.google.genai.Client.builder().apiKey(String).build()`
  - `GoogleGenAiChatModel.builder().genAiClient(Client).options(GoogleGenAiChatOptions).retryTemplate(RetryTemplate).observationRegistry(ObservationRegistry).build()`
  - `GoogleGenAiChatOptions.builder().model(String).temperature(Double).maxOutputTokens(Integer).includeThoughts(Boolean).build()`
  - `GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(String).build()`; `GoogleGenAiTextEmbeddingOptions.builder().model(String).build()`; `new GoogleGenAiTextEmbeddingModel(connectionDetails, options)`
  - `EmbeddingModel.embed(String) -> float[]`, `EmbeddingModel.embed(List<String>) -> List<float[]>`
  - `ChatModel.call(Prompt) -> ChatResponse`; `ChatResponse.getResults() -> List<Generation>`; `Generation.getOutput() -> AssistantMessage`; `AssistantMessage.getText()`, `.getMetadata().get("isThought")` is a `Boolean` per part.
  - `org.springframework.ai.retry.RetryUtils.DEFAULT_RETRY_TEMPLATE`
- **Neo4j graph shape must match V1's `Neo4jService`** — the code that wrote the existing data: `(:Document {id, name, driveFileId, driveModifiedTime, lastSyncedAt}) -[:HAS_CHUNK]-> (:Chunk {id, text, source, page, embedding})`. V1 also contains a `Neo4jChunkRepository.ts` with a different, Chunk-only shape plus `DriveFile` nodes; that class was not on V1's production write path. Do not port it.
- **Vector index:** name `chunk_embeddings`, 3072 dimensions, cosine — `CONSTANTS` in V1.
- **Embedding cache must be byte-compatible with V1** so existing Redis entries keep working: key `<prefix>embed:<embeddingModel>:<first 32 hex chars of SHA-256(text)>` with prefix `bloom:`; value = Base64 of the embedding as little-endian IEEE-754 float32. No TTL — Redis's `allkeys-lru` evicts.
- **Cache failure is never a request failure.** Redis down means "slower", never an error, and Redis is excluded from readiness.
- **No Kafka, no Feign, no circuit breakers, no security in Phase 1.** Those arrive in Phases 2, 3 and 5.
- **Integration tests are named `*IntegrationTests`** (Surefire's default pattern) and carry `@Testcontainers(disabledWithoutDocker = true)` so `./mvnw test` still passes on a machine without Docker.
- **Commit with explicit paths, never `git add -A`.** Another session may be working in the same tree.

---

## File Structure

```
platform/bloom-common/src/main/java/com/bloom/eventmanager/common/
  ai/JsonExtractor.java                      pure port of V1 GeminiAIService.extractJson
  error/GlobalExceptionHandler.java          MODIFY: map validation + unreadable-body to 400
platform/bloom-common/src/test/java/com/bloom/eventmanager/common/
  ai/JsonExtractorTest.java
  error/GlobalExceptionHandlerTest.java      MODIFY: add validation-mapping tests

services/knowledge-service/
  pom.xml                                    MODIFY: neo4j starter, validation, testcontainers
  src/main/java/com/bloom/eventmanager/knowledge/
    KnowledgeServiceApplication.java         MODIFY: @ConfigurationPropertiesScan
    config/KnowledgeProperties.java          index name / dimensions / similarity
    domain/ChunkRecord.java                  write-side record (has embedding)
    domain/ChunkMatch.java                   read-side record (has score, no embedding)
    domain/DriveFileMetadata.java
    domain/ChunkRepository.java              the port
    neo4j/Neo4jChunkRepository.java          the only class that speaks Cypher
    neo4j/VectorIndexInitializer.java        ApplicationRunner replacing `npm run init-db`
    api/ChunkController.java                 /api/v1/chunks/**
    api/SourceController.java                /api/v1/sources/**
    api/DocumentController.java              /api/v1/documents
    api/DriveFileController.java             /api/v1/drive-files/**
    api/dto/ChunkSearchRequest.java
    api/dto/ChunksBySourcesRequest.java
    api/dto/DriveFileUpsertRequest.java
    api/dto/DeleteDocumentResponse.java
  src/test/java/com/bloom/eventmanager/knowledge/
    neo4j/Neo4jChunkRepositoryIntegrationTests.java
    neo4j/VectorIndexInitializerTest.java
    api/ChunkControllerTest.java
    api/DriveFileControllerTest.java
config-repo/knowledge-service.yml            MODIFY: Neo4j connection + index settings

services/ai-service/
  pom.xml
  src/main/java/com/bloom/eventmanager/ai/
    AiServiceApplication.java
    config/AiProperties.java                 api key, models, batch size, cache prefix
    domain/EmbeddingPort.java
    domain/GenerationPort.java
    domain/GenerationOptions.java
    domain/GenerationResult.java
    gemini/GeminiClientConfig.java           the manual Spring AI wiring
    gemini/GeminiEmbeddingService.java       EmbeddingPort over Spring AI EmbeddingModel
    gemini/GeminiGenerationService.java      GenerationPort with fallback chain + thought split
    gemini/GeminiErrors.java                 429/404 classification
    cache/EmbeddingCodec.java                float[] <-> V1-compatible base64, SHA-256 key hash
    cache/EmbeddingCache.java                Redis read/write that never throws
    cache/CachingEmbeddingService.java       @Primary EmbeddingPort decorator
    api/AiController.java                    /api/v1/ai/**
    api/dto/EmbedRequest.java, EmbedResponse.java, EmbedBatchRequest.java,
            EmbedBatchResponse.java, GenerateRequest.java, GenerateResponse.java
  src/main/resources/application.yml
  src/test/java/com/bloom/eventmanager/ai/
    cache/EmbeddingCodecTest.java
    cache/EmbeddingCacheIntegrationTests.java
    cache/CachingEmbeddingServiceTest.java
    gemini/GeminiErrorsTest.java
    gemini/GeminiGenerationServiceTest.java
    api/AiControllerTest.java
config-repo/ai-service.yml
pom.xml                                      MODIFY: add services/ai-service module
```

**Two deliberate deviations from the spec's endpoint list, both recorded here so nobody "fixes" them back:**

1. `DELETE /api/v1/documents/{source}` becomes `DELETE /api/v1/documents?source=...`. Sources are file names and, for Drive files in subfolders, contain slashes (`Clients/Acme/brief.pdf`). A path variable cannot carry those.
2. `GET /api/v1/drive-files/{id}` is added. Phase 3's deletion handling needs V1's `getFileMetadata(driveFileId)` to learn a deleted file's source name before removing its chunks.

---

### Task 1: bloom-common — JsonExtractor and 400-mapping for validation errors

**Files:**
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/ai/JsonExtractor.java`
- Modify: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/GlobalExceptionHandler.java`
- Test: `platform/bloom-common/src/test/java/com/bloom/eventmanager/common/ai/JsonExtractorTest.java`
- Test: `platform/bloom-common/src/test/java/com/bloom/eventmanager/common/error/GlobalExceptionHandlerTest.java` (add two tests)

**Interfaces:**
- Consumes: Phase 0's `ErrorCode`, `GlobalExceptionHandler`
- Produces:
  - `JsonExtractor.extract(String text) -> String` — static; pulls JSON out of a fenced code block or the outermost `{...}`/`[...]`, strips trailing commas. Used by `retrieval-service` (Phase 2), `chat-service` and `proposal-service` (Phase 4).
  - `GlobalExceptionHandler` now maps `MethodArgumentNotValidException` and `HttpMessageNotReadableException` to `400` with `errorCode: VALIDATION_FAILED`. Without this, a `@Valid` failure in any service falls into the catch-all and reports as a 500.

- [ ] **Step 1: Write the failing tests**

`platform/bloom-common/src/test/java/com/bloom/eventmanager/common/ai/JsonExtractorTest.java`:

```java
package com.bloom.eventmanager.common.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExtractorTest {

    @Test
    void extractsFromAFencedJsonBlock() {
        String text = "Here you go:\n```json\n{\"specific\": \"a\", \"broad\": \"b\"}\n```\nHope that helps.";

        assertThat(JsonExtractor.extract(text)).isEqualTo("{\"specific\": \"a\", \"broad\": \"b\"}");
    }

    @Test
    void extractsFromAFenceWithoutLanguageTag() {
        String text = "```\n[1, 2, 3]\n```";

        assertThat(JsonExtractor.extract(text)).isEqualTo("[1, 2, 3]");
    }

    @Test
    void extractsTheOutermostObjectWhenThereIsNoFence() {
        String text = "Sure! {\"ranking\": [3, 1, 2]} — let me know if you need more.";

        assertThat(JsonExtractor.extract(text)).isEqualTo("{\"ranking\": [3, 1, 2]}");
    }

    @Test
    void prefersWhicheverBracketOpensFirst() {
        String text = "[{\"a\": 1}] trailing {";

        assertThat(JsonExtractor.extract(text)).isEqualTo("[{\"a\": 1}]");
    }

    @Test
    void stripsTrailingCommasThatModelsLoveToEmit() {
        String text = "{\"items\": [1, 2, 3,], \"x\": 1,}";

        assertThat(JsonExtractor.extract(text)).isEqualTo("{\"items\": [1, 2, 3], \"x\": 1}");
    }

    @Test
    void returnsTrimmedInputWhenNothingLooksLikeJson() {
        assertThat(JsonExtractor.extract("  plain text  ")).isEqualTo("plain text");
    }

    @Test
    void toleratesNull() {
        assertThat(JsonExtractor.extract(null)).isEmpty();
    }
}
```

Append to `GlobalExceptionHandlerTest.java` (keep the existing three tests):

```java
    @Test
    void mapsBeanValidationFailuresTo400WithFieldDetail() throws Exception {
        java.lang.reflect.Method method = Probe.class.getDeclaredMethod("handle", String.class);
        org.springframework.core.MethodParameter parameter = new org.springframework.core.MethodParameter(method, 0);
        org.springframework.validation.BeanPropertyBindingResult binding =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new org.springframework.validation.FieldError("request", "text", "must not be blank"));

        ProblemDetail problem = handler.handleValidation(
                new org.springframework.web.bind.MethodArgumentNotValidException(parameter, binding));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("text must not be blank");
        assertThat(problem.getProperties()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void mapsUnreadableRequestBodiesTo400WithoutEchoingParserInternals() {
        ProblemDetail problem = handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error: Unexpected character at line 3", (org.springframework.http.HttpInputMessage) null));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Malformed request body");
        assertThat(problem.getProperties()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @SuppressWarnings("unused")
    private static final class Probe {
        void handle(String text) {
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: COMPILATION FAILURE — `cannot find symbol: class JsonExtractor` and `method handleValidation`

- [ ] **Step 3: Write the implementation**

`JsonExtractor.java`:

```java
package com.bloom.eventmanager.common.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a JSON payload out of a model response that may be wrapped in prose or
 * a fenced code block, and removes the trailing commas models routinely emit.
 * A direct port of V1's {@code GeminiAIService.extractJson}.
 */
public final class JsonExtractor {

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");

    private JsonExtractor() {
    }

    public static String extract(String text) {
        if (text == null) {
            return "";
        }

        String extracted;
        Matcher fenced = CODE_BLOCK.matcher(text);
        if (fenced.find()) {
            extracted = fenced.group(1).trim();
        }
        else {
            int firstBrace = text.indexOf('{');
            int firstBracket = text.indexOf('[');
            int lastBrace = text.lastIndexOf('}');
            int lastBracket = text.lastIndexOf(']');

            int start = -1;
            if (firstBrace != -1 && (firstBracket == -1 || firstBrace < firstBracket)) {
                start = firstBrace;
            }
            else if (firstBracket != -1) {
                start = firstBracket;
            }

            int end = -1;
            if (lastBrace != -1 && (lastBracket == -1 || lastBrace > lastBracket)) {
                end = lastBrace;
            }
            else if (lastBracket != -1) {
                end = lastBracket;
            }

            extracted = (start != -1 && end != -1 && end > start)
                    ? text.substring(start, end + 1).trim()
                    : text.trim();
        }

        return TRAILING_COMMA.matcher(extracted).replaceAll("$1");
    }
}
```

In `GlobalExceptionHandler.java`, add two handlers between `handleBloom` and `handleUnexpected`:

```java
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(java.util.stream.Collectors.joining("; "));
        return toProblem(ErrorCode.VALIDATION_FAILED, detail.isBlank() ? "Request validation failed" : detail);
    }

    /** Parser messages echo fragments of the body; keep them out of the response. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return toProblem(ErrorCode.VALIDATION_FAILED, "Malformed request body");
    }
```

Move the fully-qualified names into imports at the top of the file:

```java
import java.util.stream.Collectors;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: PASS — 7 tests in `JsonExtractorTest`, 5 in `GlobalExceptionHandlerTest`

- [ ] **Step 5: Commit**

```bash
git add platform/bloom-common
git commit -m "feat(common): JsonExtractor and 400 mapping for validation failures"
```

---

### Task 2: knowledge-service — properties, domain, and the Neo4j repository

**Files:**
- Modify: `services/knowledge-service/pom.xml`
- Modify: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/KnowledgeServiceApplication.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/config/KnowledgeProperties.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/domain/ChunkRecord.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/domain/ChunkMatch.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/domain/DriveFileMetadata.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/domain/ChunkRepository.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/neo4j/Neo4jChunkRepository.java`
- Test: `services/knowledge-service/src/test/java/com/bloom/eventmanager/knowledge/neo4j/Neo4jChunkRepositoryIntegrationTests.java`

**Interfaces:**
- Consumes: Boot's autoconfigured `org.neo4j.driver.Driver` bean
- Produces:
  - `KnowledgeProperties(String vectorIndexName, int embeddingDimensions, String similarityFunction)` — `@ConfigurationProperties("bloom.knowledge")`, defaults `chunk_embeddings` / `3072` / `cosine`
  - `ChunkRecord(String id, String text, String source, Integer page, float[] embedding)`
  - `ChunkMatch(String id, String text, String source, int page, double score)`
  - `DriveFileMetadata(String source, String modifiedTime)`
  - `ChunkRepository` with: `List<ChunkMatch> searchSimilar(float[] queryEmbedding, int topK)`, `void storeBatch(List<ChunkRecord>)`, `boolean vectorIndexExists(String)`, `void createVectorIndex(String name, int dimensions, String similarityFunction)`, `List<String> allSources()`, `List<ChunkMatch> randomChunksFromSources(List<String> sources, int limit)`, `void upsertDriveFileMetadata(String source, String driveFileId, String modifiedTime)`, `Optional<DriveFileMetadata> driveFileMetadata(String driveFileId)`, `int deleteDocument(String source)`, `Map<String, String> allDriveFileMetadata()`

The integration test is written **before** the repository, and it is the only place the Cypher gets exercised — read it as the specification of the graph shape.

- [ ] **Step 1: Write the failing integration test**

`Neo4jChunkRepositoryIntegrationTests.java`:

```java
package com.bloom.eventmanager.knowledge.neo4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        // Tiny vectors keep the fixtures readable; the index is created with this size.
        "bloom.knowledge.embedding-dimensions=4"
})
class Neo4jChunkRepositoryIntegrationTests {

    // Same image tag as deploy/docker-compose.yml, so behaviour matches what runs locally.
    // If Testcontainers rejects the calendar-versioned tag, use "neo4j:5" — every Cypher
    // statement here is valid on 5.15+.
    @Container
    @ServiceConnection
    static Neo4jContainer neo4j = new Neo4jContainer("neo4j:2026.06.0").withoutAuthentication();

    @Autowired
    private Neo4jChunkRepository repository;

    @Autowired
    private Driver driver;

    @BeforeEach
    void wipeGraph() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    @Test
    void startupCreatedTheVectorIndexWithConfiguredDimensions() {
        assertThat(repository.vectorIndexExists("chunk_embeddings")).isTrue();
        assertThat(repository.vectorIndexExists("no_such_index")).isFalse();
    }

    @Test
    void storesChunksUnderTheirDocumentAndFindsThemByVectorSimilarity() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "team building games", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "catering menus", "guide.pdf", 2, new float[] { 0f, 1f, 0f, 0f }),
                new ChunkRecord("c-3", "venue checklist", "venues.pdf", null, new float[] { 0f, 0f, 1f, 0f })));

        List<ChunkMatch> matches = repository.searchSimilar(new float[] { 0.9f, 0.1f, 0f, 0f }, 2);

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).id()).isEqualTo("c-1");
        assertThat(matches.get(0).source()).isEqualTo("guide.pdf");
        assertThat(matches.get(0).page()).isEqualTo(1);
        assertThat(matches.get(0).score()).isGreaterThan(matches.get(1).score());
        // V1 stored `page ?? 0`; a missing page reads back as 0, never as null.
        assertThat(repository.randomChunksFromSources(List.of("venues.pdf"), 10))
                .singleElement()
                .satisfies(match -> assertThat(match.page()).isZero());
    }

    @Test
    void storeBatchIsIdempotentOnChunkId() {
        ChunkRecord first = new ChunkRecord("c-1", "v1", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f });
        ChunkRecord rewritten = new ChunkRecord("c-1", "v2", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f });

        repository.storeBatch(List.of(first));
        repository.storeBatch(List.of(rewritten));

        List<ChunkMatch> all = repository.randomChunksFromSources(List.of("guide.pdf"), 10);
        assertThat(all).singleElement().satisfies(match -> assertThat(match.text()).isEqualTo("v2"));
    }

    @Test
    void listsSourcesFromDocumentNodesInOrder() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "zeta.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "y", "alpha.pdf", 1, new float[] { 0f, 1f, 0f, 0f })));

        assertThat(repository.allSources()).containsExactly("alpha.pdf", "zeta.pdf");
    }

    @Test
    void deletingADocumentRemovesItsChunksAndReportsTheCount() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "y", "guide.pdf", 2, new float[] { 0f, 1f, 0f, 0f }),
                new ChunkRecord("c-3", "z", "other.pdf", 1, new float[] { 0f, 0f, 1f, 0f })));

        int deleted = repository.deleteDocument("guide.pdf");

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.allSources()).containsExactly("other.pdf");
        assertThat(repository.deleteDocument("guide.pdf")).isZero();
    }

    @Test
    void tracksDriveFileMetadataOnTheDocumentNode() {
        repository.upsertDriveFileMetadata("brief.pdf", "drive-123", "2026-09-01T10:00:00.000Z");
        repository.upsertDriveFileMetadata("brief.pdf", "drive-123", "2026-09-02T10:00:00.000Z");

        Optional<DriveFileMetadata> metadata = repository.driveFileMetadata("drive-123");
        assertThat(metadata).contains(new DriveFileMetadata("brief.pdf", "2026-09-02T10:00:00.000Z"));
        assertThat(repository.driveFileMetadata("nope")).isEmpty();

        Map<String, String> all = repository.allDriveFileMetadata();
        assertThat(all).containsExactly(Map.entry("drive-123", "2026-09-02T10:00:00.000Z"));
    }

    @Test
    void driveMetadataAndChunksShareOneDocumentNode() {
        repository.upsertDriveFileMetadata("brief.pdf", "drive-123", "2026-09-01T10:00:00.000Z");
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "brief.pdf", 1, new float[] { 1f, 0f, 0f, 0f })));

        try (var session = driver.session()) {
            long documents = session.run("MATCH (d:Document {id: 'brief.pdf'}) RETURN count(d) AS n")
                    .single().get("n").asLong();
            assertThat(documents).isEqualTo(1);
        }

        repository.deleteDocument("brief.pdf");
        assertThat(repository.driveFileMetadata("drive-123")).isEmpty();
    }
}
```

- [ ] **Step 2: Update the module POM**

Replace the `<dependencies>` block of `services/knowledge-service/pom.xml` with:

```xml
    <dependencies>
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-common</artifactId>
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
        <!-- Driver only. There are no @Node entities, so no Spring Data Neo4j. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-neo4j</artifactId>
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
        <!-- Testcontainers 2.x coordinates. The 1.x names (neo4j, junit-jupiter) do not exist. -->
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-neo4j</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

The rest of the POM (parent, `bloom-bom` import, `spring-boot-maven-plugin`) stays exactly as Phase 0 left it.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl services/knowledge-service test -Dtest=Neo4jChunkRepositoryIntegrationTests`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.knowledge.domain does not exist`

- [ ] **Step 4: Write the properties and domain types**

`KnowledgeServiceApplication.java` — add `@ConfigurationPropertiesScan`:

```java
package com.bloom.eventmanager.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Scans com.bloom.eventmanager so the shared GlobalExceptionHandler in
 * bloom-common is picked up alongside this service's own components.
 */
@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
public class KnowledgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServiceApplication.class, args);
    }
}
```

`config/KnowledgeProperties.java`:

```java
package com.bloom.eventmanager.knowledge.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Vector index settings. Cypher cannot parameterise an index name, so it is
 * interpolated into CREATE VECTOR INDEX — the pattern restricts it to
 * identifier characters, which rules out Cypher injection through config.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.knowledge")
public record KnowledgeProperties(
        @DefaultValue("chunk_embeddings") @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]*") String vectorIndexName,
        @DefaultValue("3072") @Min(1) int embeddingDimensions,
        @DefaultValue("cosine") @Pattern(regexp = "cosine|euclidean") String similarityFunction) {
}
```

`domain/ChunkRecord.java`:

```java
package com.bloom.eventmanager.knowledge.domain;

/** A chunk on its way into the graph. {@code page} may be null; it is stored as 0. */
public record ChunkRecord(String id, String text, String source, Integer page, float[] embedding) {
}
```

`domain/ChunkMatch.java`:

```java
package com.bloom.eventmanager.knowledge.domain;

/** A chunk coming back from a query. Never carries the embedding — 12 KB nobody asked for. */
public record ChunkMatch(String id, String text, String source, int page, double score) {
}
```

`domain/DriveFileMetadata.java`:

```java
package com.bloom.eventmanager.knowledge.domain;

public record DriveFileMetadata(String source, String modifiedTime) {
}
```

`domain/ChunkRepository.java`:

```java
package com.bloom.eventmanager.knowledge.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChunkRepository {

    List<ChunkMatch> searchSimilar(float[] queryEmbedding, int topK);

    void storeBatch(List<ChunkRecord> chunks);

    boolean vectorIndexExists(String indexName);

    void createVectorIndex(String indexName, int dimensions, String similarityFunction);

    List<String> allSources();

    List<ChunkMatch> randomChunksFromSources(List<String> sources, int limit);

    void upsertDriveFileMetadata(String source, String driveFileId, String modifiedTime);

    Optional<DriveFileMetadata> driveFileMetadata(String driveFileId);

    /** @return number of chunks removed; 0 when the document did not exist */
    int deleteDocument(String source);

    /** @return driveFileId -> modifiedTime for every Document that came from Drive */
    Map<String, String> allDriveFileMetadata();
}
```

- [ ] **Step 5: Write the Neo4j repository**

`neo4j/Neo4jChunkRepository.java`:

```java
package com.bloom.eventmanager.knowledge.neo4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Repository;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

/**
 * Neo4j access using the graph shape V1's {@code Neo4jService} wrote:
 *
 * <pre>
 * (:Document {id, name, driveFileId?, driveModifiedTime?, lastSyncedAt?})
 *     -[:HAS_CHUNK]-> (:Chunk {id, text, source, page, embedding})
 * </pre>
 *
 * Keeping this shape is what lets Phase 1 validate against data V1 already
 * ingested. Do not "tidy" it into separate DriveFile nodes.
 */
@Repository
public class Neo4jChunkRepository implements ChunkRepository {

    private static final Pattern SAFE_INDEX_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Driver driver;
    private final KnowledgeProperties properties;

    public Neo4jChunkRepository(Driver driver, KnowledgeProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    @Override
    public List<ChunkMatch> searchSimilar(float[] queryEmbedding, int topK) {
        String cypher = """
                CALL db.index.vector.queryNodes($indexName, $topK, $queryEmbedding)
                YIELD node, score
                RETURN node.id AS id, node.text AS text, node.source AS source, node.page AS page, score
                ORDER BY score DESC
                """;
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of(
                    "indexName", properties.vectorIndexName(),
                    "topK", topK,
                    "queryEmbedding", toDoubleList(queryEmbedding)))
                    .list(Neo4jChunkRepository::toMatch);
        }
    }

    @Override
    public void storeBatch(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        List<String> sources = chunks.stream().map(ChunkRecord::source).distinct().toList();
        List<Map<String, Object>> rows = new ArrayList<>(chunks.size());
        for (ChunkRecord chunk : chunks) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", chunk.id());
            row.put("text", chunk.text());
            row.put("source", chunk.source());
            row.put("page", chunk.page() == null ? 0 : chunk.page());
            row.put("embedding", toDoubleList(chunk.embedding()));
            rows.add(row);
        }

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        UNWIND $sources AS source
                        MERGE (doc:Document {id: source})
                        ON CREATE SET doc.name = source
                        """, Map.of("sources", sources)).consume();
                tx.run("""
                        UNWIND $chunks AS chunk
                        MATCH (doc:Document {id: chunk.source})
                        MERGE (c:Chunk {id: chunk.id})
                        SET c.text = chunk.text,
                            c.embedding = chunk.embedding,
                            c.source = chunk.source,
                            c.page = chunk.page
                        MERGE (doc)-[:HAS_CHUNK]->(c)
                        """, Map.of("chunks", rows)).consume();
                return null;
            });
        }
    }

    @Override
    public boolean vectorIndexExists(String indexName) {
        String cypher = """
                SHOW INDEXES YIELD name, type
                WHERE name = $indexName AND type = 'VECTOR'
                RETURN name
                """;
        try (Session session = driver.session()) {
            return !session.run(cypher, Map.of("indexName", indexName)).list().isEmpty();
        }
    }

    @Override
    public void createVectorIndex(String indexName, int dimensions, String similarityFunction) {
        if (!SAFE_INDEX_NAME.matcher(indexName).matches()) {
            throw new IllegalArgumentException("Unsafe vector index name: " + indexName);
        }
        String cypher = """
                CREATE VECTOR INDEX %s IF NOT EXISTS
                FOR (c:Chunk) ON c.embedding
                OPTIONS { indexConfig: {
                    `vector.dimensions`: $dimensions,
                    `vector.similarity_function`: $similarityFunction
                } }
                """.formatted(indexName);
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("dimensions", dimensions, "similarityFunction", similarityFunction)).consume();
        }
    }

    @Override
    public List<String> allSources() {
        try (Session session = driver.session()) {
            return session.run("MATCH (doc:Document) RETURN doc.name AS source ORDER BY source")
                    .list(record -> record.get("source").asString());
        }
    }

    @Override
    public List<ChunkMatch> randomChunksFromSources(List<String> sources, int limit) {
        String cypher = """
                MATCH (c:Chunk)
                WHERE c.source IN $sources
                WITH c, rand() AS r
                ORDER BY r
                LIMIT $limit
                RETURN c.id AS id, c.text AS text, c.source AS source, c.page AS page, 1.0 AS score
                """;
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of("sources", sources, "limit", limit))
                    .list(Neo4jChunkRepository::toMatch);
        }
    }

    @Override
    public void upsertDriveFileMetadata(String source, String driveFileId, String modifiedTime) {
        String cypher = """
                MERGE (doc:Document {id: $source})
                ON CREATE SET doc.name = $source
                SET doc.driveFileId = $driveFileId,
                    doc.driveModifiedTime = $modifiedTime,
                    doc.lastSyncedAt = datetime()
                """;
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("source", source, "driveFileId", driveFileId, "modifiedTime", modifiedTime))
                    .consume();
        }
    }

    @Override
    public Optional<DriveFileMetadata> driveFileMetadata(String driveFileId) {
        String cypher = """
                MATCH (doc:Document {driveFileId: $driveFileId})
                RETURN doc.name AS source, doc.driveModifiedTime AS modifiedTime
                LIMIT 1
                """;
        try (Session session = driver.session()) {
            List<Record> records = session.run(cypher, Map.of("driveFileId", driveFileId)).list();
            if (records.isEmpty()) {
                return Optional.empty();
            }
            Record record = records.get(0);
            return Optional.of(new DriveFileMetadata(
                    record.get("source").asString(),
                    record.get("modifiedTime").asString()));
        }
    }

    @Override
    public int deleteDocument(String source) {
        String cypher = """
                MATCH (doc:Document {id: $source})
                OPTIONAL MATCH (doc)-[:HAS_CHUNK]->(chunk:Chunk)
                DETACH DELETE doc, chunk
                RETURN count(chunk) AS deletedCount
                """;
        try (Session session = driver.session()) {
            List<Record> records = session.run(cypher, Map.of("source", source)).list();
            return records.isEmpty() ? 0 : records.get(0).get("deletedCount").asInt();
        }
    }

    @Override
    public Map<String, String> allDriveFileMetadata() {
        String cypher = """
                MATCH (doc:Document)
                WHERE doc.driveFileId IS NOT NULL
                RETURN doc.driveFileId AS fileId, doc.driveModifiedTime AS modifiedTime
                """;
        try (Session session = driver.session()) {
            Map<String, String> result = new LinkedHashMap<>();
            session.run(cypher).forEachRemaining(record ->
                    result.put(record.get("fileId").asString(), record.get("modifiedTime").asString("")));
            return result;
        }
    }

    private static ChunkMatch toMatch(Record record) {
        Value page = record.get("page");
        return new ChunkMatch(
                record.get("id").asString(),
                record.get("text").asString(""),
                record.get("source").asString(""),
                // V1 wrote `page` as a JS number, which the JS driver sends as a FLOAT.
                // asNumber() reads both INTEGER and FLOAT; asInt() would reject FLOAT.
                page.isNull() ? 0 : page.asNumber().intValue(),
                record.get("score").asDouble());
    }

    /** The driver has no float[] mapping; Cypher LIST<FLOAT> is 64-bit anyway. */
    private static List<Double> toDoubleList(float[] embedding) {
        List<Double> list = new ArrayList<>(embedding.length);
        for (float f : embedding) {
            list.add((double) f);
        }
        return list;
    }
}
```

Note the `forEachRemaining` on `Result` — `Result` implements `Iterator<Record>`, and this avoids materialising the list twice.

- [ ] **Step 6: Run the integration test — it will fail on the index**

Run: `./mvnw -q -pl services/knowledge-service test -Dtest=Neo4jChunkRepositoryIntegrationTests`
Expected: `startupCreatedTheVectorIndexWithConfiguredDimensions` FAILS (index not created — nothing creates it yet); `storesChunksUnderTheirDocument...` FAILS with a Neo4j error that there is no index named `chunk_embeddings`. The four tests that do not touch the index PASS.

If instead the container fails to start with a message about the image tag, change the image to `"neo4j:5"` and re-run; note the change in the commit message.

- [ ] **Step 7: Commit the repository (index initializer is the next task)**

```bash
git add services/knowledge-service
git commit -m "feat(knowledge): Neo4j chunk repository on the V1 graph shape"
```

---

### Task 3: knowledge-service — vector index initializer

**Files:**
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/neo4j/VectorIndexInitializer.java`
- Test: `services/knowledge-service/src/test/java/com/bloom/eventmanager/knowledge/neo4j/VectorIndexInitializerTest.java`

**Interfaces:**
- Consumes: `ChunkRepository`, `KnowledgeProperties`
- Produces: on startup, the vector index exists. Replaces V1's `npm run init-db`.

- [ ] **Step 1: Write the failing unit test**

```java
package com.bloom.eventmanager.knowledge.neo4j;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorIndexInitializerTest {

    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final KnowledgeProperties properties = new KnowledgeProperties("chunk_embeddings", 3072, "cosine");
    private final VectorIndexInitializer initializer = new VectorIndexInitializer(repository, properties);

    @Test
    void createsTheIndexWhenItIsMissing() {
        when(repository.vectorIndexExists("chunk_embeddings")).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        verify(repository).createVectorIndex("chunk_embeddings", 3072, "cosine");
    }

    @Test
    void leavesAnExistingIndexAlone() {
        when(repository.vectorIndexExists("chunk_embeddings")).thenReturn(true);

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).createVectorIndex(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl services/knowledge-service test -Dtest=VectorIndexInitializerTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class VectorIndexInitializer`

- [ ] **Step 3: Write the implementation**

```java
package com.bloom.eventmanager.knowledge.neo4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

/**
 * Creates the vector index on startup if it does not exist. This is the
 * successor to V1's {@code npm run init-db}; there is no separate step to
 * forget any more.
 *
 * <p>An existing index is never altered. Changing dimensions means changing
 * the embedding model, which is a data migration, not a restart.
 */
@Component
public class VectorIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexInitializer.class);

    private final ChunkRepository repository;
    private final KnowledgeProperties properties;

    public VectorIndexInitializer(ChunkRepository repository, KnowledgeProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String name = properties.vectorIndexName();
        if (repository.vectorIndexExists(name)) {
            log.info("Vector index '{}' already exists", name);
            return;
        }
        log.info("Creating vector index '{}' ({} dimensions, {})", name,
                properties.embeddingDimensions(), properties.similarityFunction());
        repository.createVectorIndex(name, properties.embeddingDimensions(), properties.similarityFunction());
    }
}
```

- [ ] **Step 4: Run both test classes — everything green now**

Run: `./mvnw -q -pl services/knowledge-service test -Dtest='VectorIndexInitializerTest,Neo4jChunkRepositoryIntegrationTests'`
Expected: PASS — 2 unit tests, 7 integration tests. The index-creation test passes because the runner executed during `@SpringBootTest` context startup.

- [ ] **Step 5: Commit**

```bash
git add services/knowledge-service
git commit -m "feat(knowledge): create the vector index on startup"
```

---

### Task 4: knowledge-service — REST API

**Files:**
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/dto/ChunkSearchRequest.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/dto/ChunksBySourcesRequest.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/dto/DriveFileUpsertRequest.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/dto/DeleteDocumentResponse.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/ChunkController.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/SourceController.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/DocumentController.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/DriveFileController.java`
- Test: `services/knowledge-service/src/test/java/com/bloom/eventmanager/knowledge/api/ChunkControllerTest.java`
- Test: `services/knowledge-service/src/test/java/com/bloom/eventmanager/knowledge/api/DriveFileControllerTest.java`

**Interfaces:**
- Consumes: `ChunkRepository`, `KnowledgeProperties`, `bloom-common` error types
- Produces the HTTP surface that `retrieval-service` (Phase 2) and `ingestion-service` / `storage-service` (Phase 3) call:

```
POST   /api/v1/chunks/search        {embedding: float[], topK?: int=15}         -> ChunkMatch[]
POST   /api/v1/chunks/batch         ChunkRecord[]                              -> 202
GET    /api/v1/sources                                                          -> string[]
POST   /api/v1/sources/chunks       {sources: string[], limit?: int=50}         -> ChunkMatch[]
DELETE /api/v1/documents?source=…                                               -> {deletedCount}
GET    /api/v1/drive-files                                                      -> {driveFileId: modifiedTime}
GET    /api/v1/drive-files/{id}                                                 -> {source, modifiedTime} | 404
PUT    /api/v1/drive-files/{id}     {source, modifiedTime}                      -> 204
```

Embedding dimension mismatches are rejected with 400 before Neo4j sees them — a wrong-sized vector otherwise produces an opaque Neo4j error deep inside the index procedure.

- [ ] **Step 1: Write the failing controller tests**

`ChunkControllerTest.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChunkController.class)
@EnableConfigurationProperties(KnowledgeProperties.class)
class ChunkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChunkRepository repository;

    @Test
    void searchReturnsMatchesFromTheRepository() throws Exception {
        when(repository.searchSimilar(any(), eq(2)))
                .thenReturn(List.of(new ChunkMatch("c-1", "text", "guide.pdf", 3, 0.91)));

        String body = "{\"embedding\": " + vector(3072) + ", \"topK\": 2}";

        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("c-1"))
                .andExpect(jsonPath("$[0].source").value("guide.pdf"))
                .andExpect(jsonPath("$[0].page").value(3))
                .andExpect(jsonPath("$[0].score").value(0.91));
    }

    @Test
    void searchDefaultsTopKToFifteen() throws Exception {
        when(repository.searchSimilar(any(), eq(15))).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\": " + vector(3072) + "}"))
                .andExpect(status().isOk());

        verify(repository).searchSimilar(any(), eq(15));
    }

    @Test
    void searchRejectsWrongDimensionsBeforeTouchingNeo4j() throws Exception {
        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"embedding\": " + vector(4) + ", \"topK\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("embedding must have 3072 dimensions, got 4"));

        verify(repository, never()).searchSimilar(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void searchRejectsMissingEmbedding() throws Exception {
        mockMvc.perform(post("/api/v1/chunks/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void batchStoreAcceptsWellFormedChunks() throws Exception {
        String body = "[{\"id\": \"c-1\", \"text\": \"hello\", \"source\": \"guide.pdf\", \"page\": 1, \"embedding\": "
                + vector(3072) + "}]";

        mockMvc.perform(post("/api/v1/chunks/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        verify(repository).storeBatch(org.mockito.ArgumentMatchers.argThat(chunks ->
                chunks.size() == 1 && chunks.get(0).id().equals("c-1")));
    }

    @Test
    void batchStoreRejectsAChunkWithWrongDimensions() throws Exception {
        String body = "[{\"id\": \"c-1\", \"text\": \"hello\", \"source\": \"guide.pdf\", \"embedding\": " + vector(2) + "}]";

        mockMvc.perform(post("/api/v1/chunks/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("chunk c-1: embedding must have 3072 dimensions, got 2"));

        verify(repository, never()).storeBatch(any());
    }

    @Test
    void batchStoreRejectsAnEmptyList() throws Exception {
        mockMvc.perform(post("/api/v1/chunks/batch").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }

    private static String vector(int dimensions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0.1");
        }
        return sb.append(']').toString();
    }
}
```

`DriveFileControllerTest.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ DriveFileController.class, DocumentController.class, SourceController.class })
@EnableConfigurationProperties(KnowledgeProperties.class)
class DriveFileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChunkRepository repository;

    @Test
    void listsAllDriveFileMetadataAsAMap() throws Exception {
        when(repository.allDriveFileMetadata()).thenReturn(Map.of("drive-1", "2026-09-01T00:00:00Z"));

        mockMvc.perform(get("/api/v1/drive-files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['drive-1']").value("2026-09-01T00:00:00Z"));
    }

    @Test
    void returns404ForUnknownDriveFile() throws Exception {
        when(repository.driveFileMetadata("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/drive-files/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void returnsMetadataForAKnownDriveFile() throws Exception {
        when(repository.driveFileMetadata("drive-1"))
                .thenReturn(Optional.of(new DriveFileMetadata("brief.pdf", "2026-09-01T00:00:00Z")));

        mockMvc.perform(get("/api/v1/drive-files/drive-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("brief.pdf"));
    }

    @Test
    void upsertsMetadataAndReturns204() throws Exception {
        mockMvc.perform(put("/api/v1/drive-files/drive-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"brief.pdf\", \"modifiedTime\": \"2026-09-01T00:00:00Z\"}"))
                .andExpect(status().isNoContent());

        verify(repository).upsertDriveFileMetadata("brief.pdf", "drive-1", "2026-09-01T00:00:00Z");
    }

    @Test
    void upsertRejectsBlankSource() throws Exception {
        mockMvc.perform(put("/api/v1/drive-files/drive-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\": \"\", \"modifiedTime\": \"2026-09-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void deletesADocumentByQueryParameterSoSlashesInSourcesWork() throws Exception {
        when(repository.deleteDocument("Clients/Acme/brief.pdf")).thenReturn(7);

        mockMvc.perform(delete("/api/v1/documents").param("source", "Clients/Acme/brief.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(7));
    }

    @Test
    void deleteRequiresASource() throws Exception {
        mockMvc.perform(delete("/api/v1/documents").param("source", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsSources() throws Exception {
        when(repository.allSources()).thenReturn(java.util.List.of("a.pdf", "b.pdf"));

        mockMvc.perform(get("/api/v1/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("b.pdf"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl services/knowledge-service test -Dtest='ChunkControllerTest,DriveFileControllerTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ChunkController`

- [ ] **Step 3: Write the DTOs**

`api/dto/ChunkSearchRequest.java`:

```java
package com.bloom.eventmanager.knowledge.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChunkSearchRequest(@NotNull float[] embedding, @Min(1) @Max(200) int topK) {

    /** V1's RAG_TOP_K default. A missing topK deserialises as 0, which becomes 15 here. */
    public ChunkSearchRequest {
        if (topK == 0) {
            topK = 15;
        }
    }
}
```

`api/dto/ChunksBySourcesRequest.java`:

```java
package com.bloom.eventmanager.knowledge.api.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

public record ChunksBySourcesRequest(@NotEmpty List<String> sources, @Min(1) @Max(500) int limit) {

    public ChunksBySourcesRequest {
        if (limit == 0) {
            limit = 50;
        }
    }
}
```

`api/dto/DriveFileUpsertRequest.java`:

```java
package com.bloom.eventmanager.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record DriveFileUpsertRequest(@NotBlank String source, @NotBlank String modifiedTime) {
}
```

`api/dto/DeleteDocumentResponse.java`:

```java
package com.bloom.eventmanager.knowledge.api.dto;

public record DeleteDocumentResponse(int deletedCount) {
}
```

- [ ] **Step 4: Write the controllers**

`api/ChunkController.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.knowledge.api.dto.ChunkSearchRequest;
import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@RestController
@RequestMapping("/api/v1/chunks")
public class ChunkController {

    private final ChunkRepository repository;
    private final KnowledgeProperties properties;

    public ChunkController(ChunkRepository repository, KnowledgeProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostMapping("/search")
    public List<ChunkMatch> search(@Valid @RequestBody ChunkSearchRequest request) {
        requireDimensions(request.embedding(), "");
        return repository.searchSimilar(request.embedding(), request.topK());
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void storeBatch(@RequestBody List<ChunkRecord> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "chunks must not be empty");
        }
        for (ChunkRecord chunk : chunks) {
            if (chunk.id() == null || chunk.id().isBlank()) {
                throw new BloomException(ErrorCode.VALIDATION_FAILED, "every chunk needs an id");
            }
            if (chunk.source() == null || chunk.source().isBlank()) {
                throw new BloomException(ErrorCode.VALIDATION_FAILED, "chunk " + chunk.id() + ": source is required");
            }
            if (chunk.text() == null) {
                throw new BloomException(ErrorCode.VALIDATION_FAILED, "chunk " + chunk.id() + ": text is required");
            }
            requireDimensions(chunk.embedding(), "chunk " + chunk.id() + ": ");
        }
        repository.storeBatch(chunks);
    }

    private void requireDimensions(float[] embedding, String context) {
        int expected = properties.embeddingDimensions();
        if (embedding == null) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, context + "embedding is required");
        }
        if (embedding.length != expected) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED,
                    context + "embedding must have " + expected + " dimensions, got " + embedding.length);
        }
    }
}
```

`api/SourceController.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.knowledge.api.dto.ChunksBySourcesRequest;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

    private final ChunkRepository repository;

    public SourceController(ChunkRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<String> all() {
        return repository.allSources();
    }

    /** Random sample of chunks from the given sources — proposal-service's raw material. */
    @PostMapping("/chunks")
    public List<ChunkMatch> chunksBySources(@Valid @RequestBody ChunksBySourcesRequest request) {
        return repository.randomChunksFromSources(request.sources(), request.limit());
    }
}
```

`api/DocumentController.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.knowledge.api.dto.DeleteDocumentResponse;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final ChunkRepository repository;

    public DocumentController(ChunkRepository repository) {
        this.repository = repository;
    }

    /**
     * Query parameter rather than path variable: sources are file names, and
     * Drive files in subfolders carry slashes ("Clients/Acme/brief.pdf").
     */
    @DeleteMapping
    public DeleteDocumentResponse delete(@RequestParam("source") String source) {
        if (source == null || source.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "source is required");
        }
        return new DeleteDocumentResponse(repository.deleteDocument(source));
    }
}
```

`api/DriveFileController.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.knowledge.api.dto.DriveFileUpsertRequest;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

@RestController
@RequestMapping("/api/v1/drive-files")
public class DriveFileController {

    private final ChunkRepository repository;

    public DriveFileController(ChunkRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Map<String, String> all() {
        return repository.allDriveFileMetadata();
    }

    @GetMapping("/{driveFileId}")
    public DriveFileMetadata get(@PathVariable String driveFileId) {
        return repository.driveFileMetadata(driveFileId)
                .orElseThrow(() -> new NotFoundException("No document for Drive file " + driveFileId));
    }

    @PutMapping("/{driveFileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsert(@PathVariable String driveFileId, @Valid @RequestBody DriveFileUpsertRequest request) {
        repository.upsertDriveFileMetadata(request.source(), driveFileId, request.modifiedTime());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl services/knowledge-service test`
Expected: PASS — 1 (`PingControllerTest`) + 2 + 7 + 7 + 8 = 25 tests. If Docker is absent the 7 integration tests report as skipped, not failed.

- [ ] **Step 6: Commit**

```bash
git add services/knowledge-service
git commit -m "feat(knowledge): REST API for chunk search, batch store, sources and drive metadata"
```

---

### Task 5: knowledge-service — configuration and a first look at the real data

**Files:**
- Modify: `config-repo/knowledge-service.yml`

**Interfaces:**
- Consumes: the Config Server from Phase 0; the Neo4j instance holding V1's data
- Produces: a running `knowledge-service` that answers `GET /api/v1/sources` with the documents V1 ingested

- [ ] **Step 1: Replace `config-repo/knowledge-service.yml`**

```yaml
server:
  port: 8082

spring:
  neo4j:
    uri: ${NEO4J_URI:bolt://localhost:7687}
    authentication:
      username: ${NEO4J_USER:neo4j}
      password: ${NEO4J_PASSWORD:password}

bloom:
  knowledge:
    vector-index-name: chunk_embeddings
    # Must match gemini-embedding-001. Changing the model is an index migration, not a config edit.
    embedding-dimensions: 3072
    similarity-function: cosine
  config-server:
    reachable: true

management:
  endpoint:
    health:
      group:
        # V1's /health/ready gated on Neo4j. Boot's readiness group contains only
        # readinessState by default; add the Neo4j indicator explicitly.
        readiness:
          include: readinessState,neo4j
```

- [ ] **Step 2: Point at the Neo4j that holds V1's data and start the service**

If V1's data is in the local Docker Neo4j (bind-mounted at `%USERPROFILE%\neo4j\data` per V1's compose file), the defaults above already match it — make sure Phase 0's `deploy/docker-compose.yml` is running or start V1's compose instead, since both map port 7687. If the data is on the remote instance from V1's `.env`, export `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` in the shell first.

From the repository root, in separate terminals:

```bash
./mvnw -pl infrastructure/discovery-server spring-boot:run
./mvnw -pl infrastructure/config-server spring-boot:run
./mvnw -pl services/knowledge-service spring-boot:run
```

Expected in the `knowledge-service` log: `Vector index 'chunk_embeddings' already exists` — V1 created it. If the log says `Creating vector index` instead, you are pointed at an **empty** Neo4j, not V1's data; stop and fix the connection before continuing.

- [ ] **Step 3: Verify against real data**

```bash
curl -s http://localhost:8082/api/v1/sources
```

Expected: a JSON array of PDF names V1 ingested (non-empty).

```bash
curl -s http://localhost:8082/api/v1/drive-files
```

Expected: a JSON object mapping Drive file ids to ISO timestamps (may be empty if V1 only ingested from the local directory).

```bash
curl -s http://localhost:8082/actuator/health/readiness
```

Expected: `{"status":"UP","components":{"neo4j":{"status":"UP"},"readinessState":{"status":"UP"}}}`

- [ ] **Step 4: Commit**

```bash
git add config-repo/knowledge-service.yml
git commit -m "config(knowledge): Neo4j connection, index settings, Neo4j in readiness"
```

---

### Task 6: ai-service — module, properties, and the V1-compatible embedding codec

**Files:**
- Create: `services/ai-service/pom.xml`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/AiServiceApplication.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/config/AiProperties.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/cache/EmbeddingCodec.java`
- Create: `services/ai-service/src/main/resources/application.yml`
- Test: `services/ai-service/src/test/java/com/bloom/eventmanager/ai/cache/EmbeddingCodecTest.java`
- Modify: `pom.xml` (add `<module>services/ai-service</module>`)

**Interfaces:**
- Consumes: `bloom-parent`, `bloom-bom` (for Spring AI versions), `bloom-common`
- Produces:
  - `AiProperties(String apiKey, String model, List<String> fallbackModels, String embeddingModel, int embeddingDimensions, int embeddingBatchSize, String cacheKeyPrefix)` — `@ConfigurationProperties("bloom.ai")`
  - `EmbeddingCodec.encode(float[]) -> String`, `EmbeddingCodec.decode(String) -> float[]`, `EmbeddingCodec.hashText(String) -> String` — all static

The codec is the parity-critical piece: V1 stores each embedding as `Buffer.from(new Float32Array(embedding).buffer).toString("base64")` — little-endian float32 — under key `embed:<model>:<sha256(text).slice(0, 32)>`. Reproducing this exactly means a V2 deployment inherits V1's warm cache instead of re-paying Gemini for every chunk.

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.ai.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingCodecTest {

    @Test
    void encodesAsLittleEndianFloat32Base64ExactlyLikeV1() {
        // Node: Buffer.from(new Float32Array([1, 0.5]).buffer).toString("base64") === "AACAPwAAAD8="
        assertThat(EmbeddingCodec.encode(new float[] { 1f, 0.5f })).isEqualTo("AACAPwAAAD8=");
    }

    @Test
    void roundTripsArbitraryVectors() {
        float[] original = { 0.123456f, -0.98765f, 3.4e-5f, 0f, 1e10f };

        float[] back = EmbeddingCodec.decode(EmbeddingCodec.encode(original));

        assertThat(back).containsExactly(original);
    }

    @Test
    void decodesAnEmptyPayloadToAnEmptyVector() {
        assertThat(EmbeddingCodec.decode("")).isEmpty();
    }

    @Test
    void hashesTextToTheFirst32HexCharsOfSha256LikeV1() {
        // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertThat(EmbeddingCodec.hashText("hello")).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e");
    }

    @Test
    void hashIsStableForUnicode() {
        assertThat(EmbeddingCodec.hashText("فعاليات بناء الفريق"))
                .isEqualTo(EmbeddingCodec.hashText("فعاليات بناء الفريق"))
                .hasSize(32);
    }
}
```

- [ ] **Step 2: Create the module**

`services/ai-service/pom.xml`:

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

    <artifactId>ai-service</artifactId>
    <name>AI Service</name>

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
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>

        <!-- Spring AI CORE modules only. The starters depend on Boot 4.1.1 and
             would break the 4.0.8 baseline that Spring Cloud 2025.1.3 requires.
             Beans are wired by hand in GeminiClientConfig. -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-google-genai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-google-genai-embedding</artifactId>
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

Add `<module>services/ai-service</module>` to the root `pom.xml` `<modules>` block, after `services/knowledge-service`.

`AiServiceApplication.java`:

```java
package com.bloom.eventmanager.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
```

`config/AiProperties.java`:

```java
package com.bloom.eventmanager.ai.config;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Gemini and cache settings. The API key is the only secret this service holds
 * and the only module in the system that holds it.
 *
 * <p>Defaults track V1's CONSTANTS. {@code fallbackModels} differs on purpose:
 * V1's list named Gemini 1.5 models that no longer exist, so its fallback chain
 * had silently become "fail, fail, fail". Override via config as models evolve.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.ai")
public record AiProperties(
        @NotBlank String apiKey,
        @DefaultValue("gemini-3-flash-preview") String model,
        @DefaultValue({ "gemini-2.5-flash", "gemini-2.0-flash-001" }) List<String> fallbackModels,
        @DefaultValue("gemini-embedding-001") String embeddingModel,
        @DefaultValue("3072") @Min(1) int embeddingDimensions,
        @DefaultValue("20") @Min(1) @Max(100) int embeddingBatchSize,
        @DefaultValue("bloom:") String cacheKeyPrefix) {
}
```

`cache/EmbeddingCodec.java`:

```java
package com.bloom.eventmanager.ai.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Byte-for-byte compatible with V1's CachedAIService encoding, so a V2
 * deployment inherits the warm Redis cache instead of re-paying Gemini.
 *
 * <p>V1: {@code Buffer.from(new Float32Array(v).buffer).toString("base64")} —
 * float32, little-endian (the only byte order Node ever ran on here).
 * Key hash: {@code sha256(text).hex.slice(0, 32)}.
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    public static String encode(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(embedding);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public static float[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        FloatBuffer floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[floats.remaining()];
        floats.get(out);
        return out;
    }

    public static String hashText(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", ex);
        }
    }
}
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: ai-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}

server:
  port: 8081
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `./mvnw -q -pl services/ai-service test -Dtest=EmbeddingCodecTest`
Expected: PASS — 5 tests

(No red step: the module did not exist, so the first compile *is* the red-to-green transition. The V1 byte-exact assertion is the check that matters.)

- [ ] **Step 4: Commit**

```bash
git add pom.xml services/ai-service
git commit -m "feat(ai): ai-service module with V1-compatible embedding codec"
```

---

### Task 7: ai-service — Redis embedding cache that never throws

**Files:**
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/cache/EmbeddingCache.java`
- Test: `services/ai-service/src/test/java/com/bloom/eventmanager/ai/cache/EmbeddingCacheIntegrationTests.java`

**Interfaces:**
- Consumes: `StringRedisTemplate` (autoconfigured), `AiProperties`, `EmbeddingCodec`
- Produces: `EmbeddingCache` with `Optional<float[]> get(String text)`, `void put(String text, float[] embedding)`, `String key(String text)`. Neither method ever throws; failures log at DEBUG and act as a miss.

- [ ] **Step 1: Write the failing integration test**

```java
package com.bloom.eventmanager.ai.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.ai.api-key=test-key",
        "bloom.ai.embedding-model=gemini-embedding-001",
        "bloom.ai.cache-key-prefix=bloom:"
})
class EmbeddingCacheIntegrationTests {

    // Boot infers the Redis connection from the "redis" name; no Redis-specific module needed.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private EmbeddingCache cache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void missesOnUnknownText() {
        assertThat(cache.get("never seen")).isEmpty();
    }

    @Test
    void roundTripsThroughRedis() {
        cache.put("team building", new float[] { 1f, 0.5f, -0.25f });

        assertThat(cache.get("team building")).hasValueSatisfying(v -> assertThat(v).containsExactly(1f, 0.5f, -0.25f));
    }

    @Test
    void usesV1sKeyLayoutSoExistingEntriesAreReused() {
        // What V1 would have written for the same text and model.
        redisTemplate.opsForValue().set("bloom:embed:gemini-embedding-001:" + EmbeddingCodec.hashText("legacy text"),
                "AACAPwAAAD8=");

        assertThat(cache.get("legacy text")).hasValueSatisfying(v -> assertThat(v).containsExactly(1f, 0.5f));
        assertThat(cache.key("legacy text")).startsWith("bloom:embed:gemini-embedding-001:").hasSize(
                "bloom:embed:gemini-embedding-001:".length() + 32);
    }

    @Test
    void storesWithoutExpiry() {
        cache.put("immutable", new float[] { 0.1f });

        assertThat(redisTemplate.getExpire(cache.key("immutable"))).isEqualTo(-1L);
    }

    @Test
    void treatsCorruptEntriesAsAMiss() {
        redisTemplate.opsForValue().set(cache.key("corrupt"), "not base64 !!!");

        assertThat(cache.get("corrupt")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl services/ai-service test -Dtest=EmbeddingCacheIntegrationTests`
Expected: COMPILATION FAILURE — `cannot find symbol: class EmbeddingCache`

- [ ] **Step 3: Write the implementation**

```java
package com.bloom.eventmanager.ai.cache;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ai.config.AiProperties;

/**
 * Embedding cache in front of Gemini.
 *
 * <p>Two rules, both inherited from V1:
 * <ol>
 * <li>A cache problem is never a request problem. Every Redis failure is
 * caught, logged at DEBUG, and treated as a miss.</li>
 * <li>No TTL. An embedding is a pure function of (text, model), so entries
 * are immutable and Redis's allkeys-lru policy reclaims the cold ones.</li>
 * </ol>
 *
 * <p>The model is part of the key: vectors from different models have
 * different dimensionality and must never be served for each other.
 */
@Component
public class EmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCache.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final String embeddingModel;

    public EmbeddingCache(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.keyPrefix = properties.cacheKeyPrefix();
        this.embeddingModel = properties.embeddingModel();
    }

    public String key(String text) {
        return keyPrefix + "embed:" + embeddingModel + ":" + EmbeddingCodec.hashText(text);
    }

    public Optional<float[]> get(String text) {
        String key = key(text);
        try {
            String encoded = redis.opsForValue().get(key);
            return encoded == null ? Optional.empty() : Optional.of(EmbeddingCodec.decode(encoded));
        }
        catch (RuntimeException ex) {
            log.debug("Embedding cache read failed for {} — falling through to source", key, ex);
            return Optional.empty();
        }
    }

    public void put(String text, float[] embedding) {
        String key = key(text);
        try {
            redis.opsForValue().set(key, EmbeddingCodec.encode(embedding));
        }
        catch (RuntimeException ex) {
            log.debug("Embedding cache write failed for {} — value not cached", key, ex);
        }
    }
}
```

`IllegalArgumentException` from a corrupt Base64 payload is a `RuntimeException`, so it is caught by the same clause — that is what the last test pins.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl services/ai-service test -Dtest=EmbeddingCacheIntegrationTests`
Expected: PASS — 5 tests

- [ ] **Step 5: Commit**

```bash
git add services/ai-service
git commit -m "feat(ai): Redis embedding cache, V1 key layout, never throws"
```

---

### Task 8: ai-service — Gemini wiring and the caching embedding service

**Files:**
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/domain/EmbeddingPort.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/domain/GenerationPort.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/domain/GenerationOptions.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/domain/GenerationResult.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/gemini/GeminiClientConfig.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/gemini/GeminiEmbeddingService.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/cache/CachingEmbeddingService.java`
- Test: `services/ai-service/src/test/java/com/bloom/eventmanager/ai/cache/CachingEmbeddingServiceTest.java`

**Interfaces:**
- Consumes: Spring AI `EmbeddingModel`, `EmbeddingCache`, `AiProperties`
- Produces:
  - `EmbeddingPort { float[] embed(String text); List<float[]> embedBatch(List<String> texts); }`
  - `GenerationPort { GenerationResult generate(String input, GenerationOptions options); }`
  - `GenerationOptions(Boolean includeThoughts, Double temperature, Integer maxOutputTokens)` with `static GenerationOptions defaults()`
  - `GenerationResult(String text, String thoughts)` — `thoughts` is null when the model produced none
  - Beans: `com.google.genai.Client`, `org.springframework.ai.chat.model.ChatModel`, `org.springframework.ai.embedding.EmbeddingModel`
  - `GeminiEmbeddingService` (an `EmbeddingPort`, not primary) and `CachingEmbeddingService` (the `@Primary EmbeddingPort`)

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.ai.cache;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.ai.gemini.GeminiEmbeddingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingEmbeddingServiceTest {

    private final GeminiEmbeddingService upstream = mock(GeminiEmbeddingService.class);
    private final EmbeddingCache cache = mock(EmbeddingCache.class);
    private final CachingEmbeddingService service = new CachingEmbeddingService(upstream, cache);

    @Test
    void singleEmbedServesAHitWithoutCallingGemini() {
        when(cache.get("hello")).thenReturn(Optional.of(new float[] { 1f }));

        assertThat(service.embed("hello")).containsExactly(1f);
        verify(upstream, never()).embed(anyString());
    }

    @Test
    void singleEmbedFillsTheCacheOnAMiss() {
        when(cache.get("hello")).thenReturn(Optional.empty());
        when(upstream.embed("hello")).thenReturn(new float[] { 2f });

        assertThat(service.embed("hello")).containsExactly(2f);
        verify(cache).put("hello", new float[] { 2f });
    }

    @Test
    void batchSendsOnlyMissesUpstreamAndKeepsPositions() {
        when(cache.get("a")).thenReturn(Optional.of(new float[] { 1f }));
        when(cache.get("b")).thenReturn(Optional.empty());
        when(cache.get("c")).thenReturn(Optional.empty());
        when(upstream.embedBatch(List.of("b", "c"))).thenReturn(List.of(new float[] { 2f }, new float[] { 3f }));

        List<float[]> result = service.embedBatch(List.of("a", "b", "c"));

        assertThat(result.get(0)).containsExactly(1f);
        assertThat(result.get(1)).containsExactly(2f);
        assertThat(result.get(2)).containsExactly(3f);
        verify(cache).put("b", new float[] { 2f });
        verify(cache).put("c", new float[] { 3f });
    }

    @Test
    void batchDedupesRepeatedTextsSoBoilerplateIsBilledOnce() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(upstream.embedBatch(List.of("same"))).thenReturn(List.of(new float[] { 9f }));

        List<float[]> result = service.embedBatch(List.of("same", "same", "same"));

        assertThat(result).hasSize(3).allSatisfy(v -> assertThat(v).containsExactly(9f));
        verify(upstream).embedBatch(List.of("same"));
    }

    @Test
    void batchWithAllHitsNeverTouchesGemini() {
        when(cache.get(anyString())).thenReturn(Optional.of(new float[] { 1f }));

        service.embedBatch(List.of("a", "b"));

        verify(upstream, never()).embedBatch(any());
    }

    @Test
    void emptyBatchIsEmpty() {
        assertThat(service.embedBatch(List.of())).isEmpty();
        verify(upstream, never()).embedBatch(any());
    }
}
```

The dedupe test guards against the same bug V1's comments warn about: a naive pass-through to the upstream batch method would silently bypass the cache for every batch caller — ingestion, sync, retrieval — and every test would still pass.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl services/ai-service test -Dtest=CachingEmbeddingServiceTest`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.ai.gemini does not exist`

- [ ] **Step 3: Write the domain ports**

`domain/EmbeddingPort.java`:

```java
package com.bloom.eventmanager.ai.domain;

import java.util.List;

public interface EmbeddingPort {

    float[] embed(String text);

    /** Result is positionally aligned with {@code texts}. */
    List<float[]> embedBatch(List<String> texts);
}
```

`domain/GenerationOptions.java`:

```java
package com.bloom.eventmanager.ai.domain;

/** Per-call overrides. Any null field means "model default". */
public record GenerationOptions(Boolean includeThoughts, Double temperature, Integer maxOutputTokens) {

    public static GenerationOptions defaults() {
        return new GenerationOptions(null, null, null);
    }
}
```

`domain/GenerationResult.java`:

```java
package com.bloom.eventmanager.ai.domain;

/** {@code thoughts} is null when the model emitted no thought parts. */
public record GenerationResult(String text, String thoughts) {
}
```

`domain/GenerationPort.java`:

```java
package com.bloom.eventmanager.ai.domain;

public interface GenerationPort {

    GenerationResult generate(String input, GenerationOptions options);
}
```

- [ ] **Step 4: Write the Gemini wiring**

`gemini/GeminiClientConfig.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bloom.eventmanager.ai.config.AiProperties;

/**
 * Manual wiring of Spring AI's Gemini models.
 *
 * <p>This is what the Spring AI starter would do for us — but the starter is
 * built against Boot 4.1.1, and Spring Cloud 2025.1.3 pins us to 4.0.8. The
 * core modules have no Boot dependency at all, so these twenty lines are the
 * whole price of staying on a supported baseline.
 */
@Configuration
public class GeminiClientConfig {

    /** Gemini Developer API mode: an API key, no Vertex project. */
    @Bean
    Client genAiClient(AiProperties properties) {
        return Client.builder().apiKey(properties.apiKey()).build();
    }

    @Bean
    ChatModel chatModel(Client client, AiProperties properties) {
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(properties.model()).build())
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(Client client, AiProperties properties) {
        GoogleGenAiEmbeddingConnectionDetails connection = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(properties.apiKey())
                .genAiClient(client)
                .build();
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(properties.embeddingModel())
                .build();
        return new GoogleGenAiTextEmbeddingModel(connection, options);
    }
}
```

`gemini/GeminiEmbeddingService.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * Uncached Gemini embeddings. Not the primary EmbeddingPort — inject this only
 * from {@link com.bloom.eventmanager.ai.cache.CachingEmbeddingService}.
 */
@Service
public class GeminiEmbeddingService implements EmbeddingPort {

    private final EmbeddingModel model;
    private final int batchSize;

    public GeminiEmbeddingService(EmbeddingModel model, AiProperties properties) {
        this.model = model;
        this.batchSize = properties.embeddingBatchSize();
    }

    @Override
    public float[] embed(String text) {
        try {
            return model.embed(text);
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Gemini embedding failed", ex);
        }
    }

    /** Sequential slices of {@code batchSize}, as V1 did, to stay under rate limits. */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> slice = texts.subList(from, Math.min(from + batchSize, texts.size()));
            try {
                out.addAll(model.embed(slice));
            }
            catch (RuntimeException ex) {
                throw new ExternalServiceException("Gemini batch embedding failed", ex);
            }
        }
        return out;
    }
}
```

`cache/CachingEmbeddingService.java`:

```java
package com.bloom.eventmanager.ai.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.ai.gemini.GeminiEmbeddingService;

/**
 * The EmbeddingPort everyone gets. Serves hits from Redis and forwards only
 * genuine misses — deduplicated — to Gemini.
 *
 * <p>The batch path must not delegate to the upstream batch method with the
 * original list: that would bypass the cache for every batch caller while every
 * test kept passing. It resolves each text here first.
 */
@Primary
@Service
public class CachingEmbeddingService implements EmbeddingPort {

    private final GeminiEmbeddingService upstream;
    private final EmbeddingCache cache;

    public CachingEmbeddingService(GeminiEmbeddingService upstream, EmbeddingCache cache) {
        this.upstream = upstream;
        this.cache = cache;
    }

    @Override
    public float[] embed(String text) {
        Optional<float[]> hit = cache.get(text);
        if (hit.isPresent()) {
            return hit.get();
        }
        float[] embedding = upstream.embed(text);
        cache.put(text, embedding);
        return embedding;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        float[][] results = new float[texts.size()][];
        Map<String, List<Integer>> missIndicesByText = new LinkedHashMap<>();

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            Optional<float[]> hit = cache.get(text);
            if (hit.isPresent()) {
                results[i] = hit.get();
            }
            else {
                missIndicesByText.computeIfAbsent(text, key -> new ArrayList<>()).add(i);
            }
        }

        if (!missIndicesByText.isEmpty()) {
            List<String> missTexts = new ArrayList<>(missIndicesByText.keySet());
            List<float[]> fresh = upstream.embedBatch(missTexts);
            for (int j = 0; j < missTexts.size(); j++) {
                String text = missTexts.get(j);
                float[] embedding = fresh.get(j);
                for (int i : missIndicesByText.get(text)) {
                    results[i] = embedding;
                }
                cache.put(text, embedding);
            }
        }

        return Arrays.asList(results);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl services/ai-service test -Dtest=CachingEmbeddingServiceTest`
Expected: PASS — 6 tests

- [ ] **Step 6: Commit**

```bash
git add services/ai-service
git commit -m "feat(ai): manual Spring AI Gemini wiring and cache-first embedding service"
```

---

### Task 9: ai-service — generation with model fallback and thought splitting

**Files:**
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/gemini/GeminiErrors.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/gemini/GeminiGenerationService.java`
- Test: `services/ai-service/src/test/java/com/bloom/eventmanager/ai/gemini/GeminiErrorsTest.java`
- Test: `services/ai-service/src/test/java/com/bloom/eventmanager/ai/gemini/GeminiGenerationServiceTest.java`

**Interfaces:**
- Consumes: Spring AI `ChatModel`, `AiProperties`, `GenerationPort` / `GenerationOptions` / `GenerationResult` from Task 8
- Produces: `GeminiGenerationService` (the `GenerationPort`), `GeminiErrors.statusCode(Throwable) -> OptionalInt`, `GeminiErrors.shouldTryNextModel(Throwable) -> boolean`

Two V1 behaviours are ported. **Thought splitting:** V1 walked the candidate's parts and separated `part.thought` from answer text. Spring AI exposes the same flag as `isThought` in each `Generation`'s message metadata. **Model fallback:** on a 429 quota error or a 404 unknown-model error V1 moved to the next model in its chain rather than retrying; anything else was retried by the transport (Spring AI's `RetryTemplate` does that here) and then surfaced.

- [ ] **Step 1: Write the failing tests**

`GeminiErrorsTest.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiErrorsTest {

    @Test
    void readsA429FromTheMessage() {
        RuntimeException ex = new RuntimeException("429 RESOURCE_EXHAUSTED: quota exceeded for GenerateRequestsPerDay");

        assertThat(GeminiErrors.statusCode(ex)).hasValue(429);
        assertThat(GeminiErrors.shouldTryNextModel(ex)).isTrue();
    }

    @Test
    void readsA404BuriedInACause() {
        RuntimeException ex = new IllegalStateException("wrapped",
                new RuntimeException("404 NOT_FOUND: models/gemini-9 is not found"));

        assertThat(GeminiErrors.statusCode(ex)).hasValue(404);
        assertThat(GeminiErrors.shouldTryNextModel(ex)).isTrue();
    }

    @Test
    void aFiveHundredIsNotAReasonToSwitchModels() {
        RuntimeException ex = new RuntimeException("500 INTERNAL: backend error");

        assertThat(GeminiErrors.statusCode(ex)).hasValue(500);
        assertThat(GeminiErrors.shouldTryNextModel(ex)).isFalse();
    }

    @Test
    void noCodeMeansNoSwitch() {
        assertThat(GeminiErrors.statusCode(new RuntimeException("connection reset"))).isEmpty();
        assertThat(GeminiErrors.shouldTryNextModel(new RuntimeException("connection reset"))).isFalse();
    }

    @Test
    void doesNotMistakeA429InsideALongerNumber() {
        assertThat(GeminiErrors.statusCode(new RuntimeException("request id 14290 failed"))).isEmpty();
    }
}
```

`GeminiGenerationServiceTest.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationResult;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiGenerationServiceTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AiProperties properties = new AiProperties("key", "gemini-primary",
            List.of("gemini-fallback-1", "gemini-fallback-2"), "gemini-embedding-001", 3072, 20, "bloom:");
    private final GeminiGenerationService service = new GeminiGenerationService(chatModel, properties);

    @Test
    void separatesThoughtPartsFromAnswerParts() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                part("Let me think about venues…", true),
                part("Here is the plan.", false),
                part(" More detail.", false)));

        GenerationResult result = service.generate("plan an event", GenerationOptions.defaults());

        assertThat(result.text()).isEqualTo("Here is the plan. More detail.");
        assertThat(result.thoughts()).isEqualTo("Let me think about venues…");
    }

    @Test
    void thoughtsAreNullWhenThereAreNone() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(part("answer", false)));

        assertThat(service.generate("q", GenerationOptions.defaults()).thoughts()).isNull();
    }

    @Test
    void passesOptionsThroughToGemini() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(part("ok", false)));

        service.generate("q", new GenerationOptions(true, 1.2, 8192));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("gemini-primary");
        assertThat(options.getTemperature()).isEqualTo(1.2);
        assertThat(options.getMaxOutputTokens()).isEqualTo(8192);
        assertThat(options.getIncludeThoughts()).isTrue();
    }

    @Test
    void fallsBackToTheNextModelOnQuotaExhaustion() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("429 RESOURCE_EXHAUSTED"))
                .thenReturn(response(part("from fallback", false)));

        GenerationResult result = service.generate("q", GenerationOptions.defaults());

        assertThat(result.text()).isEqualTo("from fallback");
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(((GoogleGenAiChatOptions) prompts.getAllValues().get(1).getOptions()).getModel())
                .isEqualTo("gemini-fallback-1");
    }

    @Test
    void doesNotSwitchModelsForNonQuotaFailures() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("500 INTERNAL"));

        assertThatThrownBy(() -> service.generate("q", GenerationOptions.defaults()))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("gemini-primary");
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void reportsFailureWhenEveryModelInTheChainIsExhausted() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("429 RESOURCE_EXHAUSTED"));

        assertThatThrownBy(() -> service.generate("q", GenerationOptions.defaults()))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("all models");
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    private static Generation part(String text, boolean thought) {
        AssistantMessage message = AssistantMessage.builder()
                .content(text)
                .properties(Map.of("isThought", thought))
                .build();
        return new Generation(message);
    }

    private static ChatResponse response(Generation... generations) {
        return new ChatResponse(List.of(generations));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl services/ai-service test -Dtest='GeminiErrorsTest,GeminiGenerationServiceTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class GeminiErrors`

- [ ] **Step 3: Write the implementation**

`gemini/GeminiErrors.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies Gemini failures the way V1 did: a 429 (quota) or a 404 (model
 * does not exist) means "try the next model in the chain"; anything else is a
 * genuine failure to report.
 *
 * <p>The SDK's exception messages start with the HTTP status ("429
 * RESOURCE_EXHAUSTED: …"). Reading it from the message, through the cause
 * chain, keeps this independent of which wrapper Spring AI's retry layer
 * happens to throw.
 */
final class GeminiErrors {

    private static final Pattern LEADING_STATUS = Pattern.compile("(?<!\\d)([45]\\d\\d)(?!\\d)");

    private GeminiErrors() {
    }

    static OptionalInt statusCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = LEADING_STATUS.matcher(message);
            if (matcher.find()) {
                return OptionalInt.of(Integer.parseInt(matcher.group(1)));
            }
        }
        return OptionalInt.empty();
    }

    static boolean shouldTryNextModel(Throwable failure) {
        OptionalInt code = statusCode(failure);
        return code.isPresent() && (code.getAsInt() == 429 || code.getAsInt() == 404);
    }
}
```

`gemini/GeminiGenerationService.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationPort;
import com.bloom.eventmanager.ai.domain.GenerationResult;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * Text generation with V1's two behaviours: walk a model chain on quota /
 * unknown-model errors, and split Gemini's "thought" parts from the answer.
 *
 * <p>Generation is deliberately uncached (V1 too): the chat path runs at
 * temperature 1.2 with a "never repeat a previous suggestion" instruction,
 * which a cache would quietly break.
 */
@Service
public class GeminiGenerationService implements GenerationPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiGenerationService.class);

    private final ChatModel chatModel;
    private final List<String> modelChain;

    public GeminiGenerationService(ChatModel chatModel, AiProperties properties) {
        this.chatModel = chatModel;
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        chain.add(properties.model());
        chain.addAll(properties.fallbackModels());
        this.modelChain = List.copyOf(chain);
    }

    @Override
    public GenerationResult generate(String input, GenerationOptions options) {
        RuntimeException last = null;
        for (String model : modelChain) {
            try {
                ChatResponse response = chatModel.call(new Prompt(input, toChatOptions(model, options)));
                return split(response);
            }
            catch (RuntimeException ex) {
                last = ex;
                if (GeminiErrors.shouldTryNextModel(ex)) {
                    log.warn("Model {} unavailable ({}); trying the next model in the chain",
                            model, GeminiErrors.statusCode(ex).orElse(-1));
                    continue;
                }
                throw new ExternalServiceException("Gemini generation failed with model " + model, ex);
            }
        }
        throw new ExternalServiceException("Gemini generation failed after trying all models", last);
    }

    private static GoogleGenAiChatOptions toChatOptions(String model, GenerationOptions options) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder().model(model);
        if (options.temperature() != null) {
            builder.temperature(options.temperature());
        }
        if (options.maxOutputTokens() != null) {
            builder.maxOutputTokens(options.maxOutputTokens());
        }
        if (Boolean.TRUE.equals(options.includeThoughts())) {
            builder.includeThoughts(true);
        }
        return builder.build();
    }

    /** Spring AI yields one Generation per Gemini part, each flagged isThought in its metadata. */
    static GenerationResult split(ChatResponse response) {
        StringBuilder text = new StringBuilder();
        List<String> thoughts = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();
            String part = message.getText();
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (Boolean.TRUE.equals(message.getMetadata().get("isThought"))) {
                thoughts.add(part.trim());
            }
            else {
                text.append(part);
            }
        }
        String joinedThoughts = String.join("\n", thoughts).trim();
        return new GenerationResult(text.toString().trim(), joinedThoughts.isEmpty() ? null : joinedThoughts);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl services/ai-service test -Dtest='GeminiErrorsTest,GeminiGenerationServiceTest'`
Expected: PASS — 5 + 6 tests

- [ ] **Step 5: Commit**

```bash
git add services/ai-service
git commit -m "feat(ai): generation with model fallback chain and thought splitting"
```

---

### Task 10: ai-service — REST API and configuration

**Files:**
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/dto/EmbedRequest.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/dto/EmbedResponse.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/dto/EmbedBatchRequest.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/dto/EmbedBatchResponse.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/dto/GenerateRequest.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/dto/GenerateResponse.java`
- Create: `services/ai-service/src/main/java/com/bloom/eventmanager/ai/api/AiController.java`
- Create: `config-repo/ai-service.yml`
- Test: `services/ai-service/src/test/java/com/bloom/eventmanager/ai/api/AiControllerTest.java`

**Interfaces:**
- Consumes: `EmbeddingPort` (primary = caching), `GenerationPort`
- Produces the surface every other service calls:

```
POST /api/v1/ai/embed          {text}                                            -> {embedding: float[]}
POST /api/v1/ai/embed/batch    {texts: string[]}                                 -> {embeddings: float[][]}
POST /api/v1/ai/generate       {input, config?: {includeThoughts, temperature,
                                                 maxOutputTokens}}                -> {text, thoughts?}
```

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.ai.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationPort;
import com.bloom.eventmanager.ai.domain.GenerationResult;
import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
@EnableConfigurationProperties(AiProperties.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmbeddingPort embeddings;

    @MockitoBean
    private GenerationPort generation;

    @Test
    void embedsASingleText() throws Exception {
        when(embeddings.embed("hello")).thenReturn(new float[] { 0.1f, 0.2f });

        mockMvc.perform(post("/api/v1/ai/embed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.length()").value(2))
                .andExpect(jsonPath("$.embedding[1]").value(0.2));
    }

    @Test
    void rejectsBlankText() throws Exception {
        mockMvc.perform(post("/api/v1/ai/embed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        verify(embeddings, never()).embed(any());
    }

    @Test
    void embedsABatchInOrder() throws Exception {
        when(embeddings.embedBatch(List.of("a", "b")))
                .thenReturn(List.of(new float[] { 1f }, new float[] { 2f }));

        mockMvc.perform(post("/api/v1/ai/embed/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\": [\"a\", \"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddings[0][0]").value(1.0))
                .andExpect(jsonPath("$.embeddings[1][0]").value(2.0));
    }

    @Test
    void rejectsAnEmptyBatch() throws Exception {
        mockMvc.perform(post("/api/v1/ai/embed/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"texts\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generatesWithDefaultsWhenNoConfigIsGiven() throws Exception {
        when(generation.generate(eq("plan"), eq(GenerationOptions.defaults())))
                .thenReturn(new GenerationResult("the plan", null));

        mockMvc.perform(post("/api/v1/ai/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"plan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("the plan"))
                .andExpect(jsonPath("$.thoughts").doesNotExist());
    }

    @Test
    void generatesWithExplicitConfigAndReturnsThoughts() throws Exception {
        when(generation.generate("plan", new GenerationOptions(true, 1.2, 4096)))
                .thenReturn(new GenerationResult("the plan", "thinking…"));

        mockMvc.perform(post("/api/v1/ai/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"plan\", \"config\": {\"includeThoughts\": true, \"temperature\": 1.2, \"maxOutputTokens\": 4096}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thoughts").value("thinking…"));
    }

    @Test
    void surfacesGeminiFailureAs502ProblemDetail() throws Exception {
        when(generation.generate(any(), any()))
                .thenThrow(new ExternalServiceException("Gemini generation failed", new RuntimeException("boom")));

        mockMvc.perform(post("/api/v1/ai/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"plan\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("EXTERNAL_SERVICE_ERROR"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl services/ai-service test -Dtest=AiControllerTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class AiController`

- [ ] **Step 3: Write the DTOs and controller**

`api/dto/EmbedRequest.java`:

```java
package com.bloom.eventmanager.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EmbedRequest(@NotBlank String text) {
}
```

`api/dto/EmbedResponse.java`:

```java
package com.bloom.eventmanager.ai.api.dto;

public record EmbedResponse(float[] embedding) {
}
```

`api/dto/EmbedBatchRequest.java`:

```java
package com.bloom.eventmanager.ai.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record EmbedBatchRequest(@NotEmpty @Size(max = 500) List<@NotBlank String> texts) {
}
```

`api/dto/EmbedBatchResponse.java`:

```java
package com.bloom.eventmanager.ai.api.dto;

import java.util.List;

public record EmbedBatchResponse(List<float[]> embeddings) {
}
```

`api/dto/GenerateRequest.java`:

```java
package com.bloom.eventmanager.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

import com.bloom.eventmanager.ai.domain.GenerationOptions;

/** {@code config} matches the spec's field name; null means model defaults. */
public record GenerateRequest(@NotBlank String input, GenerationOptions config) {
}
```

`api/dto/GenerateResponse.java`:

```java
package com.bloom.eventmanager.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerateResponse(String text, String thoughts) {
}
```

`api/AiController.java`:

```java
package com.bloom.eventmanager.ai.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.ai.api.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ai.api.dto.EmbedBatchResponse;
import com.bloom.eventmanager.ai.api.dto.EmbedRequest;
import com.bloom.eventmanager.ai.api.dto.EmbedResponse;
import com.bloom.eventmanager.ai.api.dto.GenerateRequest;
import com.bloom.eventmanager.ai.api.dto.GenerateResponse;
import com.bloom.eventmanager.ai.domain.EmbeddingPort;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationPort;
import com.bloom.eventmanager.ai.domain.GenerationResult;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final EmbeddingPort embeddings;
    private final GenerationPort generation;

    public AiController(EmbeddingPort embeddings, GenerationPort generation) {
        this.embeddings = embeddings;
        this.generation = generation;
    }

    @PostMapping("/embed")
    public EmbedResponse embed(@Valid @RequestBody EmbedRequest request) {
        return new EmbedResponse(embeddings.embed(request.text()));
    }

    @PostMapping("/embed/batch")
    public EmbedBatchResponse embedBatch(@Valid @RequestBody EmbedBatchRequest request) {
        return new EmbedBatchResponse(embeddings.embedBatch(request.texts()));
    }

    @PostMapping("/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest request) {
        GenerationOptions options = request.config() == null ? GenerationOptions.defaults() : request.config();
        GenerationResult result = generation.generate(request.input(), options);
        return new GenerateResponse(result.text(), result.thoughts());
    }
}
```

`config-repo/ai-service.yml`:

```yaml
server:
  port: 8081

spring:
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
      # Fail fast. Without these a Redis outage turns every cache lookup into a
      # long stall before it falls through — V1 set the same guard on ioredis.
      timeout: 2s
      connect-timeout: 2s

bloom:
  ai:
    api-key: ${GEMINI_API_KEY}
    model: ${GEMINI_MODEL:gemini-3-flash-preview}
    fallback-models: ${GEMINI_FALLBACK_MODELS:gemini-2.5-flash,gemini-2.0-flash-001}
    embedding-model: ${GEMINI_EMBEDDING_MODEL:gemini-embedding-001}
    embedding-dimensions: 3072
    embedding-batch-size: 20
    cache-key-prefix: ${CACHE_KEY_PREFIX:bloom:}

# Redis is a performance layer. Boot's readiness group contains only
# readinessState by default, so a Redis outage is visible on /actuator/health
# but never takes this service out of rotation — the same judgment V1 encoded
# in a comment in healthRoutes.ts.
```

`GEMINI_API_KEY` has no default on purpose: a missing key must fail startup loudly (`@NotBlank`), not run with an empty string.

- [ ] **Step 4: Run every ai-service test**

Run: `./mvnw -q -pl services/ai-service test`
Expected: PASS — 5 + 5 + 6 + 5 + 6 + 7 = 34 tests (the 5 Redis integration tests skip without Docker).

- [ ] **Step 5: Commit**

```bash
git add services/ai-service config-repo/ai-service.yml
git commit -m "feat(ai): REST API for embed, batch embed and generate; service config"
```

---

### Task 11: End-to-end verification of the data spine

**Files:** none created — this task proves the phase exit criterion.

**Interfaces:**
- Consumes: everything above, plus a real Gemini API key and the Neo4j holding V1's data

- [ ] **Step 1: Full reactor build**

Run: `./mvnw clean install`
Expected: BUILD SUCCESS across nine reactor projects (Phase 0's eight plus `ai-service`). With Docker running, the integration tests execute; without it they skip.

- [ ] **Step 2: Provide secrets through the environment, never files**

```bash
export GEMINI_API_KEY=...          # a freshly issued key — the one in V1's .env.example is compromised
export NEO4J_URI=bolt://localhost:7687
export NEO4J_USER=neo4j
export NEO4J_PASSWORD=...
export REDIS_URL=redis://localhost:6379
```

(PowerShell: `$env:GEMINI_API_KEY = "..."` and so on.)

- [ ] **Step 3: Start infrastructure and the platform**

```bash
docker compose -f deploy/docker-compose.yml --profile infra up -d
./mvnw -pl infrastructure/discovery-server spring-boot:run
./mvnw -pl infrastructure/config-server spring-boot:run
./mvnw -pl services/knowledge-service spring-boot:run
./mvnw -pl services/ai-service spring-boot:run
```

Expected: both services appear as `UP` at `http://localhost:8761`.

- [ ] **Step 4: Embed a query through ai-service**

```bash
curl -s -X POST http://localhost:8081/api/v1/ai/embed \
     -H "Content-Type: application/json" \
     -d '{"text": "team building activities for a non-profit"}' > embedding.json
jq '.embedding | length' embedding.json
```

Expected: `3072`

- [ ] **Step 5: Confirm the cache took it**

```bash
docker exec bloom-v2-redis redis-cli --scan --pattern 'bloom:embed:*' | head -3
```

Expected: at least one key of the form `bloom:embed:gemini-embedding-001:<32 hex chars>`. Re-run the Step 4 `curl`; the `ai-service` log shows no Gemini call for the repeated text and the response is identical.

- [ ] **Step 6: Search knowledge-service with that vector — the phase exit criterion**

```bash
jq '{embedding: .embedding, topK: 5}' embedding.json > search.json
curl -s -X POST http://localhost:8082/api/v1/chunks/search \
     -H "Content-Type: application/json" \
     -d @search.json | jq '.[] | {source, page, score}'
```

Expected: five objects with `source` values that are PDF names from V1's corpus and `score` values descending from roughly 0.7–0.9. **This is real data V1 ingested, retrieved by V2 code.**

- [ ] **Step 7: Generate text with thoughts**

```bash
curl -s -X POST http://localhost:8081/api/v1/ai/generate \
     -H "Content-Type: application/json" \
     -d '{"input": "In one sentence, what is a team-building icebreaker?", "config": {"includeThoughts": true}}' | jq
```

Expected: `{"text": "...", "thoughts": "..."}` — `thoughts` present when the model emitted thought parts, absent otherwise. Either is correct; a 502 is not.

- [ ] **Step 8: Health**

```bash
curl -s http://localhost:8081/actuator/health | jq '.components.redis.status'
curl -s http://localhost:8082/actuator/health/readiness | jq '.components.neo4j.status'
```

Expected: `"UP"` and `"UP"`. Now stop Redis (`docker stop bloom-v2-redis`) and repeat Step 4: it still returns 200 with a 3072-vector, and `ai-service`'s readiness stays `UP`. Start Redis again.

- [ ] **Step 9: Commit the verification note**

Append the observed source names from Step 6 to a short section at the bottom of `README.md` titled "Phase 1 verification (date)" so the next phase has a reference corpus to compare against, then:

```bash
git add README.md
git commit -m "docs: Phase 1 data-spine verification against V1 corpus"
```

---

## Phase 1 Exit Criteria

- [ ] `./mvnw clean install` succeeds; all unit tests green; integration tests green with Docker
- [ ] `knowledge-service` logs `Vector index 'chunk_embeddings' already exists` against V1's Neo4j
- [ ] `GET /api/v1/sources` on 8082 lists V1's ingested PDFs
- [ ] `POST /api/v1/ai/embed` on 8081 returns a 3072-dimension vector and populates a `bloom:embed:gemini-embedding-001:*` key in Redis
- [ ] `POST /api/v1/chunks/search` with that vector returns chunks from V1's corpus with descending scores
- [ ] Stopping Redis does not break `/api/v1/ai/embed` or `ai-service` readiness
- [ ] No Kafka, Feign, circuit-breaker, or security code exists in either service

## What Phase 1 Does Not Do

- No service-to-service calls — `retrieval-service` composes these two in Phase 2
- No gateway routes for `ai-service` or `knowledge-service` — they are internal; Phase 2 exposes `/api/v1/rag/retrieve`
- No caching of generation output or of query variations — Phase 2 handles variations in-process
- No ingestion or Drive code — Phase 3
- No circuit breakers around Gemini — Phase 5 (Spring AI's `RetryTemplate` handles transient failures now)
