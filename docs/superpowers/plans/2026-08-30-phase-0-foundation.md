# Phase 0 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-module Spring Initializr skeleton into a working multi-module Spring Cloud platform where the API gateway routes a request through Eureka to a registered service.

**Architecture:** A Maven aggregator (`bloom-parent`) owns three library modules (`bloom-bom`, `bloom-common`, `bloom-events`), three infrastructure applications (Config Server, Eureka, Gateway), and one minimal business service (`knowledge-service`) that exists in this phase only to prove routing works end to end. Local infrastructure (Neo4j, MongoDB, Redis, Kafka, Zipkin) runs under a Docker Compose `infra` profile. No business logic is written in this phase.

**Tech Stack:** Spring Boot 4.0.8, Spring Cloud 2025.1.3, Java 25, Maven, JUnit 5, AssertJ, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md`

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec.

- **Spring Boot: `4.0.8`.** Not 4.1.1. The existing skeleton is on 4.1.1 and must be downgraded — Spring Cloud 2025.1.3 pins `spring-boot.version` to 4.0.8.
- **Spring Cloud: `2025.1.3`.** Imported as a BOM.
- **Java: `25`.**
- **Group ID: `com.bloom.eventmanager`.** Base package `com.bloom.eventmanager.<module>`. The skeleton's `com.Bloom.Event.Manager` is deleted — capitalized package segments violate Java convention.
- **Project version: `0.0.1-SNAPSHOT`** for every module.
- **Gateway artifact is `spring-cloud-starter-gateway-server-webflux`.** The old `spring-cloud-starter-gateway` does not exist at gateway module version 5.0.3 — verified 404 on Maven Central.
- **Gateway config prefix is `spring.cloud.gateway.server.webflux.*`.** Not the legacy `spring.cloud.gateway.*` — verified against the jar's `spring-configuration-metadata.json`.
- **Boot 4 starter names:** `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-data-mongodb`, `spring-boot-starter-data-redis`.
- **`GET /api/rag/auth/google/callback` must keep working, with query parameters preserved.** That exact URI is registered in Google Cloud Console; breaking it requires an external change nobody can make from this repo.
- **No business logic in Phase 0.** No Neo4j access, no Gemini calls, no Drive calls, no Kafka producers or consumers. Only the event *contracts* are defined.

---

## File Structure

```
pom.xml                                          aggregator; packaging pom; Boot 4.0.8 parent
platform/bloom-bom/pom.xml                       third-party + internal version pins
platform/bloom-common/                           shared error model (RFC 9457 ProblemDetail)
  src/main/java/com/bloom/eventmanager/common/error/
    ErrorCode.java                               enum: code -> HTTP status + title
    BloomException.java                          base runtime exception carrying an ErrorCode
    NotFoundException.java
    ExternalServiceException.java
    GlobalExceptionHandler.java                  @RestControllerAdvice -> ProblemDetail
platform/bloom-events/                           Kafka wire contracts only; no Kafka client code
  src/main/java/com/bloom/eventmanager/events/
    BloomTopics.java                             topic name constants
    DocumentDiscovered.java
    ChunkPayload.java
    ChunkBatchReady.java                         enforces the 20-chunk batch guard
    DocumentIndexed.java
    DocumentDeleted.java
infrastructure/discovery-server/                 Eureka, :8761
infrastructure/config-server/                    Spring Cloud Config, native backend, :8888
infrastructure/api-gateway/                      Gateway + legacy OAuth redirect, :8080
services/knowledge-service/                      Boot app + /api/v1/ping only, :8082
config-repo/
  application.yml                                shared: Eureka client, actuator
  knowledge-service.yml                          per-service config proving Config Server works
deploy/docker-compose.yml                        infra profile: neo4j, mongo, redis, kafka, zipkin
```

**Why `knowledge-service` and not a throwaway `hello-service`:** the phase exit criterion needs a real service registered in Eureka for the gateway to route to. Building the shell of a service the spec already calls for means nothing gets deleted in Phase 1 — Phase 1 adds Neo4j to this module rather than replacing it.

---

### Task 1: Aggregator POM and version baseline

**Files:**
- Modify: `pom.xml` (complete rewrite)
- Create: `platform/bloom-bom/pom.xml`
- Delete: `src/` (the entire `com.Bloom.Event.Manager` skeleton)

**Interfaces:**
- Consumes: nothing
- Produces: parent POM coordinates `com.bloom.eventmanager:bloom-parent:0.0.1-SNAPSHOT` (packaging `pom`), which every later module declares as its `<parent>`; and `com.bloom.eventmanager:bloom-bom:0.0.1-SNAPSHOT`, imported by modules needing third-party pins.

- [x] **Step 1: Confirm the current (wrong) baseline**

Run: `./mvnw -q help:evaluate -Dexpression=project.parent.version -DforceStdout`

Expected: prints `4.1.1` — the version Spring Cloud 2025.1.3 does not support. This is the failing state.

- [x] **Step 2: Replace the root `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.8</version>
        <relativePath/>
    </parent>

    <groupId>com.bloom.eventmanager</groupId>
    <artifactId>bloom-parent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Bloom Event Manager V2</name>
    <description>Spring microservices rebuild of the Bloom Event Manager</description>

    <properties>
        <java.version>25</java.version>
        <spring-cloud.version>2025.1.3</spring-cloud.version>
    </properties>

    <modules>
        <module>platform/bloom-bom</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

Note there is no `<build><plugins>` block: `spring-boot-starter-parent` already provides `pluginManagement` for `spring-boot-maven-plugin`, and declaring it here would try to repackage the `pom` module.

- [x] **Step 3: Create `platform/bloom-bom/pom.xml`**

This module pins versions that Spring's BOMs do not manage. Versions are transcribed from spec section 8; do not change them.

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

    <artifactId>bloom-bom</artifactId>
    <packaging>pom</packaging>
    <name>Bloom BOM</name>

    <properties>
        <spring-ai.version>2.0.1</spring-ai.version>
        <pdfbox.version>3.0.8</pdfbox.version>
        <icu4j.version>78.3</icu4j.version>
        <tess4j.version>5.20.0</tess4j.version>
        <google-drive.version>v3-rev20260823-2.0.0</google-drive.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-events</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.apache.pdfbox</groupId>
                <artifactId>pdfbox</artifactId>
                <version>${pdfbox.version}</version>
            </dependency>
            <dependency>
                <groupId>com.ibm.icu</groupId>
                <artifactId>icu4j</artifactId>
                <version>${icu4j.version}</version>
            </dependency>
            <dependency>
                <groupId>net.sourceforge.tess4j</groupId>
                <artifactId>tess4j</artifactId>
                <version>${tess4j.version}</version>
            </dependency>
            <dependency>
                <groupId>com.google.apis</groupId>
                <artifactId>google-api-services-drive</artifactId>
                <version>${google-drive.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [x] **Step 4: Delete the skeleton source tree**

```bash
rm -rf src
```

The root module is `packaging: pom` and cannot carry sources. `Application.java`, `ApplicationTests.java`, and `application.properties` all reappear in the correct modules in later tasks.

- [x] **Step 5: Verify the baseline is now correct**

Run: `./mvnw -q help:evaluate -Dexpression=project.parent.version -DforceStdout`
Expected: prints `4.0.8`

Run: `./mvnw -q clean install`
Expected: BUILD SUCCESS, two modules reacted (`bloom-parent`, `bloom-bom`)

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "build: multi-module aggregator on Boot 4.0.8 and Spring Cloud 2025.1.3"
```

---

### Task 2: bloom-common — RFC 9457 error model

**Files:**
- Create: `platform/bloom-common/pom.xml`
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/ErrorCode.java`
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/BloomException.java`
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/NotFoundException.java`
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/ExternalServiceException.java`
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/GlobalExceptionHandler.java`
- Test: `platform/bloom-common/src/test/java/com/bloom/eventmanager/common/error/GlobalExceptionHandlerTest.java`
- Modify: `pom.xml` (add `<module>platform/bloom-common</module>`)

**Interfaces:**
- Consumes: `bloom-parent` as parent
- Produces:
  - `ErrorCode` enum with constants `NOT_FOUND`, `VALIDATION_FAILED`, `EXTERNAL_SERVICE_ERROR`, `INTERNAL_ERROR`, and accessors `HttpStatus status()`, `String title()`
  - `BloomException(ErrorCode, String)` and `BloomException(ErrorCode, String, Throwable)`, accessor `ErrorCode errorCode()`
  - `NotFoundException(String message)`
  - `ExternalServiceException(String message, Throwable cause)`
  - `GlobalExceptionHandler` — a `@RestControllerAdvice`; services import it via component scan of `com.bloom.eventmanager.common`

- [x] **Step 1: Write the failing test**

Create `platform/bloom-common/src/test/java/com/bloom/eventmanager/common/error/GlobalExceptionHandlerTest.java`:

```java
package com.bloom.eventmanager.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsNotFoundTo404WithTypeTitleAndErrorCode() {
        ProblemDetail problem = handler.handleBloom(new NotFoundException("chunk 42 not found"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getDetail()).isEqualTo("chunk 42 not found");
        assertThat(problem.getType()).hasToString("https://bloom.eventmanager/problems/not-found");
        assertThat(problem.getProperties()).containsEntry("errorCode", "NOT_FOUND");
    }

    @Test
    void mapsExternalServiceFailureTo502() {
        ProblemDetail problem = handler.handleBloom(
                new ExternalServiceException("gemini unavailable", new RuntimeException("connect timeout")));

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getProperties()).containsEntry("errorCode", "EXTERNAL_SERVICE_ERROR");
    }

    @Test
    void doesNotLeakInternalDetailForUnexpectedExceptions() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("mongodb://user:hunter2@cluster0"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("Unexpected error");
        assertThat(problem.getDetail()).doesNotContain("hunter2");
    }
}
```

The third test is the important one: it pins the rule that an unexpected exception's message never reaches the client, because V1's error paths sometimes stringify raw driver errors that contain connection URIs.

- [x] **Step 2: Create the module POM**

`platform/bloom-common/pom.xml`:

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

    <artifactId>bloom-common</artifactId>
    <name>Bloom Common</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Add `<module>platform/bloom-common</module>` to the root `pom.xml` `<modules>` block.

- [x] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: COMPILATION FAILURE — `cannot find symbol: class GlobalExceptionHandler`

- [x] **Step 4: Write the implementation**

`ErrorCode.java`:

```java
package com.bloom.eventmanager.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource Not Found"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation Failed"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "External Service Error"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Lower-kebab form used as the last segment of the problem type URI. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
```

`BloomException.java`:

```java
package com.bloom.eventmanager.common.error;

public class BloomException extends RuntimeException {

    private final ErrorCode errorCode;

    public BloomException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BloomException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

`NotFoundException.java`:

```java
package com.bloom.eventmanager.common.error;

public class NotFoundException extends BloomException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
```

`ExternalServiceException.java`:

```java
package com.bloom.eventmanager.common.error;

public class ExternalServiceException extends BloomException {

    public ExternalServiceException(String message, Throwable cause) {
        super(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
```

`GlobalExceptionHandler.java`:

```java
package com.bloom.eventmanager.common.error;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_PREFIX = "https://bloom.eventmanager/problems/";

    @ExceptionHandler(BloomException.class)
    public ProblemDetail handleBloom(BloomException ex) {
        return toProblem(ex.errorCode(), ex.getMessage());
    }

    /**
     * Anything not modelled as a BloomException is reported with a fixed detail
     * string. Exception messages from drivers routinely embed connection URIs
     * with credentials, so they must never reach a client.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        return toProblem(ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }

    private ProblemDetail toProblem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setType(URI.create(TYPE_PREFIX + code.slug()));
        problem.setProperty("errorCode", code.name());
        return problem;
    }
}
```

- [x] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: PASS, 3 tests

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(common): RFC 9457 problem detail error model"
```

---

### Task 3: bloom-events — Kafka wire contracts

**Files:**
- Create: `platform/bloom-events/pom.xml`
- Create: `platform/bloom-events/src/main/java/com/bloom/eventmanager/events/BloomTopics.java`
- Create: `platform/bloom-events/src/main/java/com/bloom/eventmanager/events/DocumentDiscovered.java`
- Create: `platform/bloom-events/src/main/java/com/bloom/eventmanager/events/ChunkPayload.java`
- Create: `platform/bloom-events/src/main/java/com/bloom/eventmanager/events/ChunkBatchReady.java`
- Create: `platform/bloom-events/src/main/java/com/bloom/eventmanager/events/DocumentIndexed.java`
- Create: `platform/bloom-events/src/main/java/com/bloom/eventmanager/events/DocumentDeleted.java`
- Test: `platform/bloom-events/src/test/java/com/bloom/eventmanager/events/ChunkBatchReadyTest.java`
- Modify: `pom.xml` (add `<module>platform/bloom-events</module>`)

**Interfaces:**
- Consumes: `bloom-parent` as parent
- Produces:
  - `BloomTopics.DOCUMENT_DISCOVERED`, `.CHUNK_BATCH_READY`, `.DOCUMENT_INDEXED`, `.DOCUMENT_DELETED` — `public static final String`
  - `DocumentDiscovered(String driveFileId, String name, String mimeType, String modifiedTime, String folderPath)`
  - `ChunkPayload(String id, String text, String source, Integer page, float[] embedding)`
  - `ChunkBatchReady(String source, String driveFileId, int seq, boolean last, List<ChunkPayload> chunks)` with `public static final int MAX_CHUNKS_PER_BATCH = 20`
  - `DocumentIndexed(String source, String driveFileId, int chunkCount, Instant indexedAt)`
  - `DocumentDeleted(String driveFileId, String source)`

These are pure data carriers. This module must not depend on any Kafka client — producers and consumers arrive in Phase 3.

- [x] **Step 1: Write the failing test**

Create `platform/bloom-events/src/test/java/com/bloom/eventmanager/events/ChunkBatchReadyTest.java`:

```java
package com.bloom.eventmanager.events;

import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkBatchReadyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void survivesJsonRoundTripIncludingTheEmbeddingVector() throws Exception {
        ChunkBatchReady event = new ChunkBatchReady(
                "handbook.pdf", "drive-file-1", 0, false,
                List.of(new ChunkPayload("c1", "hello world", "handbook.pdf", 3,
                        new float[] { 0.125f, -0.5f, 0.75f })));

        String json = mapper.writeValueAsString(event);
        ChunkBatchReady back = mapper.readValue(json, ChunkBatchReady.class);

        assertThat(back.source()).isEqualTo("handbook.pdf");
        assertThat(back.driveFileId()).isEqualTo("drive-file-1");
        assertThat(back.seq()).isZero();
        assertThat(back.last()).isFalse();
        assertThat(back.chunks()).hasSize(1);
        assertThat(back.chunks().get(0).page()).isEqualTo(3);
        assertThat(back.chunks().get(0).embedding()).containsExactly(0.125f, -0.5f, 0.75f);
    }

    @Test
    void rejectsBatchesLargerThanTheKafkaSizeGuard() {
        List<ChunkPayload> tooMany = IntStream.rangeClosed(0, ChunkBatchReady.MAX_CHUNKS_PER_BATCH)
                .mapToObj(i -> new ChunkPayload("c" + i, "t", "handbook.pdf", 1, new float[] { 0.1f }))
                .toList();

        assertThatThrownBy(() -> new ChunkBatchReady("handbook.pdf", "drive-file-1", 0, true, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    void exposesTheAgreedTopicNames() {
        assertThat(BloomTopics.DOCUMENT_DISCOVERED).isEqualTo("bloom.documents.discovered");
        assertThat(BloomTopics.CHUNK_BATCH_READY).isEqualTo("bloom.chunks.batch-ready");
        assertThat(BloomTopics.DOCUMENT_INDEXED).isEqualTo("bloom.documents.indexed");
        assertThat(BloomTopics.DOCUMENT_DELETED).isEqualTo("bloom.documents.deleted");
    }
}
```

The size-guard test encodes spec section 6: a 3072-dimension `float[]` is roughly 12 KB, so more than 20 chunks in one message would exceed Kafka's 1 MB default. Enforcing it in the constructor makes the constraint impossible to violate accidentally in Phase 3.

- [x] **Step 2: Create the module POM**

`platform/bloom-events/pom.xml`:

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

    <artifactId>bloom-events</artifactId>
    <name>Bloom Events</name>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Add `<module>platform/bloom-events</module>` to the root `pom.xml`.

- [x] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl platform/bloom-events test`
Expected: COMPILATION FAILURE — `cannot find symbol: class ChunkBatchReady`

- [x] **Step 4: Write the implementation**

`BloomTopics.java`:

```java
package com.bloom.eventmanager.events;

public final class BloomTopics {

    public static final String DOCUMENT_DISCOVERED = "bloom.documents.discovered";
    public static final String CHUNK_BATCH_READY = "bloom.chunks.batch-ready";
    public static final String DOCUMENT_INDEXED = "bloom.documents.indexed";
    public static final String DOCUMENT_DELETED = "bloom.documents.deleted";

    private BloomTopics() {
    }
}
```

`DocumentDiscovered.java`:

```java
package com.bloom.eventmanager.events;

/**
 * Published by storage-service when Drive polling finds a new or modified file.
 * Consumed by ingestion-service.
 */
public record DocumentDiscovered(
        String driveFileId,
        String name,
        String mimeType,
        String modifiedTime,
        String folderPath) {
}
```

`ChunkPayload.java`:

```java
package com.bloom.eventmanager.events;

/**
 * One embedded chunk in flight between ingestion-service and knowledge-service.
 *
 * <p>{@code embedding} is a raw {@code float[]} for wire compactness. That makes
 * record equality identity-based for this component — compare embeddings
 * element-wise in tests, never with {@code equals}.
 */
public record ChunkPayload(
        String id,
        String text,
        String source,
        Integer page,
        float[] embedding) {
}
```

`ChunkBatchReady.java`:

```java
package com.bloom.eventmanager.events;

import java.util.List;

/**
 * A bounded slice of one document's embedded chunks.
 *
 * <p>Batching is a hard requirement, not an optimisation: a 3072-dimension
 * {@code float[]} serialises to roughly 12 KB, so an unbounded batch would blow
 * past Kafka's 1 MB default message limit. The producer emits batches in
 * sequence and marks the final one with {@code last = true}.
 */
public record ChunkBatchReady(
        String source,
        String driveFileId,
        int seq,
        boolean last,
        List<ChunkPayload> chunks) {

    public static final int MAX_CHUNKS_PER_BATCH = 20;

    public ChunkBatchReady {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
        if (chunks.size() > MAX_CHUNKS_PER_BATCH) {
            throw new IllegalArgumentException(
                    "batch holds " + chunks.size() + " chunks; maximum is " + MAX_CHUNKS_PER_BATCH);
        }
    }
}
```

`DocumentIndexed.java`:

```java
package com.bloom.eventmanager.events;

import java.time.Instant;

/** Published by knowledge-service once every batch for a document is stored. */
public record DocumentIndexed(
        String source,
        String driveFileId,
        int chunkCount,
        Instant indexedAt) {
}
```

`DocumentDeleted.java`:

```java
package com.bloom.eventmanager.events;

/** Published by storage-service when a file disappears from Drive. */
public record DocumentDeleted(
        String driveFileId,
        String source) {
}
```

- [x] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl platform/bloom-events test`
Expected: PASS, 3 tests

If the round-trip test fails on the `last` property, Jackson has serialised the `boolean last` component as `"last"` — which is what the test asserts. Do not rename the component to `isLast`; that would produce a `"isLast"` JSON key and an awkward accessor.

- [x] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(events): Kafka event contracts with chunk batch size guard"
```

---

### Task 4: Eureka discovery server

**Files:**
- Create: `infrastructure/discovery-server/pom.xml`
- Create: `infrastructure/discovery-server/src/main/java/com/bloom/eventmanager/discovery/DiscoveryServerApplication.java`
- Create: `infrastructure/discovery-server/src/main/resources/application.yml`
- Test: `infrastructure/discovery-server/src/test/java/com/bloom/eventmanager/discovery/DiscoveryServerApplicationTests.java`
- Modify: `pom.xml` (add `<module>infrastructure/discovery-server</module>`)

**Interfaces:**
- Consumes: `bloom-parent` as parent
- Produces: a Eureka registry at `http://localhost:8761/eureka/`. Every later module registers against `${EUREKA_URL:http://localhost:8761/eureka/}`.

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void exposesEurekaRegistryEndpoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/eureka/apps", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void reportsHealthUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 2: Create the module POM**

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

    <artifactId>discovery-server</artifactId>
    <name>Discovery Server</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
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

Add `<module>infrastructure/discovery-server</module>` to the root `pom.xml`.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl infrastructure/discovery-server test`
Expected: COMPILATION FAILURE — `cannot find symbol: class DiscoveryServerApplication`

- [ ] **Step 4: Write the implementation**

`DiscoveryServerApplication.java`:

```java
package com.bloom.eventmanager.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
```

`application.yml`:

```yaml
server:
  port: 8761

spring:
  application:
    name: discovery-server

# The registry does not register with itself, and has no peer to replicate to
# in a single-node local topology.
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
  instance:
    hostname: ${EUREKA_HOSTNAME:localhost}
  server:
    # Self-preservation makes a single-node dev registry hold on to instances
    # long after they are gone, which is confusing while developing.
    enable-self-preservation: false

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl infrastructure/discovery-server test`
Expected: PASS, 2 tests

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(discovery): Eureka registry on 8761"
```

---

### Task 5: Config Server and config-repo

**Files:**
- Create: `infrastructure/config-server/pom.xml`
- Create: `infrastructure/config-server/src/main/java/com/bloom/eventmanager/config/ConfigServerApplication.java`
- Create: `infrastructure/config-server/src/main/resources/application.yml`
- Create: `infrastructure/config-server/src/test/resources/test-config-repo/knowledge-service.yml`
- Create: `config-repo/application.yml`
- Create: `config-repo/knowledge-service.yml`
- Test: `infrastructure/config-server/src/test/java/com/bloom/eventmanager/config/ConfigServerApplicationTests.java`
- Modify: `pom.xml` (add `<module>infrastructure/config-server</module>`)

**Interfaces:**
- Consumes: `bloom-parent` as parent
- Produces: a Config Server at `http://localhost:8888` serving `{application}/{profile}`. Clients import it with `spring.config.import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}`.

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=native",
                "spring.cloud.config.server.native.search-locations=classpath:/test-config-repo"
        })
class ConfigServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesPropertiesForARequestedApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/knowledge-service/default", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("bloom.config-server.reachable");
        assertThat(response.getBody()).contains("true");
    }

    @Test
    void returnsAnEmptyPropertySourceListForAnUnknownApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/no-such-service/default", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"name\":\"no-such-service\"");
    }
}
```

Create the test fixture `infrastructure/config-server/src/test/resources/test-config-repo/knowledge-service.yml`:

```yaml
bloom:
  config-server:
    reachable: true
```

- [ ] **Step 2: Create the module POM**

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

    <artifactId>config-server</artifactId>
    <name>Config Server</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
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

Add `<module>infrastructure/config-server</module>` to the root `pom.xml`.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl infrastructure/config-server test`
Expected: COMPILATION FAILURE — `cannot find symbol: class ConfigServerApplication`

- [ ] **Step 4: Write the implementation**

`ConfigServerApplication.java`:

```java
package com.bloom.eventmanager.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

`infrastructure/config-server/src/main/resources/application.yml`:

```yaml
server:
  port: 8888

spring:
  application:
    name: config-server
  profiles:
    # Filesystem backend rather than a git remote: one less moving part to run.
    active: native
  cloud:
    config:
      server:
        native:
          # Default assumes the process is started from the repository root.
          # Docker overrides this with a mounted path.
          search-locations: ${CONFIG_REPO:file:./config-repo}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
```

`config-repo/application.yml` — shared by every client:

```yaml
# Applied to every service that imports this Config Server.
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
  instance:
    prefer-ip-address: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
```

`config-repo/knowledge-service.yml`:

```yaml
server:
  port: 8082

bloom:
  config-server:
    reachable: true
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl infrastructure/config-server test`
Expected: PASS, 2 tests

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(config): Config Server with native config-repo backend"
```

---

### Task 6: knowledge-service skeleton

**Files:**
- Create: `services/knowledge-service/pom.xml`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/KnowledgeServiceApplication.java`
- Create: `services/knowledge-service/src/main/java/com/bloom/eventmanager/knowledge/api/PingController.java`
- Create: `services/knowledge-service/src/main/resources/application.yml`
- Test: `services/knowledge-service/src/test/java/com/bloom/eventmanager/knowledge/api/PingControllerTest.java`
- Modify: `pom.xml` (add `<module>services/knowledge-service</module>`)

**Interfaces:**
- Consumes: `bloom-parent` as parent; `bloom-common` for the error model; the Config Server and Eureka from Tasks 4 and 5
- Produces: a Eureka-registered service named `knowledge-service` on port 8082, exposing `GET /api/v1/ping` returning `{"service":"knowledge-service","status":"ok"}`. Task 7's gateway route targets `lb://knowledge-service`.

`/api/v1/ping` is not in the spec's `knowledge-service` contract. It is a permanent routing smoke endpoint, deliberately kept so later phases can verify gateway wiring without invoking real business logic. Phase 1 adds the Neo4j endpoints alongside it.

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.knowledge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PingController.class)
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void identifiesItselfByServiceName() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("knowledge-service"))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
```

- [ ] **Step 2: Create the module POM**

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

    <artifactId>knowledge-service</artifactId>
    <name>Knowledge Service</name>

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
            <artifactId>spring-boot-starter-actuator</artifactId>
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

Add `<module>services/knowledge-service</module>` to the root `pom.xml`.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl services/knowledge-service test`
Expected: COMPILATION FAILURE — `cannot find symbol: class PingController`

- [ ] **Step 4: Write the implementation**

`KnowledgeServiceApplication.java`:

```java
package com.bloom.eventmanager.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Scans com.bloom.eventmanager so the shared GlobalExceptionHandler in
 * bloom-common is picked up alongside this service's own components.
 */
@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
public class KnowledgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeServiceApplication.class, args);
    }
}
```

`PingController.java`:

```java
package com.bloom.eventmanager.knowledge.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routing smoke endpoint. Deliberately has no dependencies, so a failure here
 * always means the gateway or the registry is misconfigured, never that a
 * datastore is down.
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("service", "knowledge-service", "status", "ok");
    }
}
```

`services/knowledge-service/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: knowledge-service
  config:
    # 'optional:' keeps the service startable when the Config Server is down,
    # which is what lets @WebMvcTest and @SpringBootTest run without it.
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}

server:
  port: 8082
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl services/knowledge-service test`
Expected: PASS, 1 test

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(knowledge): service skeleton with ping endpoint and Eureka registration"
```

---

### Task 7: API Gateway with routing and the legacy OAuth shim

**Files:**
- Create: `infrastructure/api-gateway/pom.xml`
- Create: `infrastructure/api-gateway/src/main/java/com/bloom/eventmanager/gateway/ApiGatewayApplication.java`
- Create: `infrastructure/api-gateway/src/main/java/com/bloom/eventmanager/gateway/LegacyOAuthRedirectController.java`
- Create: `infrastructure/api-gateway/src/main/resources/application.yml`
- Test: `infrastructure/api-gateway/src/test/java/com/bloom/eventmanager/gateway/LegacyOAuthRedirectControllerTest.java`
- Test: `infrastructure/api-gateway/src/test/java/com/bloom/eventmanager/gateway/GatewayRouteDefinitionTest.java`
- Modify: `pom.xml` (add `<module>infrastructure/api-gateway</module>`)

**Interfaces:**
- Consumes: `knowledge-service` registered in Eureka (Task 6), Eureka (Task 4)
- Produces: the single public entry point on port 8080. Route `knowledge-ping` maps `GET /api/v1/rag/ping` to `lb://knowledge-service` path `/api/v1/ping`. `GET /api/rag/auth/google/callback` returns 307 to `/api/v1/rag/sync/auth/callback` with the query string preserved.

Routes live in this module's `application.yml` for Phase 0 rather than in `config-repo`, so the route tests below can run without a Config Server. Moving them into `config-repo/api-gateway.yml` is a later refinement.

No authentication in this phase — JWT is Phase 5 per the spec.

- [ ] **Step 1: Write the failing tests**

`LegacyOAuthRedirectControllerTest.java`:

```java
package com.bloom.eventmanager.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false"
        })
class LegacyOAuthRedirectControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void redirectsLegacyGoogleCallbackPreservingQueryParameters() {
        webTestClient.get()
                .uri("/api/rag/auth/google/callback?code=abc123&scope=drive.readonly")
                .exchange()
                .expectStatus().isEqualTo(307)
                .expectHeader()
                .valueEquals("Location",
                        "/api/v1/rag/sync/auth/callback?code=abc123&scope=drive.readonly");
    }

    @Test
    void redirectsLegacyGoogleCallbackWithNoQueryString() {
        webTestClient.get()
                .uri("/api/rag/auth/google/callback")
                .exchange()
                .expectStatus().isEqualTo(307)
                .expectHeader().valueEquals("Location", "/api/v1/rag/sync/auth/callback");
    }
}
```

This test protects the URI registered in Google Cloud Console. If it ever fails, Drive OAuth is broken for every user and the fix is not in this repository.

`GatewayRouteDefinitionTest.java`:

```java
package com.bloom.eventmanager.gateway;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class GatewayRouteDefinitionTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void definesTheKnowledgePingRoute() {
        List<String> routeIds = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(routeIds).contains("knowledge-ping");
    }

    @Test
    void routesKnowledgePingThroughLoadBalancedDiscovery() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "knowledge-ping".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString("lb://knowledge-service");
    }
}
```

- [ ] **Step 2: Create the module POM**

Note the artifact name: `spring-cloud-starter-gateway-server-webflux`. The old `spring-cloud-starter-gateway` does not exist at this train.

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

    <artifactId>api-gateway</artifactId>
    <name>API Gateway</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
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
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
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

Add `<module>infrastructure/api-gateway</module>` to the root `pom.xml`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl infrastructure/api-gateway test`
Expected: COMPILATION FAILURE — `cannot find symbol: class ApiGatewayApplication`

- [ ] **Step 4: Write the implementation**

`ApiGatewayApplication.java`:

```java
package com.bloom.eventmanager.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

`LegacyOAuthRedirectController.java`:

```java
package com.bloom.eventmanager.gateway;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preserves V1's Google OAuth callback URI.
 *
 * <p>The path {@code /api/rag/auth/google/callback} is registered in the Google
 * Cloud Console for this project. Changing it requires an external console
 * change, so the gateway keeps answering on it and forwards to the versioned
 * path with the query string — which carries the authorization {@code code} —
 * intact. 307 rather than 302 so the method is preserved.
 *
 * <p>Implemented as a controller rather than a RedirectTo filter because the
 * filter takes an absolute URL, and the redirect target here is relative.
 */
@RestController
public class LegacyOAuthRedirectController {

    private static final String TARGET_PATH = "/api/v1/rag/sync/auth/callback";

    @GetMapping("/api/rag/auth/google/callback")
    public ResponseEntity<Void> redirectLegacyCallback(ServerHttpRequest request) {
        String query = request.getURI().getRawQuery();
        String target = (query == null || query.isBlank()) ? TARGET_PATH : TARGET_PATH + "?" + query;

        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(target))
                .build();
    }
}
```

`infrastructure/api-gateway/src/main/resources/application.yml`:

Note the property prefix `spring.cloud.gateway.server.webflux.*` — the legacy `spring.cloud.gateway.routes` prefix silently binds nothing on this train.

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  cloud:
    gateway:
      server:
        webflux:
          discovery:
            locator:
              # Routes are declared explicitly below. Automatic discovery-based
              # routing would also expose every service under its own id.
              enabled: false
          routes:
            - id: knowledge-ping
              uri: lb://knowledge-service
              predicates:
                - Path=/api/v1/rag/ping
              filters:
                - RewritePath=/api/v1/rag/ping,/api/v1/ping

management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl infrastructure/api-gateway test`
Expected: PASS, 4 tests

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(gateway): routing to knowledge-service and legacy OAuth callback shim"
```

---

### Task 8: Docker Compose infrastructure profile

**Files:**
- Create: `deploy/docker-compose.yml`
- Create: `deploy/.env.example`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: local infrastructure on fixed ports — Neo4j `7474`/`7687`, MongoDB `27017`, Redis `6379`, Kafka `9092`, Zipkin `9411`. Phase 1 onward reads these from `config-repo`.

Phase 0 starts these containers and verifies they are healthy. No application connects to them yet.

- [ ] **Step 1: Write the failing check**

Run: `docker compose -f deploy/docker-compose.yml --profile infra config`
Expected: FAIL — `no such file or directory`

- [ ] **Step 2: Write `deploy/docker-compose.yml`**

The Neo4j image tag comment is carried over verbatim in substance from V1's compose file. It is load-bearing: the spec's Phase 1 validates against existing Neo4j data, and Neo4j upgrades its store format in place.

```yaml
# Local infrastructure for Bloom Event Manager V2.
#
#   docker compose -f deploy/docker-compose.yml --profile infra up -d
#
# Secrets come from deploy/.env (gitignored). Nothing sensitive belongs here.

name: bloom-v2

services:
  neo4j:
    # PINNED, not `latest`, on purpose. `latest` moves across major versions on
    # any re-pull, and Neo4j upgrades the store format in place: moving this tag
    # DOWN breaks an existing database, moving it UP migrates it irreversibly.
    # 2026.06.0 matches what V1 runs, which matters because Phase 1 validates
    # against V1's existing data. Change deliberately, with a data backup.
    image: neo4j:2026.06.0
    profiles: ["infra"]
    container_name: bloom-v2-neo4j
    restart: unless-stopped
    ports:
      - "7474:7474"
      - "7687:7687"
    environment:
      NEO4J_AUTH: "${NEO4J_USER:-neo4j}/${NEO4J_PASSWORD:-password}"
    volumes:
      - neo4j-data:/data
      - neo4j-logs:/logs
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O /dev/null http://localhost:7474 || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 30s

  mongodb:
    image: mongo:8.0
    profiles: ["infra"]
    container_name: bloom-v2-mongo
    restart: unless-stopped
    ports:
      - "27017:27017"
    volumes:
      - mongo-data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping')"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

  redis:
    image: redis:7-alpine
    profiles: ["infra"]
    container_name: bloom-v2-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    command:
      - redis-server
      # Bounded memory with LRU eviction is what makes it safe to cache
      # embeddings without a TTL: under pressure Redis discards the coldest
      # entries. Persistence is deliberate for a cache here, because a cold
      # embedding costs a paid Gemini call to regenerate.
      - "--maxmemory"
      - "512mb"
      - "--maxmemory-policy"
      - "allkeys-lru"
      - "--appendonly"
      - "yes"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5
      start_period: 5s

  kafka:
    # KRaft mode: no ZooKeeper container needed.
    image: apache/kafka:3.9.0
    profiles: ["infra"]
    container_name: bloom-v2-kafka
    restart: unless-stopped
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      # Headroom for ChunkBatchReady: 20 chunks x ~12 KB embeddings plus text.
      KAFKA_MESSAGE_MAX_BYTES: 5242880
      KAFKA_REPLICA_FETCH_MAX_BYTES: 5242880
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 10
      start_period: 30s

  zipkin:
    image: openzipkin/zipkin:3
    profiles: ["infra"]
    container_name: bloom-v2-zipkin
    restart: unless-stopped
    ports:
      - "9411:9411"
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O /dev/null http://localhost:9411/health || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 15s

volumes:
  neo4j-data:
  neo4j-logs:
  mongo-data:
  redis-data:
  kafka-data:
```

- [ ] **Step 3: Write `deploy/.env.example`**

Placeholder names only. Never commit real values — spec section 9.1.

```
# Copy to deploy/.env and fill in. deploy/.env is gitignored.
NEO4J_USER=neo4j
NEO4J_PASSWORD=changeme
```

Append to the repository `.gitignore`:

```
deploy/.env
```

- [ ] **Step 4: Validate and start the stack**

Run: `docker compose -f deploy/docker-compose.yml --profile infra config`
Expected: prints the resolved configuration, exit code 0

Run: `docker compose -f deploy/docker-compose.yml --profile infra up -d`
Then: `docker compose -f deploy/docker-compose.yml --profile infra ps`
Expected: all five services eventually report `healthy`. Kafka and Neo4j take the longest; allow 60 seconds.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore(deploy): docker compose infra profile for neo4j, mongo, redis, kafka, zipkin"
```

---

### Task 9: End-to-end wiring verification and README

**Files:**
- Create: `README.md`
- Modify: none

**Interfaces:**
- Consumes: every module from Tasks 1 through 8
- Produces: the Phase 0 exit criterion, demonstrated and documented

- [ ] **Step 1: Full reactor build**

Run: `./mvnw clean install`
Expected: BUILD SUCCESS across all eight reactor projects — `bloom-parent`, `bloom-bom`, `bloom-common`, `bloom-events`, `discovery-server`, `config-server`, `knowledge-service`, `api-gateway` — with all tests green: 3 in `bloom-common`, 3 in `bloom-events`, 2 in `discovery-server`, 2 in `config-server`, 1 in `knowledge-service`, 4 in `api-gateway` — 15 in total.

- [ ] **Step 2: Start the platform in dependency order**

Each command in its own terminal, from the repository root. Wait for each to report started before beginning the next.

```bash
./mvnw -pl infrastructure/discovery-server spring-boot:run
./mvnw -pl infrastructure/config-server spring-boot:run
./mvnw -pl services/knowledge-service spring-boot:run
./mvnw -pl infrastructure/api-gateway spring-boot:run
```

The Config Server must start from the repository root so its default
`search-locations` of `file:./config-repo` resolves.

- [ ] **Step 3: Verify Config Server serves the service config**

Run: `curl -s http://localhost:8888/knowledge-service/default`
Expected: JSON containing `"name":"knowledge-service"` and the property `bloom.config-server.reachable` with value `true`.

- [ ] **Step 4: Verify registration in Eureka**

Run: `curl -s -H "Accept: application/json" http://localhost:8761/eureka/apps`
Expected: JSON containing an application named `KNOWLEDGE-SERVICE` with one instance whose `status` is `UP`.

If the registry is empty, `knowledge-service` did not reach Eureka. Check that `config-repo/application.yml` was served (Step 3) — the Eureka URL comes from there, not from the service's own `application.yml`.

- [ ] **Step 5: Verify the phase exit criterion — gateway routes through Eureka**

Run: `curl -s -i http://localhost:8080/api/v1/rag/ping`

Expected: HTTP 200 with body:

```json
{"service":"knowledge-service","status":"ok"}
```

This single request proves the whole chain: the gateway matched the route, resolved `lb://knowledge-service` through Eureka, rewrote `/api/v1/rag/ping` to `/api/v1/ping`, and got a response from a separately running process.

- [ ] **Step 6: Verify the legacy OAuth callback still redirects**

Run: `curl -s -i "http://localhost:8080/api/rag/auth/google/callback?code=test123&scope=drive"`

Expected: `HTTP/1.1 307 Temporary Redirect` with header
`Location: /api/v1/rag/sync/auth/callback?code=test123&scope=drive`

- [ ] **Step 7: Write `README.md`**

```markdown
# Bloom Event Manager V2

Spring microservices rebuild of the Bloom Event Manager. The V1 monolith
(Express 5 + TypeScript) lives at `../Bloom_Event_Manager` and remains the
reference implementation.

- **Design spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md`
- **Current phase:** Phase 0 — Foundation

## Stack

Spring Boot 4.0.8 · Spring Cloud 2025.1.3 · Java 25 · Maven multi-module

Spring Boot is pinned to 4.0.8 because Spring Cloud 2025.1.3 — the newest
release train — supports exactly that version. Do not bump Boot independently.

## Modules

| Module | Port | Purpose |
|---|---|---|
| `platform/bloom-bom` | — | Third-party and internal version pins |
| `platform/bloom-common` | — | RFC 9457 problem detail error model |
| `platform/bloom-events` | — | Kafka event contracts |
| `infrastructure/discovery-server` | 8761 | Eureka registry |
| `infrastructure/config-server` | 8888 | Config Server, native backend over `config-repo/` |
| `infrastructure/api-gateway` | 8080 | Single public entry point |
| `services/knowledge-service` | 8082 | Skeleton; owns Neo4j from Phase 1 |

## Running locally

Start infrastructure:

```bash
docker compose -f deploy/docker-compose.yml --profile infra up -d
```

Build everything:

```bash
./mvnw clean install
```

Start the platform, in this order, each from the repository root:

```bash
./mvnw -pl infrastructure/discovery-server spring-boot:run
./mvnw -pl infrastructure/config-server spring-boot:run
./mvnw -pl services/knowledge-service spring-boot:run
./mvnw -pl infrastructure/api-gateway spring-boot:run
```

Verify the whole chain:

```bash
curl -s http://localhost:8080/api/v1/rag/ping
# {"service":"knowledge-service","status":"ok"}
```

## Configuration and secrets

All configuration is served by the Config Server from `config-repo/`.
Secrets are `${ENV_VAR}` placeholders resolved from the environment and are
never committed. Copy `deploy/.env.example` to `deploy/.env` for local
infrastructure credentials.
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "docs: README with Phase 0 run instructions"
git push
```

---

## Phase 0 Exit Criteria

All must hold before Phase 1 begins:

- [ ] `./mvnw clean install` succeeds on Boot 4.0.8 with all tests green
- [ ] `docker compose -f deploy/docker-compose.yml --profile infra ps` shows five healthy services
- [ ] `curl http://localhost:8888/knowledge-service/default` returns config from `config-repo/`
- [ ] `knowledge-service` appears `UP` in the Eureka registry
- [ ] `curl http://localhost:8080/api/v1/rag/ping` returns `{"service":"knowledge-service","status":"ok"}`
- [ ] `curl -i "http://localhost:8080/api/rag/auth/google/callback?code=x"` returns 307 preserving `?code=x`
- [ ] No business logic exists: no Neo4j driver, Gemini client, Drive client, or Kafka producer/consumer code

## What Phase 0 Does Not Do

Named so nobody implements them early:

- No Neo4j, MongoDB, or Redis client code — Phase 1
- No Gemini or Spring AI wiring — Phase 1
- No Kafka producers or consumers; only the event record contracts — Phase 3
- No JWT or Spring Security — Phase 5
- No Resilience4j circuit breakers — Phase 5
- No Micrometer or Zipkin tracing wiring (Zipkin runs in Compose but nothing reports to it) — Phase 5
- No frontend changes — Phase 5
