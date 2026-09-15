# Phase 3b — Write Path: Google Drive Sync

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `storage-service` — the sole holder of Google Drive OAuth and the only service that talks to Drive — so that a PDF dropped into the configured Drive folder lands in Neo4j with no manual step. This completes the spec's Phase 3 exit criterion.

**Architecture:** `storage-service` owns Mongo `tokendb` (the OAuth token, in V1's document shape), wraps the Google Drive v3 Java client behind a `DriveClient` port, ports V1's folder conventions and change detection, and runs the auto-sync poll. Instead of ingesting inline as V1's `syncController.ts` did, sync publishes `DocumentDiscovered` / `DocumentDeleted` events; it consumes `DocumentIndexed` to record each file's `modifiedTime` in `knowledge-service`, which is where change detection reads it back. Bloom folder conventions (company folders, chat backups) are exposed as small REST endpoints for Phase 4.

**Tech Stack:** Spring Boot 4.0.8, Spring Data MongoDB, Spring Kafka 4.0.7, google-api-services-drive v3-rev20260823-2.0.0 on google-api-client 2.9.1, Caffeine, Testcontainers 2.0.5 (`testcontainers-mongodb`, `testcontainers-kafka`), JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` (sections 5.2 storage-service, 5.3, 6, 13)

**Prerequisite:** Phase 3a complete.

## Global Constraints

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.**
- **Google client:** `com.google.apis:google-api-services-drive` (pinned in `bloom-bom`) plus `com.google.api-client:google-api-client:2.9.1` (add the pin to `bloom-bom`). Verified classes: `GoogleAuthorizationCodeRequestUrl`, `GoogleAuthorizationCodeTokenRequest`, `GoogleTokenResponse`, `GoogleOAuthConstants` (google-api-client); `Credential`, `BearerToken`, `ClientParametersAuthentication`, `CredentialRefreshListener` (google-oauth-client, transitive); `GsonFactory` (google-http-client-gson, transitive); `NetHttpTransport`.
- **Token document is V1's shape**, collection `drivetokens`: `{accountId, token: {access_token, refresh_token, scope, token_type, expiry_date}, createdAt, updatedAt}`. A V2 deployment pointed at V1's database reuses the existing token. Database name `tokendb` per the spec; override `MONGO_TOKEN_DB` to read V1's.
- **Testcontainers 2.0.5:** `org.testcontainers:testcontainers-mongodb` → `org.testcontainers.mongodb.MongoDBContainer` (the class Boot 4.0.8's factory binds); Kafka as in Phase 3a.
- **`modifiedTime` strings are RFC 3339 as Drive returns them** (`DateTime.toStringRfc3339()`), compared as strings — exactly what V1 did. Do not parse them.
- **Folder-name sanitising keeps whitespace.** V1's `sanitizeFolderName` had `.replace(/\\s+/g, "_")` — a double-escaped regex that matched a literal backslash-s and therefore never fired. Existing Drive folders are named `Acme Corp`, not `Acme_Corp`; replacing spaces now would create duplicate folders. Only `<>:"/\|?*` are removed.
- **OAuth redirect URI:** `http://localhost:8080/api/rag/auth/google/callback` — the gateway's legacy shim. **This URI must be added as an additional authorised redirect URI in Google Cloud Console** (V1 registered port 3000). Additive; the existing entries stay.
- **`GET …/auth?force=true` returns JSON `{status: "pending", authUrl}` rather than a 302.** Phase 5 puts a JWT on every browser request and a bare navigation cannot carry one, so the SPA will fetch the URL and navigate itself. Recorded here so Phase 5 does not have to change storage-service.
- **After the OAuth callback the browser is redirected to `${bloom.storage.frontend-url}/?auth=success|error`.** Until Phase 5 there is no frontend at that URL; the redirect landing on nothing is expected.
- **Commit with explicit paths, never `git add -A`.**

---

## File Structure

```
platform/bloom-bom/pom.xml                                  MODIFY: google-api-client pin

services/storage-service/
  pom.xml
  src/main/resources/application.yml
  .../storage/StorageServiceApplication.java
  .../storage/config/StorageProperties.java
  .../storage/config/KafkaConfig.java
  .../storage/client/KnowledgeClient.java (+ dto DriveFileMetadata, DriveFileUpsert)
  .../storage/oauth/OAuthClientCredentials.java, CredentialsResolver.java,
                    StoredToken.java, TokenStore.java, DriveAuthManager.java,
                    DriveNotAuthenticatedException.java
  .../storage/mongo/DriveTokenDocument.java, DriveTokenRepository.java, MongoTokenStore.java
  .../storage/drive/StorageFile.java, StorageFileInfo.java, UploadResult.java,
                    DriveClient.java, GoogleDriveClient.java
  .../storage/sync/ChangeSet.java, ChangeDetector.java, SyncState.java, SyncSummary.java,
                   DriveSyncService.java, AutoSyncScheduler.java, DriveStartup.java
  .../storage/conventions/CompanyFolder.java, NamedFolderUpload.java, DriveFolderConventions.java
  .../storage/kafka/EventPublisher.java, DocumentIndexedListener.java
  .../storage/api/AuthController.java, SyncController.java, FileController.java (+ dto)
  test: oauth/CredentialsResolverTest, oauth/DriveAuthManagerTest,
        mongo/MongoTokenStoreIntegrationTests, sync/ChangeDetectorTest,
        conventions/DriveFolderConventionsTest, sync/DriveSyncServiceTest,
        kafka/DocumentIndexedListenerIntegrationTests,
        api/SyncControllerTest, api/FileControllerTest
config-repo/storage-service.yml
infrastructure/api-gateway/.../application.yml              MODIFY: sync routes
infrastructure/api-gateway/.../GatewayRouteDefinitionTest.java  MODIFY
pom.xml                                                     MODIFY: module
```

---

### Task 1: Module, properties, credentials, token store

**Files:**
- Modify: `platform/bloom-bom/pom.xml`
- Create: `services/storage-service/pom.xml`, `src/main/resources/application.yml`
- Create: `.../storage/StorageServiceApplication.java`, `.../config/StorageProperties.java`
- Create: `.../oauth/OAuthClientCredentials.java`, `CredentialsResolver.java`, `StoredToken.java`, `TokenStore.java`
- Create: `.../mongo/DriveTokenDocument.java`, `DriveTokenRepository.java`, `MongoTokenStore.java`
- Test: `.../oauth/CredentialsResolverTest.java`, `.../mongo/MongoTokenStoreIntegrationTests.java`
- Modify: `pom.xml` (module)

**Interfaces:**
- Produces:
  - `StorageProperties(String clientId, String clientSecret, String credentialsPath, String redirectUri, String rootFolderId, long syncIntervalMs, String frontendUrl, List<String> scopes, String accountId)`
  - `OAuthClientCredentials(String clientId, String clientSecret)`; `CredentialsResolver.resolve() -> Optional<OAuthClientCredentials>` (env first, then V1's `client_secret.json`; empty = Drive not configured, which is normal)
  - `StoredToken(String accessToken, String refreshToken, String scope, String tokenType, Long expiryDate)`
  - `TokenStore { Optional<StoredToken> load(String accountId); void save(String accountId, StoredToken token); void clear(String accountId); }`

- [ ] **Step 1: Write the failing tests**

`CredentialsResolverTest.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.storage.config.StorageProperties;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsResolverTest {

    @TempDir
    Path dir;

    @Test
    void prefersExplicitClientIdAndSecret() {
        CredentialsResolver resolver = new CredentialsResolver(props("id-1", "secret-1", dir.resolve("missing.json")), JsonMapper.builder().build());

        assertThat(resolver.resolve()).contains(new OAuthClientCredentials("id-1", "secret-1"));
    }

    @Test
    void readsV1sClientSecretJsonWebBlock() throws IOException {
        Path file = dir.resolve("client_secret.json");
        Files.writeString(file, "{\"web\": {\"client_id\": \"web-id\", \"client_secret\": \"web-secret\", \"redirect_uris\": []}}");

        CredentialsResolver resolver = new CredentialsResolver(props("", "", file), JsonMapper.builder().build());

        assertThat(resolver.resolve()).contains(new OAuthClientCredentials("web-id", "web-secret"));
    }

    @Test
    void acceptsTheInstalledBlockToo() throws IOException {
        Path file = dir.resolve("client_secret.json");
        Files.writeString(file, "{\"installed\": {\"client_id\": \"i-id\", \"client_secret\": \"i-secret\"}}");

        assertThat(new CredentialsResolver(props("", "", file), JsonMapper.builder().build()).resolve())
                .contains(new OAuthClientCredentials("i-id", "i-secret"));
    }

    @Test
    void aMissingFileMeansNotConfiguredNotAnError() {
        assertThat(new CredentialsResolver(props("", "", dir.resolve("nope.json")), JsonMapper.builder().build()).resolve())
                .isEmpty();
    }

    @Test
    void aFileWithoutIdOrSecretMeansNotConfigured() throws IOException {
        Path file = dir.resolve("client_secret.json");
        Files.writeString(file, "{\"web\": {\"client_id\": \"only-id\"}}");

        assertThat(new CredentialsResolver(props("", "", file), JsonMapper.builder().build()).resolve()).isEmpty();
    }

    private static StorageProperties props(String id, String secret, Path credentialsPath) {
        return new StorageProperties(id, secret, credentialsPath.toString(),
                "http://localhost:8080/api/rag/auth/google/callback", "root", 300_000L,
                "http://localhost:5173", List.of("https://www.googleapis.com/auth/drive.file"), "default");
    }
}
```

`MongoTokenStoreIntegrationTests.java`:

```java
package com.bloom.eventmanager.storage.mongo;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.bloom.eventmanager.storage.oauth.StoredToken;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.data.mongodb.auto-index-creation=true"
})
class MongoTokenStoreIntegrationTests {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0");

    @Autowired
    private MongoTokenStore store;

    @Autowired
    private MongoTemplate template;

    @BeforeEach
    void clean() {
        template.getCollection("drivetokens").drop();
    }

    @Test
    void roundTripsATokenInV1sDocumentShape() {
        store.save("default", new StoredToken("access", "refresh", "https://www.googleapis.com/auth/drive.file", "Bearer", 1_800_000_000_000L));

        assertThat(store.load("default")).contains(
                new StoredToken("access", "refresh", "https://www.googleapis.com/auth/drive.file", "Bearer", 1_800_000_000_000L));

        Document raw = template.getCollection("drivetokens").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("accountId")).isEqualTo("default");
        Document token = raw.get("token", Document.class);
        assertThat(token.getString("access_token")).isEqualTo("access");
        assertThat(token.getString("refresh_token")).isEqualTo("refresh");
        assertThat(token.getLong("expiry_date")).isEqualTo(1_800_000_000_000L);
    }

    @Test
    void savingAgainUpdatesRatherThanDuplicates() {
        store.save("default", new StoredToken("a1", "r1", null, "Bearer", 1L));
        store.save("default", new StoredToken("a2", "r2", null, "Bearer", 2L));

        assertThat(template.getCollection("drivetokens").countDocuments()).isEqualTo(1);
        assertThat(store.load("default").orElseThrow().accessToken()).isEqualTo("a2");
    }

    @Test
    void loadsATokenWrittenByV1() {
        template.getCollection("drivetokens").insertOne(new Document("accountId", "default")
                .append("token", new Document("access_token", "v1-access").append("refresh_token", "v1-refresh")
                        .append("scope", "s").append("token_type", "Bearer").append("expiry_date", 42L)));

        assertThat(store.load("default")).contains(new StoredToken("v1-access", "v1-refresh", "s", "Bearer", 42L));
    }

    @Test
    void clearRemovesTheToken() {
        store.save("default", new StoredToken("a", "r", null, "Bearer", 1L));

        store.clear("default");

        assertThat(store.load("default")).isEmpty();
    }
}
```

- [ ] **Step 2: Create the module and pins**

Add to `platform/bloom-bom/pom.xml` `<properties>`: `<google-api-client.version>2.9.1</google-api-client.version>`; and to its `<dependencyManagement>`:

```xml
            <dependency>
                <groupId>com.google.api-client</groupId>
                <artifactId>google-api-client</artifactId>
                <version>${google-api-client.version}</version>
            </dependency>
```

`services/storage-service/pom.xml`:

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

    <artifactId>storage-service</artifactId>
    <name>Storage Service</name>

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
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-kafka</artifactId>
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
            <groupId>com.google.apis</groupId>
            <artifactId>google-api-services-drive</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.api-client</groupId>
            <artifactId>google-api-client</artifactId>
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
            <artifactId>testcontainers-mongodb</artifactId>
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

Add `<module>services/storage-service</module>` to the root `pom.xml` after `services/ingestion-service`.

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: storage-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  cache:
    cache-names: companyFolders
    caffeine:
      # V1: CACHE_TTL_DRIVE_MS = 5 min, matching the auto-sync interval.
      spec: maximumSize=100,expireAfterWrite=5m

server:
  port: 8086
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest=CredentialsResolverTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class CredentialsResolver`

- [ ] **Step 4: Implement**

`StorageServiceApplication.java`:

```java
package com.bloom.eventmanager.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
@EnableFeignClients(basePackages = "com.bloom.eventmanager.storage.client")
public class StorageServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageServiceApplication.class, args);
    }
}
```

`config/StorageProperties.java`:

```java
package com.bloom.eventmanager.storage.config;

import java.util.List;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Drive settings. Client id/secret may come from the environment (preferred)
 * or from V1's client_secret.json at {@code credentialsPath}. Neither present
 * means Drive is simply not configured — a normal state, not an error.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.storage")
public record StorageProperties(
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("./secrets/client_secret.json") String credentialsPath,
        /** Must be registered in Google Cloud Console. The gateway's legacy shim forwards it. */
        @DefaultValue("http://localhost:8080/api/rag/auth/google/callback") String redirectUri,
        @DefaultValue("") String rootFolderId,
        @DefaultValue("300000") @Min(10_000) long syncIntervalMs,
        @DefaultValue("http://localhost:5173") String frontendUrl,
        @DefaultValue({ "https://www.googleapis.com/auth/drive.readonly",
                "https://www.googleapis.com/auth/drive.file" }) List<String> scopes,
        @DefaultValue("default") String accountId) {
}
```

`oauth/OAuthClientCredentials.java`:

```java
package com.bloom.eventmanager.storage.oauth;

public record OAuthClientCredentials(String clientId, String clientSecret) {
}
```

`oauth/CredentialsResolver.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.storage.config.StorageProperties;

@Component
public class CredentialsResolver {

    private static final Logger log = LoggerFactory.getLogger(CredentialsResolver.class);

    private final StorageProperties properties;
    private final JsonMapper json;

    public CredentialsResolver(StorageProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public Optional<OAuthClientCredentials> resolve() {
        if (hasText(properties.clientId()) && hasText(properties.clientSecret())) {
            return Optional.of(new OAuthClientCredentials(properties.clientId(), properties.clientSecret()));
        }
        return fromFile(Path.of(properties.credentialsPath()));
    }

    @SuppressWarnings("unchecked")
    private Optional<OAuthClientCredentials> fromFile(Path path) {
        String content;
        try {
            content = Files.readString(path);
        }
        catch (NoSuchFileException ex) {
            log.info("Google Drive credentials file not found at {} — Drive integration disabled", path.toAbsolutePath());
            return Optional.empty();
        }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot read Drive credentials at " + path, ex);
        }

        Map<String, Object> root = json.readValue(content, Map.class);
        Object block = root.containsKey("web") ? root.get("web") : root.get("installed");
        if (!(block instanceof Map<?, ?> creds)) {
            log.warn("Drive credentials file at {} has neither 'web' nor 'installed' — Drive integration disabled", path);
            return Optional.empty();
        }
        Object id = creds.get("client_id");
        Object secret = creds.get("client_secret");
        if (!(id instanceof String clientId) || !(secret instanceof String clientSecret) || clientId.isBlank() || clientSecret.isBlank()) {
            log.warn("Drive credentials file at {} is missing client_id/client_secret — Drive integration disabled", path);
            return Optional.empty();
        }
        return Optional.of(new OAuthClientCredentials(clientId, clientSecret));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

`oauth/StoredToken.java`:

```java
package com.bloom.eventmanager.storage.oauth;

/** googleapis' token payload, field for field, so V1's stored tokens load unchanged. */
public record StoredToken(String accessToken, String refreshToken, String scope, String tokenType, Long expiryDate) {
}
```

`oauth/TokenStore.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.util.Optional;

public interface TokenStore {

    Optional<StoredToken> load(String accountId);

    void save(String accountId, StoredToken token);

    void clear(String accountId);
}
```

`mongo/DriveTokenDocument.java`:

```java
package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** V1's Mongoose {@code DriveToken} model: collection "drivetokens", token stored verbatim. */
@Document(collection = "drivetokens")
public class DriveTokenDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String accountId;

    private Map<String, Object> token;

    private Instant createdAt;

    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public Map<String, Object> getToken() { return token; }
    public void setToken(Map<String, Object> token) { this.token = token; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

`mongo/DriveTokenRepository.java`:

```java
package com.bloom.eventmanager.storage.mongo;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DriveTokenRepository extends MongoRepository<DriveTokenDocument, String> {

    Optional<DriveTokenDocument> findByAccountId(String accountId);

    void deleteByAccountId(String accountId);
}
```

`mongo/MongoTokenStore.java`:

```java
package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.oauth.StoredToken;
import com.bloom.eventmanager.storage.oauth.TokenStore;

/**
 * Load swallows failures (startup must not depend on Mongo being up); save
 * does not (a silent save failure would look like success and strand the
 * user unauthenticated on the next restart). Same rule V1 applied.
 */
@Component
public class MongoTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(MongoTokenStore.class);

    private final DriveTokenRepository repository;

    public MongoTokenStore(DriveTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StoredToken> load(String accountId) {
        try {
            return repository.findByAccountId(accountId).map(doc -> fromMap(doc.getToken()));
        }
        catch (RuntimeException ex) {
            log.warn("Could not load Drive token from MongoDB — treating as unauthenticated: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String accountId, StoredToken token) {
        DriveTokenDocument doc = repository.findByAccountId(accountId).orElseGet(() -> {
            DriveTokenDocument fresh = new DriveTokenDocument();
            fresh.setAccountId(accountId);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });
        doc.setToken(toMap(token));
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
    }

    @Override
    public void clear(String accountId) {
        try {
            repository.deleteByAccountId(accountId);
        }
        catch (RuntimeException ex) {
            log.warn("Could not clear Drive token from MongoDB: {}", ex.getMessage());
        }
    }

    static Map<String, Object> toMap(StoredToken token) {
        Map<String, Object> map = new HashMap<>();
        map.put("access_token", token.accessToken());
        map.put("refresh_token", token.refreshToken());
        map.put("scope", token.scope());
        map.put("token_type", token.tokenType());
        map.put("expiry_date", token.expiryDate());
        return map;
    }

    static StoredToken fromMap(Map<String, Object> map) {
        Object expiry = map.get("expiry_date");
        return new StoredToken(
                (String) map.get("access_token"),
                (String) map.get("refresh_token"),
                (String) map.get("scope"),
                (String) map.get("token_type"),
                expiry instanceof Number n ? n.longValue() : null);
    }
}
```

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl services/storage-service test -Dtest='CredentialsResolverTest,MongoTokenStoreIntegrationTests'`
Expected: PASS — 5 + 4 (Mongo tests skip without Docker)

- [ ] **Step 6: Commit**

```bash
git add pom.xml platform/bloom-bom/pom.xml services/storage-service
git commit -m "feat(storage): module, credentials resolution, Mongo token store in V1 shape"
```

---

### Task 2: OAuth lifecycle — DriveAuthManager

**Files:**
- Create: `.../oauth/DriveNotAuthenticatedException.java`, `.../oauth/DriveAuthManager.java`
- Test: `.../oauth/DriveAuthManagerTest.java`

**Interfaces:**
- Produces: `DriveAuthManager` with `loadCredentials()`, `isConfigured()`, `isAuthenticated()`, `authUrl()`, `authenticate(String code)`, `credential() -> com.google.api.client.auth.oauth2.Credential`, `handleAuthError(Throwable)`; `DriveNotAuthenticatedException` → 401 with `authUrl`, `error`, `message` properties (the shape V1's `requireDriveAuth` returned).

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.storage.oauth;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.storage.config.StorageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveAuthManagerTest {

    private final StorageProperties properties = new StorageProperties("cid", "csecret", "",
            "http://localhost:8080/api/rag/auth/google/callback", "root", 300_000L, "http://localhost:5173",
            List.of("https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/drive.file"), "default");
    private final CredentialsResolver resolver = mock(CredentialsResolver.class);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final DriveAuthManager auth = new DriveAuthManager(properties, resolver, tokenStore);

    @Test
    void isUnconfiguredWhenNoCredentialsCanBeResolved() {
        when(resolver.resolve()).thenReturn(Optional.empty());

        auth.loadCredentials();

        assertThat(auth.isConfigured()).isFalse();
        assertThat(auth.isAuthenticated()).isFalse();
        assertThatThrownBy(auth::authUrl).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    @Test
    void restoresAStoredTokenIntoARefreshableCredential() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.of(new StoredToken("acc", "ref", "s", "Bearer", 123L)));

        auth.loadCredentials();

        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.credential().getAccessToken()).isEqualTo("acc");
        assertThat(auth.credential().getRefreshToken()).isEqualTo("ref");
        assertThat(auth.credential().getExpirationTimeMilliseconds()).isEqualTo(123L);
    }

    @Test
    void configuredWithoutATokenIsNotAuthenticated() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.empty());

        auth.loadCredentials();

        assertThat(auth.isConfigured()).isTrue();
        assertThat(auth.isAuthenticated()).isFalse();
        assertThatThrownBy(auth::credential).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    @Test
    void buildsV1sConsentUrl() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.empty());
        auth.loadCredentials();

        String url = auth.authUrl();

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/auth")
                .contains("client_id=cid")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Frag%2Fauth%2Fgoogle%2Fcallback")
                .contains("access_type=offline")
                .contains("prompt=consent")
                .contains("drive.readonly")
                .contains("drive.file");
    }

    @Test
    void invalidGrantClearsTheTokenAndDeauthenticates() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.of(new StoredToken("acc", "ref", "s", "Bearer", 1L)));
        auth.loadCredentials();

        auth.handleAuthError(new RuntimeException("400 Bad Request: {\"error\": \"invalid_grant\"}"));

        assertThat(auth.isAuthenticated()).isFalse();
        verify(tokenStore).clear("default");
    }

    @Test
    void otherErrorsLeaveAuthenticationAlone() {
        when(resolver.resolve()).thenReturn(Optional.of(new OAuthClientCredentials("cid", "csecret")));
        when(tokenStore.load("default")).thenReturn(Optional.of(new StoredToken("acc", "ref", "s", "Bearer", 1L)));
        auth.loadCredentials();

        auth.handleAuthError(new RuntimeException("503 backend error"));

        assertThat(auth.isAuthenticated()).isTrue();
        verify(tokenStore, never()).clear("default");
    }

    @Test
    void notAuthenticatedExceptionCarriesTheReauthUrlForTheFrontend() {
        DriveNotAuthenticatedException ex = new DriveNotAuthenticatedException();

        assertThat(ex.properties()).containsEntry("authUrl", "/api/v1/rag/sync/auth?force=true")
                .containsEntry("error", "Google Drive not authenticated.")
                .containsEntry("message", "Google Drive not authenticated.");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest=DriveAuthManagerTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class DriveAuthManager`

- [ ] **Step 3: Implement**

`oauth/DriveNotAuthenticatedException.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.util.Map;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;

/**
 * 401 with the extra members V1's requireDriveAuth response carried
 * ({@code error}, {@code message}, {@code authUrl}); the React app reads them.
 */
public class DriveNotAuthenticatedException extends BloomException {

    public static final String REAUTH_URL = "/api/v1/rag/sync/auth?force=true";
    private static final String MESSAGE = "Google Drive not authenticated.";

    public DriveNotAuthenticatedException() {
        super(ErrorCode.DRIVE_NOT_AUTHENTICATED, MESSAGE);
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("authUrl", REAUTH_URL, "error", MESSAGE, "message", MESSAGE);
    }
}
```

`oauth/DriveAuthManager.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.io.IOException;
import java.util.Optional;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.CredentialRefreshListener;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleOAuthConstants;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.config.StorageProperties;

/**
 * V1's GoogleDriveAuthManager: owns the OAuth2 lifecycle for one account.
 * The Credential refreshes itself with the stored refresh token; every
 * refreshed access token is written back so a restart picks up the newest.
 */
@Component
public class DriveAuthManager {

    private static final Logger log = LoggerFactory.getLogger(DriveAuthManager.class);

    private final StorageProperties properties;
    private final CredentialsResolver resolver;
    private final TokenStore tokenStore;
    private final NetHttpTransport transport = new NetHttpTransport();
    private final GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    private volatile OAuthClientCredentials client;
    private volatile Credential credential;
    private volatile boolean authenticated;

    public DriveAuthManager(StorageProperties properties, CredentialsResolver resolver, TokenStore tokenStore) {
        this.properties = properties;
        this.resolver = resolver;
        this.tokenStore = tokenStore;
    }

    public void loadCredentials() {
        Optional<OAuthClientCredentials> resolved = resolver.resolve();
        if (resolved.isEmpty()) {
            log.info("Google Drive integration disabled: no client credentials configured");
            return;
        }
        client = resolved.get();
        Optional<StoredToken> token = tokenStore.load(properties.accountId());
        if (token.isPresent()) {
            credential = buildCredential(token.get());
            authenticated = true;
            log.info("Loaded existing Google Drive token");
        }
        else {
            log.info("No existing Google Drive token; authentication required");
        }
    }

    public boolean isConfigured() {
        return client != null;
    }

    public boolean isAuthenticated() {
        return authenticated && credential != null;
    }

    public String authUrl() {
        requireConfigured();
        GoogleAuthorizationCodeRequestUrl url = new GoogleAuthorizationCodeRequestUrl(
                client.clientId(), properties.redirectUri(), properties.scopes());
        url.setAccessType("offline");
        url.set("prompt", "consent");
        return url.build();
    }

    public void authenticate(String code) {
        requireConfigured();
        try {
            GoogleTokenResponse response = new GoogleAuthorizationCodeTokenRequest(transport, jsonFactory,
                    client.clientId(), client.clientSecret(), code, properties.redirectUri()).execute();
            StoredToken token = new StoredToken(response.getAccessToken(), response.getRefreshToken(),
                    response.getScope(), response.getTokenType(), expiryOf(response));
            tokenStore.save(properties.accountId(), token);
            credential = buildCredential(token);
            authenticated = true;
            log.info("Google Drive authentication successful, token saved");
        }
        catch (IOException ex) {
            throw new ExternalServiceException("Google token exchange failed", ex);
        }
    }

    public Credential credential() {
        if (!isAuthenticated()) {
            throw new DriveNotAuthenticatedException();
        }
        return credential;
    }

    /** invalid_grant means the refresh token is dead: drop it so a restart does not resurrect it. */
    public void handleAuthError(Throwable error) {
        if (!isInvalidGrant(error)) {
            return;
        }
        log.warn("Google Drive token invalid or expired; invalidating session");
        authenticated = false;
        tokenStore.clear(properties.accountId());
    }

    static boolean isInvalidGrant(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("invalid_grant")) {
                return true;
            }
        }
        return false;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new DriveNotAuthenticatedException();
        }
    }

    private Credential buildCredential(StoredToken token) {
        Credential built = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .setTokenServerEncodedUrl(GoogleOAuthConstants.TOKEN_SERVER_URL)
                .setClientAuthentication(new ClientParametersAuthentication(client.clientId(), client.clientSecret()))
                .addRefreshListener(new PersistRefreshedToken(token))
                .build();
        built.setAccessToken(token.accessToken());
        built.setRefreshToken(token.refreshToken());
        built.setExpirationTimeMilliseconds(token.expiryDate());
        return built;
    }

    private static Long expiryOf(TokenResponse response) {
        Long expiresIn = response.getExpiresInSeconds();
        return expiresIn == null ? null : System.currentTimeMillis() + expiresIn * 1000;
    }

    /** Writes each refreshed access token back so the stored token never goes stale. */
    private final class PersistRefreshedToken implements CredentialRefreshListener {

        private final StoredToken original;

        PersistRefreshedToken(StoredToken original) {
            this.original = original;
        }

        @Override
        public void onTokenResponse(Credential refreshed, TokenResponse response) {
            String refreshToken = response.getRefreshToken() != null ? response.getRefreshToken() : original.refreshToken();
            tokenStore.save(properties.accountId(), new StoredToken(response.getAccessToken(), refreshToken,
                    original.scope(), response.getTokenType(), expiryOf(response)));
        }

        @Override
        public void onTokenErrorResponse(Credential refreshed, TokenErrorResponse errorResponse) {
            if (errorResponse != null && "invalid_grant".equals(errorResponse.getError())) {
                handleAuthError(new IllegalStateException("invalid_grant"));
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl services/storage-service test -Dtest=DriveAuthManagerTest`
Expected: PASS — 7 tests

- [ ] **Step 5: Commit**

```bash
git add services/storage-service
git commit -m "feat(storage): Drive OAuth lifecycle with refresh persistence and invalid_grant handling"
```

---

### Task 3: Drive client, change detection, folder conventions

**Files:**
- Create: `.../drive/StorageFile.java`, `StorageFileInfo.java`, `UploadResult.java`, `DriveClient.java`, `GoogleDriveClient.java`
- Create: `.../sync/ChangeSet.java`, `ChangeDetector.java`
- Create: `.../conventions/CompanyFolder.java`, `NamedFolderUpload.java`, `DriveFolderConventions.java`
- Test: `.../sync/ChangeDetectorTest.java`, `.../conventions/DriveFolderConventionsTest.java`

**Interfaces:**
- Produces:
  - `StorageFile(String id, String name, String mimeType, String modifiedTime, Long size)`; `StorageFileInfo(String id, String name, String modifiedTime, String checksum)`; `UploadResult(String fileId, String webViewLink)`
  - `DriveClient { listFolders(folderId); listFiles(folderId, mimeTypeFilter); listFilesRecursive(rootFolderId, mimeTypeFilter); fileInfo(fileId); download(fileId) -> byte[]; upload(folderId, fileName, byte[] content, mimeType); createFolder(parentId, name); findOrCreateFolder(parentId, name) }` — V1's `IStorageService` file operations
  - `ChangeSet(List<StorageFile> added, List<StorageFile> modified, List<String> deleted)`; `ChangeDetector.detect(List<StorageFile> current, Map<String, String> known)`
  - `DriveFolderConventions.sanitizeFolderName`, `uploadFileToNamedFolder`, `uploadSessionBackup`, `listCompanyFolders`; `CompanyFolder(String id, String name, int briefCount)`; `NamedFolderUpload(String folderId, String fileId, String webViewLink)`

- [ ] **Step 1: Write the failing tests**

`ChangeDetectorTest.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.storage.drive.StorageFile;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeDetectorTest {

    @Test
    void classifiesAddedModifiedAndDeleted() {
        List<StorageFile> current = List.of(
                file("a", "a.pdf", "t1"),
                file("b", "b.pdf", "t2-new"),
                file("c", "c.pdf", "t3"));
        Map<String, String> known = Map.of("b", "t2-old", "c", "t3", "d", "t4");

        ChangeSet changes = ChangeDetector.detect(current, known);

        assertThat(changes.added()).extracting(StorageFile::id).containsExactly("a");
        assertThat(changes.modified()).extracting(StorageFile::id).containsExactly("b");
        assertThat(changes.deleted()).containsExactly("d");
        assertThat(changes.hasChanges()).isTrue();
    }

    @Test
    void noChangesWhenEverythingMatches() {
        ChangeSet changes = ChangeDetector.detect(List.of(file("a", "a.pdf", "t1")), Map.of("a", "t1"));

        assertThat(changes.hasChanges()).isFalse();
    }

    static StorageFile file(String id, String name, String modifiedTime) {
        return new StorageFile(id, name, "application/pdf", modifiedTime, 10L);
    }
}
```

`DriveFolderConventionsTest.java`:

```java
package com.bloom.eventmanager.storage.conventions;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.drive.UploadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveFolderConventionsTest {

    private final DriveClient drive = mock(DriveClient.class);
    private final DriveFolderConventions conventions = new DriveFolderConventions(drive);

    @Test
    void sanitizeStripsPathCharactersButKeepsSpacesLikeV1Did() {
        assertThat(DriveFolderConventions.sanitizeFolderName("Acme Corp")).isEqualTo("Acme Corp");
        assertThat(DriveFolderConventions.sanitizeFolderName(" A/B:C*D?E\"F<G>H|I\\J ")).isEqualTo("ABCDEFGHIJ");
        assertThat(DriveFolderConventions.sanitizeFolderName("x".repeat(150))).hasSize(100);
    }

    @Test
    void uploadsIntoTheCompanyFolderCreatingItIfNeeded() {
        when(drive.findOrCreateFolder("root", "Acme Corp")).thenReturn("folder-1");
        when(drive.upload("folder-1", "brief.pdf", new byte[] { 1 }, "application/pdf"))
                .thenReturn(new UploadResult("file-1", "https://drive/view"));

        NamedFolderUpload result = conventions.uploadFileToNamedFolder("root", "Acme Corp", "brief.pdf", new byte[] { 1 }, "application/pdf");

        assertThat(result).isEqualTo(new NamedFolderUpload("folder-1", "file-1", "https://drive/view"));
    }

    @Test
    void chatBackupsGoUnderChatsSessionFolderWithV1Names() {
        when(drive.findOrCreateFolder("root", "Chats")).thenReturn("chats");
        when(drive.findOrCreateFolder("chats", "Session_2026-09-15T10-30")).thenReturn("session");
        when(drive.upload(eq("session"), eq("Chat_Backup_session_.pdf"), any(), eq("application/pdf")))
                .thenReturn(new UploadResult("f", null));

        NamedFolderUpload result = conventions.uploadSessionBackup("root", "session_1757932245_abc", new byte[] { 1 },
                Instant.parse("2026-09-15T10:30:45.123Z"));

        assertThat(result.folderId()).isEqualTo("session");
        verify(drive).upload("session", "Chat_Backup_session_.pdf", new byte[] { 1 }, "application/pdf");
    }

    @Test
    void listsCompanyFoldersWithBriefCountsExcludingChats() {
        when(drive.listFolders("root")).thenReturn(List.of(
                new StorageFile("f1", "Acme", "folder", "t", null),
                new StorageFile("f2", "Chats", "folder", "t", null),
                new StorageFile("f3", "Globex", "folder", "t", null)));
        when(drive.listFiles("f1", "application/pdf")).thenReturn(List.of(
                new StorageFile("p1", "a.pdf", "application/pdf", "t", 1L),
                new StorageFile("p2", "b.pdf", "application/pdf", "t", 1L)));
        when(drive.listFiles("f3", "application/pdf")).thenReturn(List.of());

        List<CompanyFolder> companies = conventions.listCompanyFolders("root");

        assertThat(companies).containsExactly(new CompanyFolder("f1", "Acme", 2), new CompanyFolder("f3", "Globex", 0));
    }
}
```

`Chat_Backup_session_.pdf` is correct: V1 took `sessionId.slice(0, 8)` and V1 session ids start with `session_`, so every backup V1 ever wrote is named exactly this. Preserved, because the Drive folder already contains files with that name pattern.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest='ChangeDetectorTest,DriveFolderConventionsTest'`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.storage.drive does not exist`

- [ ] **Step 3: Implement**

`drive/StorageFile.java`, `StorageFileInfo.java`, `UploadResult.java`:

```java
package com.bloom.eventmanager.storage.drive;

public record StorageFile(String id, String name, String mimeType, String modifiedTime, Long size) {
}
```

```java
package com.bloom.eventmanager.storage.drive;

public record StorageFileInfo(String id, String name, String modifiedTime, String checksum) {
}
```

```java
package com.bloom.eventmanager.storage.drive;

public record UploadResult(String fileId, String webViewLink) {
}
```

`drive/DriveClient.java`:

```java
package com.bloom.eventmanager.storage.drive;

import java.util.List;

/** V1's IStorageService file operations. Everything here needs an authenticated Credential. */
public interface DriveClient {

    List<StorageFile> listFolders(String folderId);

    /** {@code mimeTypeFilter} null lists every non-folder file. */
    List<StorageFile> listFiles(String folderId, String mimeTypeFilter);

    /** Names of files in subfolders are prefixed with their relative path, as V1 did. */
    List<StorageFile> listFilesRecursive(String rootFolderId, String mimeTypeFilter);

    StorageFileInfo fileInfo(String fileId);

    byte[] download(String fileId);

    UploadResult upload(String folderId, String fileName, byte[] content, String mimeType);

    String createFolder(String parentId, String name);

    String findOrCreateFolder(String parentId, String name);
}
```

`drive/GoogleDriveClient.java`:

```java
package com.bloom.eventmanager.storage.drive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

/** V1's GoogleDriveFileOperations over the Drive v3 Java client. */
@Component
public class GoogleDriveClient implements DriveClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveClient.class);
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String FILE_FIELDS = "files(id, name, mimeType, modifiedTime, size)";

    private final DriveAuthManager auth;
    private final NetHttpTransport transport = new NetHttpTransport();
    private final GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    public GoogleDriveClient(DriveAuthManager auth) {
        this.auth = auth;
    }

    private Drive drive() {
        return new Drive.Builder(transport, jsonFactory, auth.credential())
                .setApplicationName("Bloom Event Manager")
                .build();
    }

    @Override
    public List<StorageFile> listFolders(String folderId) {
        return call("list folders", () -> toStorageFiles(drive().files().list()
                .setQ("'" + folderId + "' in parents and mimeType='" + FOLDER_MIME + "' and trashed=false")
                .setFields(FILE_FIELDS)
                .setOrderBy("name")
                .execute()));
    }

    @Override
    public List<StorageFile> listFiles(String folderId, String mimeTypeFilter) {
        String mimeClause = mimeTypeFilter != null
                ? " and mimeType='" + mimeTypeFilter + "'"
                : " and mimeType!='" + FOLDER_MIME + "'";
        return call("list files", () -> toStorageFiles(drive().files().list()
                .setQ("'" + folderId + "' in parents and trashed=false" + mimeClause)
                .setFields(FILE_FIELDS)
                .setOrderBy("modifiedTime desc")
                .execute()));
    }

    @Override
    public List<StorageFile> listFilesRecursive(String rootFolderId, String mimeTypeFilter) {
        List<StorageFile> all = new ArrayList<>();
        scan(rootFolderId, "", mimeTypeFilter, all);
        log.info("Drive: found {} files under {} (including subfolders)", all.size(), rootFolderId);
        return all;
    }

    private void scan(String folderId, String path, String mimeTypeFilter, List<StorageFile> into) {
        for (StorageFile file : listFiles(folderId, mimeTypeFilter)) {
            String name = path.isEmpty() ? file.name() : path + "/" + file.name();
            into.add(new StorageFile(file.id(), name, file.mimeType(), file.modifiedTime(), file.size()));
        }
        for (StorageFile sub : listFolders(folderId)) {
            scan(sub.id(), path.isEmpty() ? sub.name() : path + "/" + sub.name(), mimeTypeFilter, into);
        }
    }

    @Override
    public StorageFileInfo fileInfo(String fileId) {
        return call("file info", () -> {
            File file = drive().files().get(fileId).setFields("id, name, modifiedTime, md5Checksum").execute();
            return new StorageFileInfo(file.getId(), file.getName(), rfc3339(file), file.getMd5Checksum());
        });
    }

    @Override
    public byte[] download(String fileId) {
        return call("download", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            drive().files().get(fileId).executeMediaAndDownloadTo(out);
            return out.toByteArray();
        });
    }

    @Override
    public UploadResult upload(String folderId, String fileName, byte[] content, String mimeType) {
        return call("upload", () -> {
            File metadata = new File().setName(fileName).setParents(List.of(folderId));
            File created = drive().files().create(metadata, new ByteArrayContent(mimeType, content))
                    .setFields("id, webViewLink").execute();
            log.info("Uploaded \"{}\" to Drive folder {}", fileName, folderId);
            return new UploadResult(created.getId(), created.getWebViewLink());
        });
    }

    @Override
    public String createFolder(String parentId, String name) {
        return call("create folder", () -> {
            File metadata = new File().setName(name).setMimeType(FOLDER_MIME).setParents(List.of(parentId));
            return drive().files().create(metadata).setFields("id").execute().getId();
        });
    }

    @Override
    public String findOrCreateFolder(String parentId, String name) {
        String escaped = name.replace("'", "\\'");
        FileList existing = call("find folder", () -> drive().files().list()
                .setQ("name='" + escaped + "' and '" + parentId + "' in parents and mimeType='" + FOLDER_MIME + "' and trashed=false")
                .setFields("files(id, name)")
                .execute());
        if (existing.getFiles() != null && !existing.getFiles().isEmpty()) {
            return existing.getFiles().get(0).getId();
        }
        return createFolder(parentId, name);
    }

    private static List<StorageFile> toStorageFiles(FileList list) {
        List<StorageFile> files = new ArrayList<>();
        if (list.getFiles() == null) {
            return files;
        }
        for (File file : list.getFiles()) {
            files.add(new StorageFile(file.getId(), file.getName(), file.getMimeType(), rfc3339(file), file.getSize()));
        }
        return files;
    }

    /** Same RFC 3339 string V1 received from the Node client, so stored values compare equal. */
    private static String rfc3339(File file) {
        return file.getModifiedTime() == null ? null : file.getModifiedTime().toStringRfc3339();
    }

    private <T> T call(String operation, DriveCall<T> call) {
        try {
            return call.run();
        }
        catch (IOException ex) {
            auth.handleAuthError(ex);
            throw new ExternalServiceException("Google Drive " + operation + " failed", ex);
        }
    }

    @FunctionalInterface
    private interface DriveCall<T> {
        T run() throws IOException;
    }
}
```

`sync/ChangeSet.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.util.List;

import com.bloom.eventmanager.storage.drive.StorageFile;

public record ChangeSet(List<StorageFile> added, List<StorageFile> modified, List<String> deleted) {

    public boolean hasChanges() {
        return !added.isEmpty() || !modified.isEmpty() || !deleted.isEmpty();
    }
}
```

`sync/ChangeDetector.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bloom.eventmanager.storage.drive.StorageFile;

/** V1's detectChanges: compare Drive's current listing with id -> modifiedTime we last recorded. */
public final class ChangeDetector {

    private ChangeDetector() {
    }

    public static ChangeSet detect(List<StorageFile> current, Map<String, String> known) {
        List<StorageFile> added = new ArrayList<>();
        List<StorageFile> modified = new ArrayList<>();
        Set<String> currentIds = new HashSet<>();

        for (StorageFile file : current) {
            currentIds.add(file.id());
            String knownModified = known.get(file.id());
            if (knownModified == null) {
                added.add(file);
            }
            else if (!knownModified.equals(file.modifiedTime())) {
                modified.add(file);
            }
        }

        List<String> deleted = new ArrayList<>();
        for (String knownId : known.keySet()) {
            if (!currentIds.contains(knownId)) {
                deleted.add(knownId);
            }
        }
        return new ChangeSet(added, modified, deleted);
    }
}
```

`conventions/CompanyFolder.java`, `NamedFolderUpload.java`:

```java
package com.bloom.eventmanager.storage.conventions;

public record CompanyFolder(String id, String name, int briefCount) {
}
```

```java
package com.bloom.eventmanager.storage.conventions;

public record NamedFolderUpload(String folderId, String fileId, String webViewLink) {
}
```

`conventions/DriveFolderConventions.java`:

```java
package com.bloom.eventmanager.storage.conventions;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.drive.UploadResult;

/**
 * Bloom's folder layout on Drive: one folder per company under the root,
 * chat backups under Chats/Session_&lt;timestamp&gt;/. V1's driveFolderConventions.ts.
 */
@Component
public class DriveFolderConventions {

    static final String CHATS_FOLDER = "Chats";
    private static final String PDF = "application/pdf";

    private final DriveClient drive;

    public DriveFolderConventions(DriveClient drive) {
        this.drive = drive;
    }

    /**
     * Removes characters Drive/Windows reject. Does NOT replace whitespace:
     * V1's intended {@code /\s+/} was written as {@code /\\s+/} and never
     * matched, so existing company folders keep their spaces.
     */
    public static String sanitizeFolderName(String name) {
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*]", "").trim();
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    @CacheEvict(cacheNames = "companyFolders", key = "#rootFolderId")
    public NamedFolderUpload uploadFileToNamedFolder(String rootFolderId, String folderName, String fileName,
            byte[] content, String mimeType) {
        String folderId = drive.findOrCreateFolder(rootFolderId, sanitizeFolderName(folderName));
        UploadResult result = drive.upload(folderId, fileName, content, mimeType);
        return new NamedFolderUpload(folderId, result.fileId(), result.webViewLink());
    }

    public NamedFolderUpload uploadSessionBackup(String rootFolderId, String sessionId, byte[] content, Instant now) {
        String chatsFolderId = drive.findOrCreateFolder(rootFolderId, CHATS_FOLDER);
        // V1: date.toISOString().replace(/[:.]/g, "-").slice(0, 16)  -> "2026-09-15T10-30"
        String sessionFolderName = "Session_" + DateTimeFormatter.ISO_INSTANT.format(now).replaceAll("[:.]", "-").substring(0, 16);
        String sessionFolderId = drive.findOrCreateFolder(chatsFolderId, sessionFolderName);
        String fileName = "Chat_Backup_" + sessionId.substring(0, Math.min(8, sessionId.length())) + ".pdf";
        UploadResult result = drive.upload(sessionFolderId, fileName, content, PDF);
        return new NamedFolderUpload(sessionFolderId, result.fileId(), result.webViewLink());
    }

    /** 1 + N Drive round-trips; cached five minutes and evicted by our own uploads. */
    @Cacheable(cacheNames = "companyFolders", key = "#rootFolderId")
    public List<CompanyFolder> listCompanyFolders(String rootFolderId) {
        List<CompanyFolder> companies = new ArrayList<>();
        for (StorageFile folder : drive.listFolders(rootFolderId)) {
            if (CHATS_FOLDER.equals(folder.name())) {
                continue;
            }
            int briefs = drive.listFiles(folder.id(), PDF).size();
            companies.add(new CompanyFolder(folder.id(), folder.name(), briefs));
        }
        return companies;
    }
}
```

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl services/storage-service test -Dtest='ChangeDetectorTest,DriveFolderConventionsTest'`
Expected: PASS — 2 + 4

- [ ] **Step 5: Commit**

```bash
git add services/storage-service
git commit -m "feat(storage): Drive client, change detection, Bloom folder conventions"
```

---

### Task 4: Sync orchestration, scheduler, Kafka

**Files:**
- Create: `.../client/KnowledgeClient.java`, `.../client/dto/DriveFileMetadata.java`, `.../client/dto/DriveFileUpsert.java`
- Create: `.../config/KafkaConfig.java`, `.../kafka/EventPublisher.java`, `.../kafka/DocumentIndexedListener.java`
- Create: `.../sync/SyncState.java`, `SyncSummary.java`, `DriveSyncService.java`, `AutoSyncScheduler.java`, `DriveStartup.java`
- Test: `.../sync/DriveSyncServiceTest.java`, `.../kafka/DocumentIndexedListenerIntegrationTests.java`

**Interfaces:**
- Consumes: `DriveClient`, `DriveAuthManager`, `KnowledgeClient` (`GET /api/v1/drive-files`, `GET /api/v1/drive-files/{id}`, `PUT /api/v1/drive-files/{id}`), `EventPublisher`
- Produces: `DriveSyncService.syncAll() -> SyncSummary`, `checkForUpdates() -> ChangeSet`, `autoSync()`, `onIndexed(DocumentIndexed)`, `status() -> SyncStatus`; `AutoSyncScheduler.start(long)/stop()/isEnabled()`; `DocumentDiscovered` and `DocumentDeleted` on their topics.

- [ ] **Step 1: Write the failing tests**

`DriveSyncServiceTest.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentDeleted;
import com.bloom.eventmanager.events.DocumentDiscovered;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.storage.client.KnowledgeClient;
import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.drive.StorageFileInfo;
import com.bloom.eventmanager.storage.kafka.EventPublisher;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveSyncServiceTest {

    private final DriveClient drive = mock(DriveClient.class);
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final DriveAuthManager auth = mock(DriveAuthManager.class);
    private final StorageProperties properties = new StorageProperties("", "", "", "http://cb", "root", 300_000L,
            "http://localhost:5173", List.of(), "default");
    private final DriveSyncService service = new DriveSyncService(drive, knowledge, publisher, auth, properties);

    @Test
    void syncAllPublishesDiscoveredForNewAndChangedFilesOnly() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of(
                file("a", "Clients/a.pdf", "t1"), file("b", "b.pdf", "t2"), file("c", "c.pdf", "t3")));
        when(knowledge.allDriveFiles()).thenReturn(Map.of("b", "t2", "c", "old"));

        SyncSummary summary = service.syncAll();

        assertThat(summary.filesQueued()).isEqualTo(2);
        assertThat(summary.filesSeen()).isEqualTo(3);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_DISCOVERED), eq("a"), events.capture());
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_DISCOVERED), eq("c"), events.capture());
        DocumentDiscovered first = (DocumentDiscovered) events.getAllValues().get(0);
        assertThat(first.name()).isEqualTo("Clients/a.pdf");
        assertThat(first.folderPath()).isEqualTo("Clients");
        assertThat(first.modifiedTime()).isEqualTo("t1");
        assertThat(service.status().status()).isEqualTo("idle");
    }

    @Test
    void syncAllRequiresDriveAuthentication() {
        when(auth.isAuthenticated()).thenReturn(false);

        assertThatThrownBy(service::syncAll).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    @Test
    void autoSyncPublishesDeletionsWithTheSourceLookedUpInKnowledge() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of());
        when(knowledge.allDriveFiles()).thenReturn(Map.of("gone", "t"));
        when(knowledge.driveFile("gone")).thenReturn(new DriveFileMetadata("Clients/gone.pdf", "t"));

        service.autoSync();

        verify(publisher).publish(BloomTopics.DOCUMENT_DELETED, "gone", new DocumentDeleted("gone", "Clients/gone.pdf"));
    }

    @Test
    void autoSyncSkipsADeletionWhoseSourceIsUnknown() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of());
        when(knowledge.allDriveFiles()).thenReturn(Map.of("gone", "t"));
        when(knowledge.driveFile("gone")).thenThrow(new FeignException.NotFound("nope",
                Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, null, null), null, Map.of()));

        service.autoSync();

        verify(publisher, never()).publish(eq(BloomTopics.DOCUMENT_DELETED), anyString(), any());
    }

    @Test
    void autoSyncDoesNothingWhenUnauthenticatedOrUnchanged() {
        when(auth.isAuthenticated()).thenReturn(false);
        service.autoSync();
        verify(drive, never()).listFilesRecursive(anyString(), anyString());

        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of(file("a", "a.pdf", "t")));
        when(knowledge.allDriveFiles()).thenReturn(Map.of("a", "t"));
        service.autoSync();
        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void onIndexedRecordsThePendingFilesModifiedTimeInKnowledge() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of(file("a", "a.pdf", "t9")));
        when(knowledge.allDriveFiles()).thenReturn(Map.of());
        service.syncAll();

        service.onIndexed(new DocumentIndexed("a.pdf", "a", 12, Instant.now()));

        verify(knowledge).upsertDriveFile("a", new DriveFileUpsert("a.pdf", "t9"));
    }

    @Test
    void onIndexedFallsBackToDriveWhenNothingIsPendingAfterARestart() {
        when(drive.fileInfo("a")).thenReturn(new StorageFileInfo("a", "a.pdf", "t-from-drive", null));

        service.onIndexed(new DocumentIndexed("a.pdf", "a", 12, Instant.now()));

        verify(knowledge).upsertDriveFile("a", new DriveFileUpsert("a.pdf", "t-from-drive"));
    }

    @Test
    void onIndexedIgnoresLocalIngestsThatHaveNoDriveFileId() {
        service.onIndexed(new DocumentIndexed("local.pdf", null, 3, Instant.now()));

        verify(knowledge, never()).upsertDriveFile(anyString(), any());
    }

    @Test
    void driveFailuresAreRoutedThroughAuthErrorHandling() {
        when(auth.isAuthenticated()).thenReturn(true);
        RuntimeException failure = new RuntimeException("invalid_grant");
        when(drive.listFilesRecursive(anyString(), anyString())).thenThrow(failure);

        assertThatThrownBy(service::syncAll).isSameAs(failure);
        verify(auth).handleAuthError(failure);
        assertThat(service.status().status()).isEqualTo("error");
    }

    @Test
    void aSecondSyncWhileOneRunsIs409() {
        when(auth.isAuthenticated()).thenReturn(true);
        service.stateForTesting().start();

        assertThatThrownBy(service::syncAll).isInstanceOf(BloomException.class).hasMessageContaining("already in progress");
    }

    private static StorageFile file(String id, String name, String modifiedTime) {
        return new StorageFile(id, name, "application/pdf", modifiedTime, 1L);
    }
}
```

`DocumentIndexedListenerIntegrationTests.java`:

```java
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

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.0");

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
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest=DriveSyncServiceTest`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.storage.client does not exist`

- [ ] **Step 3: Implement**

`client/dto/DriveFileMetadata.java`, `client/dto/DriveFileUpsert.java`:

```java
package com.bloom.eventmanager.storage.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriveFileMetadata(String source, String modifiedTime) {
}
```

```java
package com.bloom.eventmanager.storage.client.dto;

public record DriveFileUpsert(String source, String modifiedTime) {
}
```

`client/KnowledgeClient.java`:

```java
package com.bloom.eventmanager.storage.client;

import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;

@FeignClient(name = "knowledge-service")
public interface KnowledgeClient {

    @GetMapping("/api/v1/drive-files")
    Map<String, String> allDriveFiles();

    /** Throws feign.FeignException.NotFound when the id is unknown. */
    @GetMapping("/api/v1/drive-files/{driveFileId}")
    DriveFileMetadata driveFile(@PathVariable("driveFileId") String driveFileId);

    @PutMapping("/api/v1/drive-files/{driveFileId}")
    void upsertDriveFile(@PathVariable("driveFileId") String driveFileId, @RequestBody DriveFileUpsert body);
}
```

`config/KafkaConfig.java`:

```java
package com.bloom.eventmanager.storage.config;

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

    @Bean
    NewTopic documentDiscoveredTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_DISCOVERED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic documentDeletedTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_DELETED).partitions(3).replicas(1).build();
    }

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

`kafka/EventPublisher.java` — identical in shape to knowledge-service's:

```java
package com.bloom.eventmanager.storage.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

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

`kafka/DocumentIndexedListener.java`:

```java
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
```

`sync/SyncState.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** V1's module-level lastSyncTime / syncStatus / lastSyncResult, made thread-safe. */
public class SyncState {

    public record LastResult(int filesQueued, int filesSeen, List<String> errors) {
    }

    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private volatile Instant lastSyncTime;
    private volatile LastResult lastResult;

    /** @return false when a sync is already running */
    public boolean tryStart() {
        return status.compareAndSet("idle", "syncing") || status.compareAndSet("error", "syncing");
    }

    public void start() {
        status.set("syncing");
    }

    public boolean isSyncing() {
        return "syncing".equals(status.get());
    }

    public void finish(int filesQueued, int filesSeen, List<String> errors) {
        lastSyncTime = Instant.now();
        lastResult = new LastResult(filesQueued, filesSeen, List.copyOf(errors));
        status.set("idle");
    }

    public void error() {
        status.set("error");
    }

    public String status() {
        return status.get();
    }

    public Instant lastSyncTime() {
        return lastSyncTime;
    }

    public LastResult lastResult() {
        return lastResult;
    }
}
```

`sync/SyncSummary.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.time.Instant;

public record SyncSummary(int filesQueued, int filesSeen, Instant syncTime) {
}
```

`sync/DriveSyncService.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentDeleted;
import com.bloom.eventmanager.events.DocumentDiscovered;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.storage.client.KnowledgeClient;
import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.kafka.EventPublisher;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

/**
 * V1's syncController without the inline ingestion: changes become events.
 *
 * <p>Change detection compares Drive against the id → modifiedTime map
 * knowledge-service keeps on Document nodes. That map is only updated here,
 * on {@link #onIndexed}, once knowledge-service reports a document stored —
 * so a file that failed to ingest is picked up again on the next poll.
 */
@Service
public class DriveSyncService {

    private static final Logger log = LoggerFactory.getLogger(DriveSyncService.class);
    private static final String PDF = "application/pdf";

    public record SyncStatus(String status, boolean isAuthenticated, Instant lastSyncTime,
            SyncState.LastResult lastResult, String authUrl) {
    }

    private record PendingFile(String name, String modifiedTime) {
    }

    private final DriveClient drive;
    private final KnowledgeClient knowledge;
    private final EventPublisher publisher;
    private final DriveAuthManager auth;
    private final StorageProperties properties;
    private final SyncState state = new SyncState();
    private final Map<String, PendingFile> pending = new ConcurrentHashMap<>();

    public DriveSyncService(DriveClient drive, KnowledgeClient knowledge, EventPublisher publisher,
            DriveAuthManager auth, StorageProperties properties) {
        this.drive = drive;
        this.knowledge = knowledge;
        this.publisher = publisher;
        this.auth = auth;
        this.properties = properties;
    }

    /** Manual full sync: every PDF whose modifiedTime differs from what knowledge-service recorded. */
    public SyncSummary syncAll() {
        requireAuthenticated();
        if (!state.tryStart()) {
            throw new BloomException(ErrorCode.CONFLICT, "Sync already in progress");
        }
        try {
            List<StorageFile> files = drive.listFilesRecursive(properties.rootFolderId(), PDF);
            Map<String, String> known = knowledge.allDriveFiles();
            int queued = 0;
            for (StorageFile file : files) {
                if (!file.modifiedTime().equals(known.get(file.id()))) {
                    publishDiscovered(file);
                    queued++;
                }
            }
            state.finish(queued, files.size(), List.of());
            log.info("Sync: {} of {} Drive files queued for ingestion", queued, files.size());
            return new SyncSummary(queued, files.size(), state.lastSyncTime());
        }
        catch (RuntimeException ex) {
            auth.handleAuthError(ex);
            state.error();
            throw ex;
        }
    }

    public ChangeSet checkForUpdates() {
        requireAuthenticated();
        return ChangeDetector.detect(drive.listFilesRecursive(properties.rootFolderId(), PDF), knowledge.allDriveFiles());
    }

    /** The poll loop body. Never throws — it runs on a scheduler thread. */
    public void autoSync() {
        if (!auth.isAuthenticated()) {
            log.debug("AutoSync skipped: not authenticated");
            return;
        }
        if (state.isSyncing()) {
            log.debug("AutoSync skipped: sync already in progress");
            return;
        }
        try {
            ChangeSet changes = checkForUpdates();
            if (!changes.hasChanges()) {
                log.debug("AutoSync: no changes");
                return;
            }
            log.info("AutoSync: {} added, {} modified, {} deleted",
                    changes.added().size(), changes.modified().size(), changes.deleted().size());
            state.start();
            List<String> errors = new ArrayList<>();
            int queued = 0;
            for (StorageFile file : changes.added()) {
                publishDiscovered(file);
                queued++;
            }
            for (StorageFile file : changes.modified()) {
                publishDiscovered(file);
                queued++;
            }
            for (String deletedId : changes.deleted()) {
                publishDeleted(deletedId, errors);
            }
            state.finish(queued, changes.added().size() + changes.modified().size(), errors);
        }
        catch (RuntimeException ex) {
            auth.handleAuthError(ex);
            state.error();
            log.error("AutoSync failed", ex);
        }
    }

    /** knowledge-service stored the document: record the modifiedTime it was ingested at. */
    public void onIndexed(DocumentIndexed event) {
        if (event.driveFileId() == null) {
            return; // local-directory ingest; nothing to track
        }
        PendingFile pendingFile = pending.remove(event.driveFileId());
        String modifiedTime = pendingFile != null
                ? pendingFile.modifiedTime()
                : drive.fileInfo(event.driveFileId()).modifiedTime(); // restarted mid-flight: ask Drive
        knowledge.upsertDriveFile(event.driveFileId(), new DriveFileUpsert(event.source(), modifiedTime));
        log.info("Recorded Drive metadata for {} ({} chunks)", event.source(), event.chunkCount());
    }

    public SyncStatus status() {
        boolean authenticated = auth.isAuthenticated();
        String authUrl = null;
        if (!authenticated && auth.isConfigured()) {
            authUrl = auth.authUrl();
        }
        return new SyncStatus(state.status(), authenticated, state.lastSyncTime(), state.lastResult(), authUrl);
    }

    SyncState stateForTesting() {
        return state;
    }

    private void publishDiscovered(StorageFile file) {
        pending.put(file.id(), new PendingFile(file.name(), file.modifiedTime()));
        publisher.publish(BloomTopics.DOCUMENT_DISCOVERED, file.id(),
                new DocumentDiscovered(file.id(), file.name(), file.mimeType(), file.modifiedTime(), folderPath(file.name())));
    }

    private void publishDeleted(String driveFileId, List<String> errors) {
        try {
            DriveFileMetadata metadata = knowledge.driveFile(driveFileId);
            publisher.publish(BloomTopics.DOCUMENT_DELETED, driveFileId, new DocumentDeleted(driveFileId, metadata.source()));
        }
        catch (FeignException.NotFound notFound) {
            log.warn("Deleted Drive file {} has no recorded document; nothing to remove", driveFileId);
        }
        catch (RuntimeException ex) {
            log.error("Failed to handle deletion of Drive file {}", driveFileId, ex);
            errors.add("delete " + driveFileId + ": " + ex.getMessage());
        }
    }

    private void requireAuthenticated() {
        if (!auth.isAuthenticated()) {
            throw new DriveNotAuthenticatedException();
        }
    }

    private static String folderPath(String prefixedName) {
        int slash = prefixedName.lastIndexOf('/');
        return slash == -1 ? "" : prefixedName.substring(0, slash);
    }
}
```

`sync/AutoSyncScheduler.java`:

```java
package com.bloom.eventmanager.storage.sync;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** V1's startAutoSync/stopAutoSync: runs immediately, then every interval. */
@Component
public class AutoSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoSyncScheduler.class);

    private final TaskScheduler scheduler;
    private final DriveSyncService sync;
    private ScheduledFuture<?> handle;
    private long intervalMs;

    public AutoSyncScheduler(TaskScheduler scheduler, DriveSyncService sync) {
        this.scheduler = scheduler;
        this.sync = sync;
    }

    public synchronized void start(long intervalMs) {
        if (handle != null) {
            log.info("AutoSync already running");
            return;
        }
        this.intervalMs = intervalMs;
        handle = scheduler.scheduleWithFixedDelay(sync::autoSync, Duration.ofMillis(intervalMs));
        log.info("AutoSync started (interval {}s)", intervalMs / 1000);
    }

    public synchronized void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
            log.info("AutoSync stopped");
        }
    }

    public synchronized boolean isEnabled() {
        return handle != null;
    }

    public synchronized long intervalMs() {
        return intervalMs;
    }
}
```

`sync/DriveStartup.java`:

```java
package com.bloom.eventmanager.storage.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

/** V1's main.ts Drive block: load credentials, start auto-sync if a token is already stored. */
@Component
public class DriveStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DriveStartup.class);

    private final DriveAuthManager auth;
    private final AutoSyncScheduler scheduler;
    private final StorageProperties properties;

    public DriveStartup(DriveAuthManager auth, AutoSyncScheduler scheduler, StorageProperties properties) {
        this.auth = auth;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            auth.loadCredentials();
            if (auth.isAuthenticated()) {
                scheduler.start(properties.syncIntervalMs());
            }
        }
        catch (RuntimeException ex) {
            log.warn("Google Drive initialisation skipped: {}", ex.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl services/storage-service test -Dtest='DriveSyncServiceTest,DocumentIndexedListenerIntegrationTests'`
Expected: PASS — 10 + 1

- [ ] **Step 5: Commit**

```bash
git add services/storage-service
git commit -m "feat(storage): event-driven Drive sync, auto-sync scheduler, indexed metadata recording"
```

---

### Task 5: REST API, config, gateway routes

**Files:**
- Create: `.../api/dto/FileUploadRequest.java`, `FolderRequest.java`, `CompanyFileRequest.java`, `ChatBackupRequest.java`, `AutoSyncRequest.java`
- Create: `.../api/AuthController.java`, `SyncController.java`, `FileController.java`
- Create: `config-repo/storage-service.yml`
- Modify: `infrastructure/api-gateway/src/main/resources/application.yml`, `.../GatewayRouteDefinitionTest.java`
- Test: `.../api/SyncControllerTest.java`, `.../api/FileControllerTest.java`

**Interfaces (storage-service, all under `/api/v1/storage`):**

```
GET  /auth?force=              -> {status:"success"} | {status:"pending", authUrl}
GET  /auth/callback?code=      -> 302 ${frontendUrl}/?auth=success|error
GET  /auth/status              -> same as /sync/status
POST /sync                     -> {status:"success", filesQueued, filesSeen, syncTime}
GET  /sync/status              -> {status, isAuthenticated, lastSyncTime, lastResult, authUrl?, autoSyncEnabled}
GET  /sync/check               -> {status:"success", hasChanges, changes:{added[], modified[], deleted[]}}
POST /sync/auto {enabled, intervalMs?} -> V1's three response shapes
GET  /files/{id}/content       -> application/octet-stream
POST /files {folderId, fileName, mimeType, contentBase64}          -> {fileId, webViewLink}
POST /folders {parentFolderId, name}                               -> {folderId}
GET  /companies                -> [{id, name, briefCount}]
POST /company-files {companyName, fileName, mimeType, contentBase64} -> {folderId, fileId, webViewLink}
POST /chat-backups {sessionId, contentBase64}                      -> {folderId, fileId, webViewLink}
```

Gateway: `/api/v1/rag/sync/auth/**` → `/api/v1/storage/auth/**`; `/api/v1/rag/sync/**` → `/api/v1/storage/sync/**`. Binary uploads travel as base64 in JSON — PDFs here are well under a megabyte, and it keeps every inter-service call a plain Feign JSON call.

- [ ] **Step 1: Write the failing tests**

`SyncControllerTest.java`:

```java
package com.bloom.eventmanager.storage.api;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.ChangeSet;
import com.bloom.eventmanager.storage.sync.DriveSyncService;
import com.bloom.eventmanager.storage.sync.SyncState;
import com.bloom.eventmanager.storage.sync.SyncSummary;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ SyncController.class, AuthController.class })
@EnableConfigurationProperties(StorageProperties.class)
class SyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveSyncService sync;

    @MockitoBean
    private AutoSyncScheduler scheduler;

    @MockitoBean
    private DriveAuthManager auth;

    @Test
    void statusHasV1sShapeIncludingAuthUrlWhenUnauthenticated() throws Exception {
        when(sync.status()).thenReturn(new DriveSyncService.SyncStatus("idle", false, null, null, "https://accounts.google.com/x"));
        when(scheduler.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("idle"))
                .andExpect(jsonPath("$.isAuthenticated").value(false))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/x"))
                .andExpect(jsonPath("$.autoSyncEnabled").value(false));
    }

    @Test
    void manualSyncReportsQueuedFiles() throws Exception {
        when(sync.syncAll()).thenReturn(new SyncSummary(2, 5, Instant.parse("2026-09-15T10:00:00Z")));

        mockMvc.perform(post("/api/v1/storage/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.filesQueued").value(2))
                .andExpect(jsonPath("$.syncTime").value("2026-09-15T10:00:00Z"));
    }

    @Test
    void manualSyncWithoutDriveAuthIs401WithReauthUrl() throws Exception {
        when(sync.syncAll()).thenThrow(new DriveNotAuthenticatedException());

        mockMvc.perform(post("/api/v1/storage/sync"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DRIVE_NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"))
                .andExpect(jsonPath("$.error").value("Google Drive not authenticated."));
    }

    @Test
    void checkListsNamesForAddedAndModifiedAndIdsForDeleted() throws Exception {
        when(sync.checkForUpdates()).thenReturn(new ChangeSet(
                List.of(new StorageFile("a", "a.pdf", "application/pdf", "t", 1L)), List.of(), List.of("gone")));

        mockMvc.perform(get("/api/v1/storage/sync/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasChanges").value(true))
                .andExpect(jsonPath("$.changes.added[0]").value("a.pdf"))
                .andExpect(jsonPath("$.changes.deleted[0]").value("gone"));
    }

    @Test
    void autoSyncToggleStartsAndStops() throws Exception {
        mockMvc.perform(post("/api/v1/storage/sync/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true, \"intervalMs\": 60000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Auto sync enabled"))
                .andExpect(jsonPath("$.intervalMs").value(60000));
        verify(scheduler).start(60000L);

        mockMvc.perform(post("/api/v1/storage/sync/auto").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Auto sync disabled"));
        verify(scheduler).stop();
    }

    @Test
    void authReturnsPendingWithUrlWhenNotAuthenticated() throws Exception {
        when(auth.isConfigured()).thenReturn(true);
        when(auth.isAuthenticated()).thenReturn(false);
        when(auth.authUrl()).thenReturn("https://accounts.google.com/x");

        mockMvc.perform(get("/api/v1/storage/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/x"));
    }

    @Test
    void authForceReturnsAFreshUrlEvenWhenAuthenticated() throws Exception {
        when(auth.isConfigured()).thenReturn(true);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.authUrl()).thenReturn("https://accounts.google.com/y");

        mockMvc.perform(get("/api/v1/storage/auth").param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.authUrl").value("https://accounts.google.com/y"));
    }

    @Test
    void callbackAuthenticatesStartsAutoSyncAndRedirectsToTheFrontend() throws Exception {
        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/?auth=success"));

        verify(auth).authenticate("abc");
        verify(scheduler).start(300000L);
    }

    @Test
    void callbackWithoutCodeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/storage/auth/callback")).andExpect(status().isBadRequest());
    }
}
```

`FileControllerTest.java`:

```java
package com.bloom.eventmanager.storage.api;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.conventions.CompanyFolder;
import com.bloom.eventmanager.storage.conventions.DriveFolderConventions;
import com.bloom.eventmanager.storage.conventions.NamedFolderUpload;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.UploadResult;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
@EnableConfigurationProperties(StorageProperties.class)
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveClient drive;

    @MockitoBean
    private DriveFolderConventions conventions;

    @MockitoBean
    private DriveAuthManager auth;

    @Test
    void streamsFileContentAsOctetStream() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.download("f1")).thenReturn(new byte[] { 1, 2, 3 });

        mockMvc.perform(get("/api/v1/storage/files/f1/content"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(new byte[] { 1, 2, 3 }));
    }

    @Test
    void downloadWithoutDriveAuthIs401() throws Exception {
        when(auth.isAuthenticated()).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/files/f1/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadsBase64ContentIntoAFolder() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.upload("folder", "x.pdf", new byte[] { 9 }, "application/pdf")).thenReturn(new UploadResult("id", "link"));

        mockMvc.perform(post("/api/v1/storage/files").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderId\": \"folder\", \"fileName\": \"x.pdf\", \"mimeType\": \"application/pdf\", \"contentBase64\": \""
                                + Base64.getEncoder().encodeToString(new byte[] { 9 }) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("id"))
                .andExpect(jsonPath("$.webViewLink").value("link"));
    }

    @Test
    void listsCompaniesUnderTheConfiguredRoot() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(conventions.listCompanyFolders(any())).thenReturn(List.of(new CompanyFolder("f", "Acme", 2)));

        mockMvc.perform(get("/api/v1/storage/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Acme"))
                .andExpect(jsonPath("$[0].briefCount").value(2));
    }

    @Test
    void uploadsACompanyFileThroughTheConventions() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);
        when(conventions.uploadFileToNamedFolder(any(), eq("Acme"), eq("brief.pdf"), eq(new byte[] { 7 }), eq("application/pdf")))
                .thenReturn(new NamedFolderUpload("folder", "file", "link"));

        mockMvc.perform(post("/api/v1/storage/company-files").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\", \"fileName\": \"brief.pdf\", \"mimeType\": \"application/pdf\", \"contentBase64\": \""
                                + Base64.getEncoder().encodeToString(new byte[] { 7 }) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value("folder"))
                .andExpect(jsonPath("$.fileId").value("file"));
    }

    @Test
    void rejectsACompanyFileWithoutAName() throws Exception {
        when(auth.isAuthenticated()).thenReturn(true);

        mockMvc.perform(post("/api/v1/storage/company-files").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"\", \"fileName\": \"b.pdf\", \"mimeType\": \"application/pdf\", \"contentBase64\": \"AA==\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

Append to the gateway's `GatewayRouteDefinitionTest`:

```java
    @Test
    void routesSyncAuthAndSyncToStorageInThatOrder() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).containsSubsequence("sync-auth", "sync");
        assertThat(routeLocator.getRoutes().filter(r -> "sync".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://storage-service");
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest='SyncControllerTest,FileControllerTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class SyncController`

- [ ] **Step 3: Implement**

`api/dto/*.java`:

```java
package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FileUploadRequest(@NotBlank String folderId, @NotBlank String fileName, @NotBlank String mimeType,
        @NotBlank String contentBase64) {
}
```

```java
package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FolderRequest(@NotBlank String parentFolderId, @NotBlank String name) {
}
```

```java
package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CompanyFileRequest(@NotBlank String companyName, @NotBlank String fileName, @NotBlank String mimeType,
        @NotBlank String contentBase64) {
}
```

```java
package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatBackupRequest(@NotBlank String sessionId, @NotBlank String contentBase64) {
}
```

```java
package com.bloom.eventmanager.storage.api.dto;

public record AutoSyncRequest(Boolean enabled, Long intervalMs) {
}
```

`api/AuthController.java`:

```java
package com.bloom.eventmanager.storage.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.DriveSyncService;

@RestController
@RequestMapping("/api/v1/storage/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final DriveAuthManager auth;
    private final AutoSyncScheduler scheduler;
    private final DriveSyncService sync;
    private final StorageProperties properties;

    public AuthController(DriveAuthManager auth, AutoSyncScheduler scheduler, DriveSyncService sync,
            StorageProperties properties) {
        this.auth = auth;
        this.scheduler = scheduler;
        this.sync = sync;
        this.properties = properties;
    }

    /**
     * V1 redirected straight to Google on {@code force=true}. That cannot carry
     * the JWT Phase 5 adds, so this always answers JSON and the SPA navigates.
     */
    @GetMapping
    public Map<String, Object> initAuth(@RequestParam(name = "force", defaultValue = "false") boolean force) {
        if (!auth.isConfigured()) {
            auth.loadCredentials();
        }
        if (!auth.isConfigured()) {
            throw new DriveNotAuthenticatedException();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (auth.isAuthenticated() && !force) {
            body.put("status", "success");
            body.put("message", "Already authenticated");
            return body;
        }
        body.put("status", "pending");
        body.put("message", "Authentication required");
        body.put("authUrl", auth.authUrl());
        return body;
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(name = "code", required = false) String code) {
        if (code == null || code.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "Authorization code missing");
        }
        String target;
        try {
            auth.authenticate(code);
            scheduler.start(properties.syncIntervalMs());
            target = properties.frontendUrl() + "/?auth=success";
        }
        catch (RuntimeException ex) {
            log.error("Google OAuth callback failed", ex);
            target = properties.frontendUrl() + "/?auth=error";
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return SyncController.statusBody(sync.status(), scheduler.isEnabled());
    }
}
```

`api/SyncController.java`:

```java
package com.bloom.eventmanager.storage.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.storage.api.dto.AutoSyncRequest;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.ChangeSet;
import com.bloom.eventmanager.storage.sync.DriveSyncService;
import com.bloom.eventmanager.storage.sync.SyncSummary;

/** V1's sync routes. Response shapes are V1's; the React app's useDriveAuthStatus reads /status. */
@RestController
@RequestMapping("/api/v1/storage/sync")
public class SyncController {

    private final DriveSyncService sync;
    private final AutoSyncScheduler scheduler;
    private final StorageProperties properties;

    public SyncController(DriveSyncService sync, AutoSyncScheduler scheduler, StorageProperties properties) {
        this.sync = sync;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @PostMapping
    public Map<String, Object> syncNow() {
        SyncSummary summary = sync.syncAll();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("filesQueued", summary.filesQueued());
        body.put("filesSeen", summary.filesSeen());
        body.put("syncTime", summary.syncTime().toString());
        return body;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return statusBody(sync.status(), scheduler.isEnabled());
    }

    @GetMapping("/check")
    public Map<String, Object> check() {
        ChangeSet changes = sync.checkForUpdates();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("hasChanges", changes.hasChanges());
        body.put("changes", Map.of(
                "added", changes.added().stream().map(StorageFile::name).toList(),
                "modified", changes.modified().stream().map(StorageFile::name).toList(),
                "deleted", changes.deleted()));
        return body;
    }

    @PostMapping("/auto")
    public Map<String, Object> toggleAutoSync(@RequestBody(required = false) AutoSyncRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        Boolean enabled = request == null ? null : request.enabled();
        if (Boolean.TRUE.equals(enabled)) {
            long interval = request.intervalMs() != null ? request.intervalMs() : properties.syncIntervalMs();
            scheduler.start(interval);
            body.put("status", "success");
            body.put("message", "Auto sync enabled");
            body.put("intervalMs", interval);
        }
        else if (Boolean.FALSE.equals(enabled)) {
            scheduler.stop();
            body.put("status", "success");
            body.put("message", "Auto sync disabled");
        }
        else {
            body.put("status", "info");
            body.put("autoSyncEnabled", scheduler.isEnabled());
            body.put("message", scheduler.isEnabled() ? "Auto sync is running" : "Auto sync is stopped");
        }
        return body;
    }

    static Map<String, Object> statusBody(DriveSyncService.SyncStatus status, boolean autoSyncEnabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.status());
        body.put("isAuthenticated", status.isAuthenticated());
        body.put("lastSyncTime", status.lastSyncTime() == null ? null : status.lastSyncTime().toString());
        body.put("lastResult", status.lastResult());
        if (status.authUrl() != null) {
            body.put("authUrl", status.authUrl());
        }
        body.put("autoSyncEnabled", autoSyncEnabled);
        return body;
    }
}
```

`api/FileController.java`:

```java
package com.bloom.eventmanager.storage.api;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.storage.api.dto.ChatBackupRequest;
import com.bloom.eventmanager.storage.api.dto.CompanyFileRequest;
import com.bloom.eventmanager.storage.api.dto.FileUploadRequest;
import com.bloom.eventmanager.storage.api.dto.FolderRequest;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.conventions.CompanyFolder;
import com.bloom.eventmanager.storage.conventions.DriveFolderConventions;
import com.bloom.eventmanager.storage.conventions.NamedFolderUpload;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.UploadResult;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

/** Generic file/folder primitives plus Bloom's folder conventions, for the other services. */
@RestController
@RequestMapping("/api/v1/storage")
public class FileController {

    private final DriveClient drive;
    private final DriveFolderConventions conventions;
    private final DriveAuthManager auth;
    private final StorageProperties properties;

    public FileController(DriveClient drive, DriveFolderConventions conventions, DriveAuthManager auth,
            StorageProperties properties) {
        this.drive = drive;
        this.conventions = conventions;
        this.auth = auth;
        this.properties = properties;
    }

    @GetMapping(value = "/files/{fileId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String fileId) {
        requireDriveAuth();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(drive.download(fileId));
    }

    @PostMapping("/files")
    public UploadResult upload(@Valid @RequestBody FileUploadRequest request) {
        requireDriveAuth();
        return drive.upload(request.folderId(), request.fileName(), decode(request.contentBase64()), request.mimeType());
    }

    @PostMapping("/folders")
    public Map<String, String> findOrCreateFolder(@Valid @RequestBody FolderRequest request) {
        requireDriveAuth();
        return Map.of("folderId", drive.findOrCreateFolder(request.parentFolderId(), request.name()));
    }

    @GetMapping("/companies")
    public List<CompanyFolder> companies() {
        requireDriveAuth();
        return conventions.listCompanyFolders(properties.rootFolderId());
    }

    @PostMapping("/company-files")
    public NamedFolderUpload uploadCompanyFile(@Valid @RequestBody CompanyFileRequest request) {
        requireDriveAuth();
        return conventions.uploadFileToNamedFolder(properties.rootFolderId(), request.companyName(),
                request.fileName(), decode(request.contentBase64()), request.mimeType());
    }

    @PostMapping("/chat-backups")
    public NamedFolderUpload uploadChatBackup(@Valid @RequestBody ChatBackupRequest request) {
        requireDriveAuth();
        return conventions.uploadSessionBackup(properties.rootFolderId(), request.sessionId(),
                decode(request.contentBase64()), Instant.now());
    }

    private void requireDriveAuth() {
        if (!auth.isAuthenticated()) {
            throw new DriveNotAuthenticatedException();
        }
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        }
        catch (IllegalArgumentException ex) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "contentBase64 is not valid Base64");
        }
    }
}
```

`config-repo/storage-service.yml`:

```yaml
server:
  port: 8086

spring:
  data:
    mongodb:
      uri: ${MONGO_URI:mongodb://localhost:27017}
      # Overrides any database in the URI. Set MONGO_TOKEN_DB to V1's database
      # name to reuse the token V1 already stored.
      database: ${MONGO_TOKEN_DB:tokendb}
      auto-index-creation: true

bloom:
  storage:
    client-id: ${GOOGLE_CLIENT_ID:}
    client-secret: ${GOOGLE_CLIENT_SECRET:}
    credentials-path: ${GOOGLE_CREDENTIALS_PATH:./secrets/client_secret.json}
    # Registered in Google Cloud Console. The gateway's legacy shim owns this path.
    redirect-uri: ${GOOGLE_OAUTH_REDIRECT_URI:http://localhost:8080/api/rag/auth/google/callback}
    root-folder-id: ${GOOGLE_DRIVE_FOLDER_ID:}
    sync-interval-ms: ${DRIVE_SYNC_INTERVAL_MS:300000}
    frontend-url: ${FRONTEND_URL:http://localhost:5173}
```

Gateway — add these two routes **before** `retrieve` in `infrastructure/api-gateway/src/main/resources/application.yml` (order matters: the more specific `sync-auth` must precede `sync`):

```yaml
            - id: sync-auth
              uri: lb://storage-service
              predicates:
                - Path=/api/v1/rag/sync/auth,/api/v1/rag/sync/auth/**
              filters:
                - RewritePath=/api/v1/rag/sync/auth(?<rest>.*),/api/v1/storage/auth$\{rest}
            - id: sync
              uri: lb://storage-service
              predicates:
                - Path=/api/v1/rag/sync,/api/v1/rag/sync/**
              filters:
                - RewritePath=/api/v1/rag/sync(?<rest>.*),/api/v1/storage/sync$\{rest}
```

- [ ] **Step 4: Run all storage-service and gateway tests**

Run: `./mvnw -q -pl services/storage-service,infrastructure/api-gateway test`
Expected: PASS — storage 5 + 4 + 7 + 2 + 4 + 10 + 1 + 9 + 6 = 48; gateway 7. Docker-dependent tests skip without Docker.

- [ ] **Step 5: Commit**

```bash
git add services/storage-service config-repo/storage-service.yml infrastructure/api-gateway
git commit -m "feat(storage): auth, sync, file and convention endpoints; gateway sync routes"
```

---

### Task 6: End-to-end — a PDF dropped in Drive lands in Neo4j

**Files:** none.

- [ ] **Step 1: One-time Google Cloud Console change**

In the OAuth client used by V1, add `http://localhost:8080/api/rag/auth/google/callback` as an **additional** authorised redirect URI. Do not remove existing entries.

- [ ] **Step 2: Build and start**

Run: `./mvnw clean install` — twelve reactor projects.

Environment: Phase 1's variables plus `GOOGLE_CREDENTIALS_PATH` (V1's `secrets/client_secret.json`) or `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`, and `GOOGLE_DRIVE_FOLDER_ID` (V1's value). Start infra, discovery, config, knowledge, ai, ingestion, storage, gateway.

- [ ] **Step 3: Authenticate with Drive**

```bash
curl -s "http://localhost:8080/api/v1/rag/sync/auth?force=true" | jq -r .authUrl
```

Open the printed URL in a browser, consent, and let Google redirect. The browser lands on `http://localhost:8080/api/rag/auth/google/callback?code=…` → 307 → `/api/v1/rag/sync/auth/callback` → storage-service → 302 to `http://localhost:5173/?auth=success`. The last hop shows a connection error (no frontend until Phase 5). That is expected.

```bash
curl -s http://localhost:8080/api/v1/rag/sync/status | jq
```

Expected: `isAuthenticated: true`, `autoSyncEnabled: true`. `storage-service` log: `Google Drive authentication successful, token saved` then `AutoSync started`. Mongo `tokendb.drivetokens` has one document.

- [ ] **Step 4: Initial reconciliation**

The first auto-sync pass compares Drive against `knowledge-service`'s recorded metadata. Against V1's Neo4j (which already has metadata for V1-ingested files) only genuinely new or changed files are queued; against an empty knowledge base everything is. Watch:

```bash
curl -s http://localhost:8080/api/v1/rag/sync/check | jq
```

- [ ] **Step 5: The exit criterion**

Upload a new small PDF into the Drive folder (web UI). Within one poll interval (or immediately via `curl -X POST http://localhost:8080/api/v1/rag/sync`):

- `storage-service`: `AutoSync: 1 added, 0 modified, 0 deleted`
- `ingestion-service`: `Ingesting from Drive: <name>` … `Queued N chunks`
- `knowledge-service`: `Indexed <name> (N chunks)`
- `storage-service`: `Recorded Drive metadata for <name> (N chunks)`

```bash
curl -s http://localhost:8082/api/v1/drive-files | jq
curl -s -X POST http://localhost:8080/api/v1/rag/retrieve -H "Content-Type: application/json" \
     -d '{"query": "<phrase from the new PDF>", "topK": 3, "useAI": false}' | jq '.chunks[].source'
```

Expected: the new file's Drive id appears with its RFC 3339 `modifiedTime`; retrieval returns chunks from it. **No manual step happened between the upload and the search.**

- [ ] **Step 6: Modification and deletion**

Edit the PDF in Drive (or re-upload a changed version to the same file): next poll logs `1 modified`; `knowledge-service` deletes and re-indexes; `countChunks` reflects the new content. Delete the file from Drive: next poll logs `1 deleted`; `knowledge-service` logs `Deleted N chunks for <name>`; `GET /api/v1/drive-files` no longer lists the id.

- [ ] **Step 7: Record and commit**

```bash
git add README.md
git commit -m "docs: Phase 3 write path verified end to end from Google Drive"
```

---

## Phase 3 Exit Criteria (3a + 3b)

- [ ] `./mvnw clean install` green, twelve reactor projects
- [ ] OAuth completes through the gateway's legacy shim and the token persists in Mongo `tokendb.drivetokens` in V1's shape
- [ ] A PDF added to the Drive folder is chunked, embedded and searchable with no manual step
- [ ] A modified Drive file is re-indexed; a deleted one disappears from Neo4j and from `/api/v1/drive-files`
- [ ] `storage-service` is the only module holding Drive credentials; `knowledge-service` the only one touching Neo4j
- [ ] `GET /api/v1/rag/sync/status` returns the shape V1's `useDriveAuthStatus` hook expects

## What Phase 3 Does Not Do

- No chat backups or proposal uploads are *triggered* — the `/chat-backups` and `/company-files` endpoints exist for Phase 4 to call
- No frontend — the OAuth redirect lands nowhere until Phase 5
- No JWT on the gateway — Phase 5, including the `permitAll` needed for the OAuth callback path
