# Phase 2 — Read Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `retrieval-service` — the dual-search orchestration from V1's `retrievalController.ts` — as a pure composition of `ai-service` and `knowledge-service` over OpenFeign, expose it through the gateway at V1's path, and prove its output matches V1 for the same query.

**Architecture:** `retrieval-service` owns no data. It declares two Feign clients against Eureka service names, runs V1's pipeline (AI query-splitting → parallel embed → parallel vector search → dedupe → AI re-rank → truncate), caches query variations in-process with Caffeine for an hour, and degrades to a plain single-vector search whenever the AI enhancement step fails. The gateway gains one route. Nothing in `ai-service` or `knowledge-service` changes.

**Tech Stack:** Spring Boot 4.0.8, Spring Cloud 2025.1.3 (OpenFeign, Eureka client), Caffeine 3.2.4, Jackson 3 (`tools.jackson`), JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` (sections 5.2 retrieval-service, 5.3, 9.3, 13)

**Prerequisite:** Phase 1 complete — `ai-service` and `knowledge-service` registered in Eureka and verified against V1's corpus.

## Global Constraints

Every task's requirements implicitly include this section.

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.**
- **Boot 4.0 test modules:** `spring-boot-webmvc-test` (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`), `@MockitoBean` from `org.springframework.test.context.bean.override.mockito`.
- **Feign:** `spring-cloud-starter-openfeign`; clients target logical names `ai-service` and `knowledge-service`, never a host. Load balancing comes with the Eureka client starter.
- **JSON parsing in application code uses Jackson 3:** inject `tools.jackson.databind.json.JsonMapper` (Boot 4's autoconfigured mapper). Do not add a `com.fasterxml.jackson.core:jackson-databind` dependency to this service. Jackson **annotations** keep their `com.fasterxml.jackson.annotation` package.
- **Consumer-owned contracts.** Each Feign client's request/response records live in `retrieval-service`'s own `client` package. They mirror the producer's JSON shape but are not shared classes — a producer can evolve without recompiling consumers.
- **Response shape is V1's, field for field:** `{"status":"success","chunks":[…],"metadata":{"originalQuery","variations"?,"totalRetrieved","returned","aiEnhanced"}}`. The React `QueryForm` reads it.
- **Defaults from V1's `CONSTANTS`:** `topK` 15, query-variation cache TTL 1 hour.
- **Degradation, not failure:** if query splitting or re-ranking fails, retrieval still answers with the plain path. Only a failure to obtain *any* embedding is a 502.
- **No circuit breakers yet** (Phase 5); the degradation logic here is what the circuit breaker will later trip into.
- **Gateway property prefix is `spring.cloud.gateway.server.webflux.*`.**
- **Commit with explicit paths, never `git add -A`.**

---

## File Structure

```
services/retrieval-service/
  pom.xml
  src/main/java/com/bloom/eventmanager/retrieval/
    RetrievalServiceApplication.java            @EnableFeignClients, @EnableCaching
    config/RetrievalProperties.java             topK default
    client/AiClient.java                        Feign -> ai-service
    client/KnowledgeClient.java                 Feign -> knowledge-service
    client/dto/EmbedBatchRequest.java, EmbedBatchResponse.java,
               GenerateRequest.java, GenerateResponse.java,
               ChunkSearchRequest.java, ChunkMatch.java
    domain/SearchVariations.java                {specific, broad}
    domain/QueryVariationService.java           @Cacheable AI query split
    domain/ReRankService.java                   AI re-rank, order-preserving on failure
    domain/RetrievalResult.java                 chunks + metadata
    domain/RetrievalService.java                the pipeline
    api/RetrieveController.java                 POST /api/v1/retrieve
    api/dto/RetrieveRequest.java, RetrieveResponse.java
  src/main/resources/application.yml
  src/test/java/com/bloom/eventmanager/retrieval/
    domain/QueryVariationServiceTest.java
    domain/ReRankServiceTest.java
    domain/RetrievalServiceTest.java
    domain/QueryVariationCachingTests.java      proves the Caffeine cache actually engages
    api/RetrieveControllerTest.java
config-repo/retrieval-service.yml
infrastructure/api-gateway/src/main/resources/application.yml           MODIFY: add route
infrastructure/api-gateway/src/test/java/.../GatewayRouteDefinitionTest.java  MODIFY: assert route
pom.xml                                        MODIFY: add module
```

---

### Task 1: Module skeleton and Feign clients

**Files:**
- Create: `services/retrieval-service/pom.xml`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/RetrievalServiceApplication.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/config/RetrievalProperties.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/dto/EmbedBatchRequest.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/dto/EmbedBatchResponse.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/dto/GenerateRequest.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/dto/GenerateResponse.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/dto/ChunkSearchRequest.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/dto/ChunkMatch.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/AiClient.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/client/KnowledgeClient.java`
- Create: `services/retrieval-service/src/main/resources/application.yml`
- Modify: `pom.xml` (add `<module>services/retrieval-service</module>`)

**Interfaces:**
- Consumes: `ai-service`'s `POST /api/v1/ai/embed/batch` and `POST /api/v1/ai/generate`; `knowledge-service`'s `POST /api/v1/chunks/search` (Phase 1 contracts)
- Produces:
  - `AiClient { EmbedBatchResponse embedBatch(EmbedBatchRequest); GenerateResponse generate(GenerateRequest); }`
  - `KnowledgeClient { List<ChunkMatch> search(ChunkSearchRequest); }`
  - `ChunkMatch(String id, String text, String source, int page, double score)` — retrieval's view of a chunk, also the wire shape it returns to callers
  - `RetrievalProperties(int topK)` — `@ConfigurationProperties("bloom.retrieval")`, default 15

There is no unit test for a Feign interface; its correctness is proven end to end in Task 6. The step-1 check here is that the module builds and the application context starts with Feign disabled.

- [ ] **Step 1: Create the POM**

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

    <artifactId>retrieval-service</artifactId>
    <name>Retrieval Service</name>

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
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
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
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-webmvc-test</artifactId>
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

Add `<module>services/retrieval-service</module>` to the root `pom.xml` after `services/ai-service`.

- [ ] **Step 2: Write the application class, properties, DTOs and clients**

`RetrievalServiceApplication.java`:

```java
package com.bloom.eventmanager.retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
@EnableCaching
@EnableFeignClients(basePackages = "com.bloom.eventmanager.retrieval.client")
public class RetrievalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalServiceApplication.class, args);
    }
}
```

`config/RetrievalProperties.java`:

```java
package com.bloom.eventmanager.retrieval.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** V1's RAG_TOP_K. */
@Validated
@ConfigurationProperties(prefix = "bloom.retrieval")
public record RetrievalProperties(@DefaultValue("15") @Min(1) @Max(100) int topK) {
}
```

`client/dto/EmbedBatchRequest.java`:

```java
package com.bloom.eventmanager.retrieval.client.dto;

import java.util.List;

public record EmbedBatchRequest(List<String> texts) {
}
```

`client/dto/EmbedBatchResponse.java`:

```java
package com.bloom.eventmanager.retrieval.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedBatchResponse(List<float[]> embeddings) {
}
```

`client/dto/GenerateRequest.java`:

```java
package com.bloom.eventmanager.retrieval.client.dto;

/** Only the fields retrieval needs; ai-service applies model defaults for the rest. */
public record GenerateRequest(String input) {
}
```

`client/dto/GenerateResponse.java`:

```java
package com.bloom.eventmanager.retrieval.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateResponse(String text, String thoughts) {
}
```

`client/dto/ChunkSearchRequest.java`:

```java
package com.bloom.eventmanager.retrieval.client.dto;

public record ChunkSearchRequest(float[] embedding, int topK) {
}
```

`client/dto/ChunkMatch.java`:

```java
package com.bloom.eventmanager.retrieval.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** knowledge-service's search result, and the shape retrieval returns to its own callers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkMatch(String id, String text, String source, int page, double score) {
}
```

`client/AiClient.java`:

```java
package com.bloom.eventmanager.retrieval.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

@FeignClient(name = "ai-service")
public interface AiClient {

    @PostMapping("/api/v1/ai/embed/batch")
    EmbedBatchResponse embedBatch(@RequestBody EmbedBatchRequest request);

    @PostMapping("/api/v1/ai/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);
}
```

`client/KnowledgeClient.java`:

```java
package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;

@FeignClient(name = "knowledge-service")
public interface KnowledgeClient {

    @PostMapping("/api/v1/chunks/search")
    List<ChunkMatch> search(@RequestBody ChunkSearchRequest request);
}
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: retrieval-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  cache:
    cache-names: queryVariations
    caffeine:
      # V1: CACHE_TTL_QUERY_VARIATIONS_MS = 1 hour. In-process, because this
      # service owns no Redis; the expensive part (embeddings) is cached in ai-service.
      spec: maximumSize=1000,expireAfterWrite=1h

server:
  port: 8084
```

- [ ] **Step 3: Verify it compiles and the context starts**

Run: `./mvnw -q -pl services/retrieval-service compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml services/retrieval-service
git commit -m "feat(retrieval): module skeleton with Feign clients for ai and knowledge"
```

---

### Task 2: Query variations — AI query splitting with an in-process cache

**Files:**
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/domain/SearchVariations.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/domain/QueryVariationService.java`
- Test: `services/retrieval-service/src/test/java/com/bloom/eventmanager/retrieval/domain/QueryVariationServiceTest.java`
- Test: `services/retrieval-service/src/test/java/com/bloom/eventmanager/retrieval/domain/QueryVariationCachingTests.java`

**Interfaces:**
- Consumes: `AiClient.generate`, `JsonExtractor` (bloom-common), `JsonMapper`
- Produces: `SearchVariations(String specific, String broad)`; `QueryVariationService.variations(String query) -> SearchVariations` — **throws** on any failure so the caller can fall back *outside* the cache. Caching a failure would pin a query to its unexpanded form for an hour after Gemini recovered; V1 documents exactly this trap.

- [ ] **Step 1: Write the failing tests**

`QueryVariationServiceTest.java`:

```java
package com.bloom.eventmanager.retrieval.domain;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryVariationServiceTest {

    private final AiClient ai = mock(AiClient.class);
    private final QueryVariationService service = new QueryVariationService(ai, JsonMapper.builder().build());

    @Test
    void parsesSpecificAndBroadFromTheModelsJson() {
        when(ai.generate(any())).thenReturn(new GenerateResponse(
                "```json\n{\"specific\": \"Awlady team building brief\", \"broad\": \"non-profit team building activities\"}\n```",
                null));

        SearchVariations variations = service.variations("Awlady team building");

        assertThat(variations.specific()).isEqualTo("Awlady team building brief");
        assertThat(variations.broad()).isEqualTo("non-profit team building activities");
    }

    @Test
    void sendsV1sPromptWithTheQueryEmbedded() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"specific\": \"a\", \"broad\": \"b\"}", null));

        service.variations("icebreakers for 40 people");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input())
                .contains("Analyze this search query: \"icebreakers for 40 people\"")
                .contains("\"Specific\"")
                .contains("\"Broad\"")
                .contains("Return ONLY a JSON object");
    }

    @Test
    void fallsBackFieldWiseToTheOriginalQueryWhenAFieldIsMissing() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"specific\": \"only this\"}", null));

        SearchVariations variations = service.variations("original");

        assertThat(variations.specific()).isEqualTo("only this");
        assertThat(variations.broad()).isEqualTo("original");
    }

    @Test
    void throwsWhenTheModelReturnsGarbageSoTheCallerCanDegradeOutsideTheCache() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("I cannot help with that.", null));

        assertThatThrownBy(() -> service.variations("q")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void propagatesTransportFailures() {
        when(ai.generate(any())).thenThrow(new RuntimeException("ai-service unavailable"));

        assertThatThrownBy(() -> service.variations("q")).hasMessageContaining("ai-service unavailable");
    }
}
```

`QueryVariationCachingTests.java` — proves `@Cacheable` is wired, which a plain unit test cannot:

```java
package com.bloom.eventmanager.retrieval.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class QueryVariationCachingTests {

    @Autowired
    private QueryVariationService service;

    @MockitoBean
    private AiClient ai;

    @MockitoBean
    private KnowledgeClient knowledge;

    @Test
    void secondCallForTheSameQueryIsServedFromCache() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"specific\": \"s\", \"broad\": \"b\"}", null));

        service.variations("cached query");
        service.variations("cached query");

        verify(ai, times(1)).generate(any());
    }

    @Test
    void failuresAreNotCached() {
        when(ai.generate(any()))
                .thenReturn(new GenerateResponse("garbage", null))
                .thenReturn(new GenerateResponse("{\"specific\": \"s\", \"broad\": \"b\"}", null));

        assertThatThrownBy(() -> service.variations("flaky query")).isInstanceOf(RuntimeException.class);
        assertThat(service.variations("flaky query").specific()).isEqualTo("s");

        verify(ai, times(2)).generate(any());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest='QueryVariationServiceTest,QueryVariationCachingTests'`
Expected: COMPILATION FAILURE — `cannot find symbol: class QueryVariationService`

- [ ] **Step 3: Write the implementation**

`domain/SearchVariations.java`:

```java
package com.bloom.eventmanager.retrieval.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** V1's {specific, broad} query pair. Either may be null straight off the wire. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchVariations(String specific, String broad) {

    public static SearchVariations same(String query) {
        return new SearchVariations(query, query);
    }
}
```

`domain/QueryVariationService.java`:

```java
package com.bloom.eventmanager.retrieval.domain;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.common.ai.JsonExtractor;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Asks Gemini to split a query into a client-specific and a general-knowledge
 * variant. The prompt is V1's verbatim.
 *
 * <p>Cached for an hour per query: the prompt is a fixed template over the
 * query alone, so the result is deterministic. Failures throw and are
 * therefore <em>not</em> cached — Spring Cache never stores an exception — so
 * one transient Gemini error cannot pin a query to its degraded form.
 */
@Service
public class QueryVariationService {

    private final AiClient ai;
    private final JsonMapper json;

    public QueryVariationService(AiClient ai, JsonMapper json) {
        this.ai = ai;
        this.json = json;
    }

    @Cacheable(cacheNames = "queryVariations", key = "#query")
    public SearchVariations variations(String query) {
        String prompt = """
                Analyze this search query: "%s"

                Goal: We need to find both specific client documents AND general knowledge base articles (like activity guides, best practices).

                Task: Generate two search queries:
                1. "Specific": Optimized to find client briefs, previous chats, and specific constraints. (Keep names like "Awlady", "Bloom").
                2. "Broad": Optimized to find general concepts, removing proper nouns that might not exist in the general knowledge base. (e.g. change "Awlady team building" to "non-profit team building activities").

                Format: Return ONLY a JSON object: {"specific": "...", "broad": "..."}""".formatted(query);

        GenerateResponse response = ai.generate(new GenerateRequest(prompt));
        SearchVariations parsed = json.readValue(JsonExtractor.extract(response.text()), SearchVariations.class);

        return new SearchVariations(
                hasText(parsed.specific()) ? parsed.specific() : query,
                hasText(parsed.broad()) ? parsed.broad() : query);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

Jackson 3's `readValue` throws an unchecked `tools.jackson.core.JacksonException` on garbage — that is the "throws so the caller can degrade" path, no wrapping needed.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest='QueryVariationServiceTest,QueryVariationCachingTests'`
Expected: PASS — 5 + 2 tests

- [ ] **Step 5: Commit**

```bash
git add services/retrieval-service
git commit -m "feat(retrieval): cached AI query variations with V1 prompt"
```

---

### Task 3: Re-ranking

**Files:**
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/domain/ReRankService.java`
- Test: `services/retrieval-service/src/test/java/com/bloom/eventmanager/retrieval/domain/ReRankServiceTest.java`

**Interfaces:**
- Consumes: `AiClient.generate`, `JsonExtractor`, `JsonMapper`
- Produces: `ReRankService.reRank(String query, List<ChunkMatch> chunks) -> List<ChunkMatch>` — returns the input order on any failure; never throws.

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.retrieval.domain;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReRankServiceTest {

    private final AiClient ai = mock(AiClient.class);
    private final ReRankService service = new ReRankService(ai, JsonMapper.builder().build());

    private final List<ChunkMatch> chunks = List.of(
            chunk("a", "guide.pdf"), chunk("b", "brief.pdf"), chunk("c", "chat.pdf"));

    @Test
    void reordersByTheModelsOneBasedRanking() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [3, 1, 2]}", null));

        List<ChunkMatch> ranked = service.reRank("q", chunks);

        assertThat(ranked).extracting(ChunkMatch::id).containsExactly("c", "a", "b");
    }

    @Test
    void dropsIndicesOutOfRangeAndDuplicates() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [2, 9, 2, 0, 1]}", null));

        List<ChunkMatch> ranked = service.reRank("q", chunks);

        assertThat(ranked).extracting(ChunkMatch::id).containsExactly("b", "a");
    }

    @Test
    void keepsOriginalOrderWhenTheModelReturnsGarbage() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("no json here", null));

        assertThat(service.reRank("q", chunks)).extracting(ChunkMatch::id).containsExactly("a", "b", "c");
    }

    @Test
    void keepsOriginalOrderWhenTheCallFails() {
        when(ai.generate(any())).thenThrow(new RuntimeException("down"));

        assertThat(service.reRank("q", chunks)).extracting(ChunkMatch::id).containsExactly("a", "b", "c");
    }

    @Test
    void skipsTheModelEntirelyForAnEmptyList() {
        assertThat(service.reRank("q", List.of())).isEmpty();
        verify(ai, never()).generate(any());
    }

    @Test
    void dedupesByIdBeforeAskingTheModel() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [1, 2]}", null));

        service.reRank("q", List.of(chunk("a", "x"), chunk("a", "x"), chunk("b", "y")));

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).contains("[1]").contains("[2]").doesNotContain("[3]");
    }

    @Test
    void truncatesChunkTextInThePromptTo300Chars() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"ranking\": [1]}", null));
        String longText = "x".repeat(1000);

        service.reRank("q", List.of(new ChunkMatch("a", longText, "s", 1, 0.5)));

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).contains("x".repeat(300) + "...").doesNotContain("x".repeat(301));
    }

    private static ChunkMatch chunk(String id, String source) {
        return new ChunkMatch(id, "text of " + id, source, 1, 0.5);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest=ReRankServiceTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class ReRankService`

- [ ] **Step 3: Write the implementation**

```java
package com.bloom.eventmanager.retrieval.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.bloom.eventmanager.common.ai.JsonExtractor;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Asks Gemini to order candidate chunks by relevance beyond vector similarity.
 * V1's prompt verbatim. Any failure returns the candidates in their incoming
 * (vector-score) order: re-ranking is a refinement, never a dependency.
 */
@Service
public class ReRankService {

    private static final Logger log = LoggerFactory.getLogger(ReRankService.class);
    private static final int PREVIEW_CHARS = 300;

    private final AiClient ai;
    private final JsonMapper json;

    public ReRankService(AiClient ai, JsonMapper json) {
        this.ai = ai;
        this.json = json;
    }

    public List<ChunkMatch> reRank(String query, List<ChunkMatch> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        Map<String, ChunkMatch> byId = new LinkedHashMap<>();
        for (ChunkMatch chunk : chunks) {
            byId.putIfAbsent(chunk.id(), chunk);
        }
        List<ChunkMatch> unique = new ArrayList<>(byId.values());

        try {
            GenerateResponse response = ai.generate(new GenerateRequest(buildPrompt(query, unique)));
            Ranking ranking = json.readValue(JsonExtractor.extract(response.text()), Ranking.class);
            if (ranking.ranking() == null || ranking.ranking().isEmpty()) {
                return unique;
            }

            Set<Integer> seen = new LinkedHashSet<>();
            List<ChunkMatch> ordered = new ArrayList<>();
            for (Integer oneBased : ranking.ranking()) {
                if (oneBased == null || oneBased < 1 || oneBased > unique.size() || !seen.add(oneBased)) {
                    continue;
                }
                ordered.add(unique.get(oneBased - 1));
            }
            return ordered.isEmpty() ? unique : ordered;
        }
        catch (RuntimeException ex) {
            log.warn("Re-ranking failed, using vector order: {}", ex.getMessage());
            return unique;
        }
    }

    private static String buildPrompt(String query, List<ChunkMatch> unique) {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < unique.size(); i++) {
            ChunkMatch chunk = unique.get(i);
            String text = chunk.text() == null ? "" : chunk.text();
            String preview = text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) : text;
            results.append("\n[").append(i + 1).append("]\nSource: ").append(chunk.source())
                    .append("\nContent: ").append(preview).append("...\n");
        }

        return """
                Query: "%s"

                Rank these search results by relevance (1 = most relevant).
                Rules:
                - Prioritize "General Knowledge" (Activities, Guides) if the user is asking for ideas.
                - Prioritize "Client Context" (Briefs) if the user is asking about constraints.
                - Down-rank generic "Chat Logs" unless they contain specific decided ideas.

                Results:
                %s
                Return ONLY a JSON array of numbers representing the ranking order.
                Format: {"ranking": [3, 1, 5, 2, 4]}""".formatted(query, results);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ranking(List<Integer> ranking) {
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest=ReRankServiceTest`
Expected: PASS — 7 tests

- [ ] **Step 5: Commit**

```bash
git add services/retrieval-service
git commit -m "feat(retrieval): AI re-ranking that degrades to vector order"
```

---

### Task 4: The retrieval pipeline

**Files:**
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/domain/RetrievalResult.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/domain/RetrievalService.java`
- Test: `services/retrieval-service/src/test/java/com/bloom/eventmanager/retrieval/domain/RetrievalServiceTest.java`

**Interfaces:**
- Consumes: `AiClient`, `KnowledgeClient`, `QueryVariationService`, `ReRankService`, `RetrievalProperties`
- Produces:
  - `RetrievalResult(List<ChunkMatch> chunks, String originalQuery, SearchVariations variations, int totalRetrieved, boolean aiEnhanced)` — `variations` null when not AI-enhanced
  - `RetrievalService.retrieve(String query, float[] queryEmbedding, Integer topK, Boolean useAi) -> RetrievalResult`

This is `retrieveContext` from V1, decision for decision:

| V1 | V2 |
|---|---|
| `useAI && query` → variations → embed batch → parallel search topK each → flatten | same; the two searches run concurrently on a virtual-thread executor |
| broad == specific → embed only one | same |
| else → single search with `queryEmbedding ?? embed(query)` at `topK * 2` | same |
| dedupe by id, preserve first occurrence | same |
| AI path → re-rank then `slice(0, topK)`; else `slice(0, topK)` | same |
| `generateSearchVariations` failure → `{specific: q, broad: q}` | same, and the failure is not cached (Task 2) |

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.retrieval.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.config.RetrievalProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private static final float[] SPECIFIC_VEC = { 1f, 0f };
    private static final float[] BROAD_VEC = { 0f, 1f };

    private final AiClient ai = mock(AiClient.class);
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final QueryVariationService variations = mock(QueryVariationService.class);
    private final ReRankService reRank = mock(ReRankService.class);
    private final RetrievalService service = new RetrievalService(
            ai, knowledge, variations, reRank, new RetrievalProperties(3));

    @Test
    void aiPathSearchesBothVariationsDedupesReRanksAndTruncates() {
        when(variations.variations("q")).thenReturn(new SearchVariations("q specific", "q broad"));
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q specific", "q broad"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC, BROAD_VEC)));
        when(knowledge.search(argThat(r -> r != null && r.embedding() == SPECIFIC_VEC && r.topK() == 3)))
                .thenReturn(List.of(chunk("a"), chunk("b"), chunk("c")));
        when(knowledge.search(argThat(r -> r != null && r.embedding() == BROAD_VEC && r.topK() == 3)))
                .thenReturn(List.of(chunk("c"), chunk("d"), chunk("e")));
        when(reRank.reRank(eq("q"), any())).thenAnswer(inv -> {
            List<ChunkMatch> in = inv.getArgument(1);
            return in.reversed();
        });

        RetrievalResult result = service.retrieve("q", null, null, true);

        // 6 retrieved, 5 unique (c deduped), reversed by re-rank, cut to topK=3
        assertThat(result.totalRetrieved()).isEqualTo(6);
        assertThat(result.chunks()).extracting(ChunkMatch::id).containsExactly("e", "d", "c");
        assertThat(result.aiEnhanced()).isTrue();
        assertThat(result.variations()).isEqualTo(new SearchVariations("q specific", "q broad"));
        assertThat(result.originalQuery()).isEqualTo("q");
    }

    @Test
    void aiPathEmbedsOnceWhenBroadEqualsSpecific() {
        when(variations.variations("q")).thenReturn(SearchVariations.same("q"));
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(any())).thenReturn(List.of(chunk("a")));
        when(reRank.reRank(eq("q"), any())).thenAnswer(inv -> inv.getArgument(1));

        service.retrieve("q", null, null, true);

        verify(ai).embedBatch(new EmbedBatchRequest(List.of("q")));
        verify(knowledge).search(any());
    }

    @Test
    void aiPathDegradesToTheOriginalQueryWhenVariationsFail() {
        when(variations.variations("q")).thenThrow(new RuntimeException("gemini down"));
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(any())).thenReturn(List.of(chunk("a")));
        when(reRank.reRank(eq("q"), any())).thenAnswer(inv -> inv.getArgument(1));

        RetrievalResult result = service.retrieve("q", null, null, true);

        assertThat(result.variations()).isEqualTo(SearchVariations.same("q"));
        assertThat(result.chunks()).extracting(ChunkMatch::id).containsExactly("a");
    }

    @Test
    void plainPathEmbedsTheQueryAndSearchesDoubleTopK() {
        when(ai.embedBatch(new EmbedBatchRequest(List.of("q"))))
                .thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(argThat(r -> r != null && r.topK() == 6)))
                .thenReturn(List.of(chunk("a"), chunk("b"), chunk("c"), chunk("d")));

        RetrievalResult result = service.retrieve("q", null, null, false);

        assertThat(result.chunks()).extracting(ChunkMatch::id).containsExactly("a", "b", "c");
        assertThat(result.totalRetrieved()).isEqualTo(4);
        assertThat(result.aiEnhanced()).isFalse();
        assertThat(result.variations()).isNull();
        verify(reRank, never()).reRank(any(), any());
        verify(variations, never()).variations(any());
    }

    @Test
    void plainPathUsesACallerSuppliedEmbeddingWithoutCallingAi() {
        float[] supplied = { 0.5f, 0.5f };
        when(knowledge.search(argThat(r -> r != null && r.embedding() == supplied))).thenReturn(List.of(chunk("a")));

        service.retrieve(null, supplied, 5, false);

        verify(ai, never()).embedBatch(any());
    }

    @Test
    void useAiWithoutAQueryFallsToThePlainPath() {
        float[] supplied = { 0.5f, 0.5f };
        when(knowledge.search(any())).thenReturn(List.of(chunk("a")));

        RetrievalResult result = service.retrieve(null, supplied, null, true);

        assertThat(result.aiEnhanced()).isFalse();
        verify(variations, never()).variations(any());
    }

    @Test
    void explicitTopKOverridesTheDefault() {
        when(ai.embedBatch(any())).thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(argThat(r -> r != null && r.topK() == 2))).thenReturn(List.of(chunk("a")));

        service.retrieve("q", null, 1, false);

        verify(knowledge).search(argThat(r -> r != null && r.topK() == 2));
    }

    @Test
    void embeddingFailureIsA502() {
        when(ai.embedBatch(any())).thenThrow(new RuntimeException("ai-service unreachable"));

        assertThatThrownBy(() -> service.retrieve("q", null, null, false))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void knowledgeFailureIsA502() {
        when(ai.embedBatch(any())).thenReturn(new EmbedBatchResponse(List.of(SPECIFIC_VEC)));
        when(knowledge.search(any())).thenThrow(new RuntimeException("knowledge-service unreachable"));

        assertThatThrownBy(() -> service.retrieve("q", null, null, false))
                .isInstanceOf(ExternalServiceException.class);
    }

    private static ChunkMatch chunk(String id) {
        return new ChunkMatch(id, "text " + id, "source.pdf", 1, 0.8);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest=RetrievalServiceTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class RetrievalService`

- [ ] **Step 3: Write the implementation**

`domain/RetrievalResult.java`:

```java
package com.bloom.eventmanager.retrieval.domain;

import java.util.List;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;

/** {@code variations} is null on the plain path. */
public record RetrievalResult(
        List<ChunkMatch> chunks,
        String originalQuery,
        SearchVariations variations,
        int totalRetrieved,
        boolean aiEnhanced) {
}
```

`domain/RetrievalService.java`:

```java
package com.bloom.eventmanager.retrieval.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.config.RetrievalProperties;

/**
 * V1's dual-search strategy, decision for decision.
 *
 * <p>AI path: split the query into specific/broad, embed both, search both
 * concurrently at topK each, dedupe by id, re-rank, cut to topK. Plain path:
 * one embedding (caller-supplied or computed), one search at 2×topK, cut to
 * topK. Every AI refinement degrades to the plain behaviour on failure; only
 * failing to obtain an embedding or reach the index is an error.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final AiClient ai;
    private final KnowledgeClient knowledge;
    private final QueryVariationService variationService;
    private final ReRankService reRankService;
    private final RetrievalProperties properties;
    private final ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public RetrievalService(AiClient ai, KnowledgeClient knowledge, QueryVariationService variationService,
            ReRankService reRankService, RetrievalProperties properties) {
        this.ai = ai;
        this.knowledge = knowledge;
        this.variationService = variationService;
        this.reRankService = reRankService;
        this.properties = properties;
    }

    public RetrievalResult retrieve(String query, float[] queryEmbedding, Integer requestedTopK, Boolean useAi) {
        int topK = requestedTopK == null ? properties.topK() : requestedTopK;
        boolean aiPath = !Boolean.FALSE.equals(useAi) && query != null && !query.isBlank();

        List<ChunkMatch> retrieved;
        SearchVariations variations = null;

        if (aiPath) {
            variations = variationsOrOriginal(query);
            List<String> toEmbed = new ArrayList<>();
            toEmbed.add(variations.specific());
            if (variations.broad() != null && !variations.broad().equals(variations.specific())) {
                toEmbed.add(variations.broad());
            }
            List<float[]> embeddings = embed(toEmbed);
            retrieved = searchAll(embeddings, topK);
        }
        else {
            float[] embedding = queryEmbedding != null ? queryEmbedding : embed(List.of(query)).get(0);
            retrieved = search(embedding, topK * 2);
        }

        List<ChunkMatch> unique = dedupe(retrieved);
        List<ChunkMatch> ordered = aiPath && !unique.isEmpty()
                ? reRankService.reRank(query, unique)
                : unique;
        List<ChunkMatch> finalChunks = ordered.subList(0, Math.min(topK, ordered.size()));

        return new RetrievalResult(List.copyOf(finalChunks), query, variations, retrieved.size(), aiPath);
    }

    private SearchVariations variationsOrOriginal(String query) {
        try {
            return variationService.variations(query);
        }
        catch (RuntimeException ex) {
            log.warn("Query variation generation failed, using original: {}", ex.getMessage());
            return SearchVariations.same(query);
        }
    }

    private List<float[]> embed(List<String> texts) {
        try {
            return ai.embedBatch(new EmbedBatchRequest(texts)).embeddings();
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Could not obtain query embedding from ai-service", ex);
        }
    }

    private List<ChunkMatch> search(float[] embedding, int topK) {
        try {
            return knowledge.search(new ChunkSearchRequest(embedding, topK));
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Vector search failed in knowledge-service", ex);
        }
    }

    /** V1 ran its per-variation searches with Promise.all; virtual threads are the Java spelling. */
    private List<ChunkMatch> searchAll(List<float[]> embeddings, int topK) {
        List<Future<List<ChunkMatch>>> futures = new ArrayList<>();
        for (float[] embedding : embeddings) {
            futures.add(searchExecutor.submit(() -> search(embedding, topK)));
        }
        List<ChunkMatch> all = new ArrayList<>();
        for (Future<List<ChunkMatch>> future : futures) {
            try {
                all.addAll(future.get());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ExternalServiceException("Interrupted while searching", ex);
            }
            catch (ExecutionException ex) {
                if (ex.getCause() instanceof ExternalServiceException external) {
                    throw external;
                }
                throw new ExternalServiceException("Vector search failed in knowledge-service", ex.getCause());
            }
        }
        return all;
    }

    private static List<ChunkMatch> dedupe(List<ChunkMatch> chunks) {
        Map<String, ChunkMatch> byId = new LinkedHashMap<>();
        for (ChunkMatch chunk : chunks) {
            byId.putIfAbsent(chunk.id(), chunk);
        }
        return new ArrayList<>(byId.values());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest=RetrievalServiceTest`
Expected: PASS — 9 tests

- [ ] **Step 5: Commit**

```bash
git add services/retrieval-service
git commit -m "feat(retrieval): dual-search pipeline with graceful degradation"
```

---

### Task 5: REST endpoint, service config, and the gateway route

**Files:**
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/api/dto/RetrieveRequest.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/api/dto/RetrieveResponse.java`
- Create: `services/retrieval-service/src/main/java/com/bloom/eventmanager/retrieval/api/RetrieveController.java`
- Create: `config-repo/retrieval-service.yml`
- Modify: `infrastructure/api-gateway/src/main/resources/application.yml`
- Modify: `infrastructure/api-gateway/src/test/java/com/bloom/eventmanager/gateway/GatewayRouteDefinitionTest.java`
- Test: `services/retrieval-service/src/test/java/com/bloom/eventmanager/retrieval/api/RetrieveControllerTest.java`

**Interfaces:**
- Consumes: `RetrievalService`
- Produces: `POST /api/v1/retrieve` on `retrieval-service`, reachable as `POST /api/v1/rag/retrieve` through the gateway — V1's exact public path and response shape.

- [ ] **Step 1: Write the failing tests**

`RetrieveControllerTest.java`:

```java
package com.bloom.eventmanager.retrieval.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.domain.RetrievalResult;
import com.bloom.eventmanager.retrieval.domain.RetrievalService;
import com.bloom.eventmanager.retrieval.domain.SearchVariations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RetrieveController.class)
class RetrieveControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RetrievalService retrievalService;

    @Test
    void returnsV1sResponseShapeForAnAiEnhancedQuery() throws Exception {
        when(retrievalService.retrieve(eq("team building"), isNull(), eq(5), eq(true)))
                .thenReturn(new RetrievalResult(
                        List.of(new ChunkMatch("c-1", "text", "guide.pdf", 2, 0.87)),
                        "team building", new SearchVariations("specific", "broad"), 9, true));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"team building\", \"topK\": 5, \"useAI\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.chunks[0].id").value("c-1"))
                .andExpect(jsonPath("$.chunks[0].score").value(0.87))
                .andExpect(jsonPath("$.metadata.originalQuery").value("team building"))
                .andExpect(jsonPath("$.metadata.variations.specific").value("specific"))
                .andExpect(jsonPath("$.metadata.variations.broad").value("broad"))
                .andExpect(jsonPath("$.metadata.totalRetrieved").value(9))
                .andExpect(jsonPath("$.metadata.returned").value(1))
                .andExpect(jsonPath("$.metadata.aiEnhanced").value(true));
    }

    @Test
    void omitsVariationsOnThePlainPath() throws Exception {
        when(retrievalService.retrieve(eq("q"), isNull(), isNull(), eq(false)))
                .thenReturn(new RetrievalResult(List.of(), "q", null, 0, false));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"q\", \"useAI\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.variations").doesNotExist())
                .andExpect(jsonPath("$.metadata.aiEnhanced").value(false));
    }

    @Test
    void useAiDefaultsToTrueLikeV1() throws Exception {
        when(retrievalService.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(), "q", null, 0, true));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"q\"}"))
                .andExpect(status().isOk());

        verify(retrievalService).retrieve(eq("q"), isNull(), isNull(), eq(true));
    }

    @Test
    void acceptsACallerSuppliedEmbeddingWithoutAQuery() throws Exception {
        when(retrievalService.retrieve(any(), any(), any(), any()))
                .thenReturn(new RetrievalResult(List.of(), null, null, 0, false));

        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryEmbedding\": [0.1, 0.2], \"useAI\": false}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsARequestWithNeitherQueryNorEmbedding() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topK\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("queryEmbedding must be a non-empty array"));

        verify(retrievalService, never()).retrieve(any(), any(), any(), any());
    }

    @Test
    void rejectsATopKOutOfRange() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"q\", \"topK\": 0}"))
                .andExpect(status().isBadRequest());
    }
}
```

The "neither query nor embedding" message is V1's `ERROR_MESSAGES.INVALID_EMBEDDING` verbatim.

Append to the gateway's `GatewayRouteDefinitionTest.java`:

```java
    @Test
    void routesRetrieveToTheRetrievalService() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "retrieve".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString("lb://retrieval-service");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest=RetrieveControllerTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class RetrieveController`

Run: `./mvnw -q -pl infrastructure/api-gateway test -Dtest=GatewayRouteDefinitionTest`
Expected: FAIL — `routesRetrieveToTheRetrievalService`: expected not null

- [ ] **Step 3: Write the DTOs and controller**

`api/dto/RetrieveRequest.java`:

```java
package com.bloom.eventmanager.retrieval.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * V1's request body. {@code useAI} keeps V1's capitalisation on the wire; the
 * React QueryForm sends it that way.
 */
public record RetrieveRequest(
        String query,
        float[] queryEmbedding,
        @Min(1) @Max(100) Integer topK,
        Boolean useAI) {

    public boolean hasQuery() {
        return query != null && !query.isBlank();
    }

    public boolean hasEmbedding() {
        return queryEmbedding != null && queryEmbedding.length > 0;
    }
}
```

`api/dto/RetrieveResponse.java`:

```java
package com.bloom.eventmanager.retrieval.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.domain.SearchVariations;

/** V1's response, field for field. */
public record RetrieveResponse(String status, List<ChunkMatch> chunks, Metadata metadata) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metadata(
            String originalQuery,
            SearchVariations variations,
            int totalRetrieved,
            int returned,
            boolean aiEnhanced) {
    }
}
```

`api/RetrieveController.java`:

```java
package com.bloom.eventmanager.retrieval.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.retrieval.api.dto.RetrieveRequest;
import com.bloom.eventmanager.retrieval.api.dto.RetrieveResponse;
import com.bloom.eventmanager.retrieval.domain.RetrievalResult;
import com.bloom.eventmanager.retrieval.domain.RetrievalService;

@RestController
@RequestMapping("/api/v1")
public class RetrieveController {

    private final RetrievalService retrievalService;

    public RetrieveController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/retrieve")
    public RetrieveResponse retrieve(@Valid @RequestBody RetrieveRequest request) {
        if (!request.hasQuery() && !request.hasEmbedding()) {
            // V1's validateEmbedding middleware, same message.
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "queryEmbedding must be a non-empty array");
        }

        RetrievalResult result = retrievalService.retrieve(
                request.query(), request.queryEmbedding(), request.topK(), request.useAI());

        return new RetrieveResponse("success", result.chunks(), new RetrieveResponse.Metadata(
                result.originalQuery(),
                result.variations(),
                result.totalRetrieved(),
                result.chunks().size(),
                result.aiEnhanced()));
    }
}
```

- [ ] **Step 4: Write the service config and the gateway route**

`config-repo/retrieval-service.yml`:

```yaml
server:
  port: 8084

bloom:
  retrieval:
    topK: ${RAG_TOP_K:15}
```

In `infrastructure/api-gateway/src/main/resources/application.yml`, add a second entry under `routes:` after `knowledge-ping`:

```yaml
            - id: retrieve
              uri: lb://retrieval-service
              predicates:
                - Path=/api/v1/rag/retrieve
              filters:
                - RewritePath=/api/v1/rag/retrieve,/api/v1/retrieve
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl services/retrieval-service,infrastructure/api-gateway test`
Expected: PASS — retrieval 5 + 2 + 7 + 9 + 6 = 29 tests; gateway 5 tests

- [ ] **Step 6: Commit**

```bash
git add services/retrieval-service config-repo/retrieval-service.yml infrastructure/api-gateway
git commit -m "feat(retrieval): POST /api/v1/retrieve with V1 response shape; gateway route"
```

---

### Task 6: End-to-end parity check against V1

**Files:** none — this task proves the phase exit criterion: `/api/v1/retrieve` output matches V1's for the same query.

- [ ] **Step 1: Full reactor build**

Run: `./mvnw clean install`
Expected: BUILD SUCCESS, ten reactor projects.

- [ ] **Step 2: Start V2**

Environment as in Phase 1 Task 11 (`GEMINI_API_KEY`, `NEO4J_*`, `REDIS_URL`), then in separate terminals from the repository root:

```bash
docker compose -f deploy/docker-compose.yml --profile infra up -d
./mvnw -pl infrastructure/discovery-server spring-boot:run
./mvnw -pl infrastructure/config-server spring-boot:run
./mvnw -pl services/knowledge-service spring-boot:run
./mvnw -pl services/ai-service spring-boot:run
./mvnw -pl services/retrieval-service spring-boot:run
./mvnw -pl infrastructure/api-gateway spring-boot:run
```

Expected: `http://localhost:8761` lists `AI-SERVICE`, `KNOWLEDGE-SERVICE`, `RETRIEVAL-SERVICE`, `API-GATEWAY`.

- [ ] **Step 3: Start V1 on a different port, pointed at the same Neo4j and Redis**

In `E:\Bloom_Event_Manager`:

```bash
PORT=3000 npm run dev
```

V1 requires HTTP Basic auth when `APP_AUTH_USER`/`APP_AUTH_PASSWORD` are set in its `.env`; the `curl` below passes them with `-u`.

- [ ] **Step 4: Deterministic comparison — the plain path**

`useAI: false` has no model in the loop, so V1 and V2 must agree exactly on chunk ids and order.

```bash
BODY='{"query": "team building activities for a non-profit", "topK": 5, "useAI": false}'

curl -s -u "$APP_AUTH_USER:$APP_AUTH_PASSWORD" -X POST http://localhost:3000/api/v1/rag/retrieve \
     -H "Content-Type: application/json" -d "$BODY" | jq -r '.chunks[].id' > v1.txt

curl -s -X POST http://localhost:8080/api/v1/rag/retrieve \
     -H "Content-Type: application/json" -d "$BODY" | jq -r '.chunks[].id' > v2.txt

diff v1.txt v2.txt && echo "IDENTICAL"
```

Expected: `IDENTICAL`. The embedding is served from the shared Redis cache the second time round, so both sides even use the same vector.

If they differ: confirm both point at the same `NEO4J_URI`; confirm V1's `.env` has `GEMINI_EMBEDDING_MODEL=gemini-embedding-001` (a different model gives different vectors and a cache miss on a different key).

- [ ] **Step 5: Statistical comparison — the AI path**

`useAI: true` involves two model calls, so exact order is not expected. Overlap of the returned sets is.

```bash
BODY='{"query": "team building activities for a non-profit", "topK": 10, "useAI": true}'

curl -s -u "$APP_AUTH_USER:$APP_AUTH_PASSWORD" -X POST http://localhost:3000/api/v1/rag/retrieve \
     -H "Content-Type: application/json" -d "$BODY" | jq -r '.chunks[].id' | sort > v1.txt
curl -s -X POST http://localhost:8080/api/v1/rag/retrieve \
     -H "Content-Type: application/json" -d "$BODY" | jq -r '.chunks[].id' | sort > v2.txt

comm -12 v1.txt v2.txt | wc -l
```

Expected: 7 or more of 10 in common. Also inspect V2's `metadata.variations` — both `specific` and `broad` populated and different from each other.

- [ ] **Step 6: Degradation check**

Stop `ai-service`. Repeat the V2 call from Step 4.

Expected: HTTP 502 with `errorCode: EXTERNAL_SERVICE_ERROR` and detail `Could not obtain query embedding from ai-service`. The embedding cache lives inside `ai-service`, so no query is answerable without it — a cached embedding does not help here. Restart `ai-service`, stop `knowledge-service`, call again: 502 with detail `Vector search failed in knowledge-service`. Restart it.

This is the behaviour Phase 5's circuit breakers wrap; the point now is that failures are classified, not swallowed.

- [ ] **Step 7: Record and commit**

Add the Step 4 query and the observed `IDENTICAL` result to the README's Phase verification section:

```bash
git add README.md
git commit -m "docs: Phase 2 retrieval parity verified against V1"
```

---

## Phase 2 Exit Criteria

- [ ] `./mvnw clean install` green, ten reactor projects
- [ ] `RETRIEVAL-SERVICE` registered in Eureka; `POST http://localhost:8080/api/v1/rag/retrieve` answers through the gateway
- [ ] Plain-path (`useAI: false`) chunk ids and order are **identical** to V1's for the same query
- [ ] AI-path result sets overlap V1's by at least 70%, and `metadata.variations` is populated
- [ ] With `ai-service` down, retrieval returns a 502 ProblemDetail, not a 500 or a hang
- [ ] `retrieval-service` holds no datastore connection

## What Phase 2 Does Not Do

- No circuit breakers or Feign fallbacks — Phase 5 wraps the degradation paths built here
- No chat, proposals, ingestion or Drive — Phases 3 and 4
- No authentication on the gateway — Phase 5; V2's gateway is open on localhost during Phases 2–4
- No distributed tracing yet — Phase 5; until then, correlate by timestamps across the four service logs
