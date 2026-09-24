# Phase 6 — Config Server Git Backend and OAuth Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Config Server off its filesystem `native` backend onto a git backend with encrypted secrets, and close the OAuth CSRF hole that lets an attacker bind their own Google account as the fleet's Drive identity.

**Architecture:** The Config Server keeps serving the same properties on the same port; only its backend changes, from `spring.profiles.active=native` reading `./config-repo` to a git repository read over HTTPS. That removes the Config Server's host bind-mount (Task 5 also moves ingestion-service's PDF mount to a named volume, the other one Compose needs), makes every config change versioned and reviewable, and — because git-backed config can carry `{cipher}` values — lets `INTERNAL_SERVICE_TOKEN` and the Google client secret live in config instead of in each deployment's environment. Separately, storage-service's Google OAuth flow gains a `state` nonce: `authUrl()` mints one, stores it, and the callback rejects any `code` that does not arrive with a matching, unexpired nonce.

**Tech Stack:** Spring Cloud Config Server 5.0.x (git backend, `spring-cloud-config-server`), Spring Cloud Config encryption (`{cipher}`, JCE unlimited-strength), Spring Boot 4.0.8, Java 25, MongoDB (nonce store, reusing storage-service's existing `tokendb`).

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` — section 11's deviation table records `Config Server native backend → Git backend | One less moving part to demo`. This phase pays that debt down. Section 9 covers the OAuth flow.

**Prerequisite:** Phase 5 complete — `bloom-security` validating JWTs in every service, `config-repo/` holding the shared `application.yml` plus one file per service, and `deploy/docker-compose.yml` bind-mounting `../config-repo` into the Config Server.

## Global Constraints

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.** Spring Security is `7.0.7`: lambda DSL only.
- **Boot 4.0 test modules:** `spring-boot-webmvc-test` (`@WebMvcTest` at `org.springframework.boot.webmvc.test.autoconfigure`), `@MockitoBean`.
- **JSON in application code is Jackson 3** (`tools.jackson.databind.json.JsonMapper`); annotations stay `com.fasterxml.jackson.annotation`.
- **Secrets are environment-only until Task 4 lands**, and even then only `{cipher}` ciphertext may be committed. `ENCRYPT_KEY`, `JWT_PRIVATE_KEY_PEM`, `JWT_PUBLIC_KEY_PEM`, `INTERNAL_SERVICE_TOKEN`, `APP_AUTH_USER`, `APP_AUTH_PASSWORD`, `GOOGLE_CLIENT_SECRET` never appear as plaintext in any committed file, including this plan and the README.
- **Tests must stay hermetic.** Phase 5 added `src/test/resources/application.properties` with `spring.cloud.config.enabled=false` to all eight Boot modules. Do not remove those — a test that reaches a Config Server is a test whose result depends on what happens to be running.
- **Commit with explicit paths, never `git add -A`.**

---

## File Structure

```
infrastructure/config-server/
  src/main/resources/application.yml                 MODIFY: git backend, profile-gated
  src/main/resources/application-native.yml           new: keeps the filesystem backend for offline work
  src/test/java/.../GitBackendConfigurationTests.java  new
services/storage-service/
  .../oauth/OAuthStateStore.java                       new: mint/consume single-use nonces
  .../mongo/OAuthStateDocument.java                    new
  .../oauth/DriveAuthManager.java                      MODIFY: authUrl() sets state
  .../api/AuthController.java                          MODIFY: callback requires state
  .../config/StorageProperties.java                    MODIFY: stateTtlMs
  test: OAuthStateStoreTest, AuthControllerStateTest, DriveAuthManagerStateTest
config-repo/                                           MOVED to its own git repository
deploy/docker-compose.yml                              MODIFY: drop both host bind mounts (config-repo, PDFs), add git env
README.md                                              MODIFY: git backend setup, encryption, deviations
```

---

### Task 1: OAuth state nonce — the store

**Files:**
- Create: `services/storage-service/src/main/java/com/bloom/eventmanager/storage/oauth/OAuthStateStore.java`
- Create: `services/storage-service/src/main/java/com/bloom/eventmanager/storage/mongo/OAuthStateDocument.java`
- Modify: `services/storage-service/src/main/java/com/bloom/eventmanager/storage/config/StorageProperties.java`
- Test: `services/storage-service/src/test/java/com/bloom/eventmanager/storage/oauth/OAuthStateStoreTest.java`

**Interfaces:**
- Produces: `OAuthStateStore.mint(): String` returns a fresh 256-bit URL-safe nonce and persists it; `OAuthStateStore.consume(String state): boolean` returns true exactly once per nonce and false for unknown, already-used, or expired values.
- Consumes: `StorageProperties.stateTtlMs()` — new component, `@DefaultValue("600000")` (10 minutes).

Why Mongo rather than an in-memory set: storage-service already owns `tokendb` and a token document, the nonce must survive the process restart that a slow human consent flow can outlast, and an in-memory set silently breaks the moment the service runs more than one replica.

Why `MongoTemplate.findAndRemove` rather than a derived repository method: redemption must be atomic, or two concurrent callbacks can both read the nonce before either deletes it and both get through. `findAndRemove` maps to Mongo's `findAndModify` with `remove: true`, a single server-side operation. Spring Data's derived `deleteBy...` is a find followed by a separate remove, and there is no `findAndDeleteBy...` derivation keyword at all. `ChatSessionStore` in chat-service already sets this precedent.

- [ ] **Step 1: Write the failing test**

`OAuthStateStoreTest.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.bloom.eventmanager.storage.mongo.OAuthStateDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthStateStoreTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final OAuthStateStore store = new OAuthStateStore(mongoTemplate, Duration.ofMinutes(10));

    @Test
    void mintsAnUnguessableNonceAndPersistsIt() {
        String state = store.mint();

        assertThat(state).isNotBlank();
        // 256 bits base64url without padding.
        assertThat(state.length()).isGreaterThanOrEqualTo(43);
        verify(mongoTemplate).save(any(OAuthStateDocument.class));
    }

    @Test
    void mintsADifferentNonceEveryTime() {
        assertThat(store.mint()).isNotEqualTo(store.mint());
    }

    @Test
    void consumesAValidNonceExactlyOnce() {
        OAuthStateDocument doc = new OAuthStateDocument("abc", Instant.now().plusSeconds(60));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class)))
                .thenReturn(doc, (OAuthStateDocument) null);

        assertThat(store.consume("abc")).isTrue();
        assertThat(store.consume("abc")).isFalse();
    }

    @Test
    void rejectsAnUnknownNonce() {
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class))).thenReturn(null);

        assertThat(store.consume("never-issued")).isFalse();
    }

    @Test
    void rejectsAnExpiredNonce() {
        OAuthStateDocument expired = new OAuthStateDocument("old", Instant.now().minusSeconds(1));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class))).thenReturn(expired);

        assertThat(store.consume("old")).isFalse();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(store.consume(null)).isFalse();
        assertThat(store.consume("  ")).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest=OAuthStateStoreTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class OAuthStateStore`

- [ ] **Step 3: Implement**

`mongo/OAuthStateDocument.java`:

```java
package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A single-use CSRF nonce for the Google OAuth flow. */
@Document(collection = "oauthstates")
public record OAuthStateDocument(@Id String state, @Indexed(expireAfterSeconds = 0) Instant expiresAt) {
}
```

`oauth/OAuthStateStore.java`:

```java
package com.bloom.eventmanager.storage.oauth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.mongo.OAuthStateDocument;

/**
 * Single-use nonces for the Google OAuth {@code state} parameter.
 *
 * <p>Without this, the callback accepts an authorization code from anyone. The
 * OAuth client id and the registered redirect URI are both public, so an
 * attacker can run the consent flow against their own Google account, capture
 * the code, and hand it to our callback — binding their Drive as the fleet's
 * single Drive identity and poisoning the corpus the Event Advisor answers from.
 */
@Component
public class OAuthStateStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongoTemplate;
    private final Duration ttl;

    public OAuthStateStore(MongoTemplate mongoTemplate, Duration ttl) {
        this.mongoTemplate = mongoTemplate;
        this.ttl = ttl;
    }

    public String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mongoTemplate.save(new OAuthStateDocument(state, Instant.now().plus(ttl)));
        return state;
    }

    /**
     * True at most once per minted nonce. findAndRemove is Mongo's findAndModify
     * with remove:true -- one server-side operation, so two callbacks racing on
     * the same nonce cannot both be told yes.
     */
    public boolean consume(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        OAuthStateDocument found = mongoTemplate.findAndRemove(
                Query.query(Criteria.where("_id").is(state)), OAuthStateDocument.class);
        return found != null && found.expiresAt().isAfter(Instant.now());
    }
}
```

Add to `StorageProperties`, after `accountId`:

```java
        @DefaultValue("600000") @Min(60_000) long stateTtlMs
```

and register the bean where the other storage beans are built, passing `Duration.ofMillis(properties.stateTtlMs())`.

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl services/storage-service test -Dtest=OAuthStateStoreTest`
Expected: PASS — 6 tests

- [ ] **Step 5: Commit**

```bash
git add services/storage-service/src/main/java/com/bloom/eventmanager/storage/oauth/OAuthStateStore.java \
        services/storage-service/src/main/java/com/bloom/eventmanager/storage/mongo/OAuthStateDocument.java \
        services/storage-service/src/main/java/com/bloom/eventmanager/storage/config/StorageProperties.java \
        services/storage-service/src/test/java/com/bloom/eventmanager/storage/oauth/OAuthStateStoreTest.java
git commit -m "feat(storage): single-use OAuth state nonces"
```

---

### Task 2: Wire the nonce into the OAuth flow

**Files:**
- Modify: `services/storage-service/src/main/java/com/bloom/eventmanager/storage/oauth/DriveAuthManager.java`
- Modify: `services/storage-service/src/main/java/com/bloom/eventmanager/storage/api/AuthController.java`
- Test: `services/storage-service/src/test/java/com/bloom/eventmanager/storage/api/AuthControllerStateTest.java`

**Interfaces:**
- Consumes: `OAuthStateStore.mint()`, `OAuthStateStore.consume(String)` from Task 1.
- Produces: `GET /api/v1/storage/auth/callback?code=…&state=…` returns 302 to `frontendUrl + "/?auth=error"` when `state` is missing or not redeemable, and never exchanges the code in that case.

- [ ] **Step 1: Write the failing test**

`AuthControllerStateTest.java`:

```java
package com.bloom.eventmanager.storage.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.OAuthStateStore;
import com.bloom.eventmanager.storage.sync.AutoSyncScheduler;
import com.bloom.eventmanager.storage.sync.DriveSyncService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
class AuthControllerStateTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriveAuthManager auth;

    @MockitoBean
    private OAuthStateStore states;

    @MockitoBean
    private AutoSyncScheduler scheduler;

    @MockitoBean
    private DriveSyncService sync;

    @Test
    void exchangesTheCodeWhenTheStateIsValid() throws Exception {
        when(states.consume("good-state")).thenReturn(true);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "good-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=success")));

        verify(auth).authenticate("abc");
    }

    @Test
    void refusesToExchangeACodeWithNoState() throws Exception {
        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=error")));

        verify(auth, never()).authenticate(anyString());
    }

    @Test
    void refusesToExchangeACodeWithAnUnknownState() throws Exception {
        when(states.consume("forged")).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "forged"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("auth=error")));

        verify(auth, never()).authenticate(anyString());
    }

    @Test
    void doesNotStartTheSyncSchedulerOnARejectedCallback() throws Exception {
        when(states.consume("forged")).thenReturn(false);

        mockMvc.perform(get("/api/v1/storage/auth/callback").param("code", "abc").param("state", "forged"))
                .andExpect(status().isFound());

        verify(scheduler, never()).start(org.mockito.ArgumentMatchers.anyLong());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/storage-service test -Dtest=AuthControllerStateTest`
Expected: FAIL — the callback exchanges the code regardless of `state`, so `refusesToExchangeACodeWithNoState` fails on `verify(auth, never()).authenticate(...)`.

- [ ] **Step 3: Implement**

In `DriveAuthManager`, take `OAuthStateStore` through the constructor and change `authUrl()`:

```java
    public String authUrl() {
        requireConfigured();
        GoogleAuthorizationCodeRequestUrl url = new GoogleAuthorizationCodeRequestUrl(
                AUTHORIZATION_SERVER_URL, client.clientId(), properties.redirectUri(), properties.scopes());
        url.setAccessType("offline");
        url.set("prompt", "consent");
        // Google echoes this back to the callback unchanged. Without it the
        // callback cannot tell our own consent flow from an attacker's.
        url.setState(states.mint());
        return url.build();
    }
```

In `AuthController`, add an `OAuthStateStore states` field, take it through the constructor alongside the existing dependencies, and replace the callback:

```java
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state) {
        if (code == null || code.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "Authorization code missing");
        }
        // Checked before the code is exchanged: an unredeemable state means this
        // callback did not originate from a consent flow we started.
        if (!states.consume(state)) {
            log.warn("Rejected Google OAuth callback with a missing or unrecognised state parameter");
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(properties.frontendUrl() + "/?auth=error")).build();
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
```

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl services/storage-service test`
Expected: PASS. Existing `DriveAuthManagerTest` needs the new constructor argument — pass a mock `OAuthStateStore` whose `mint()` returns a fixed string, and assert the built URL contains `state=`.

- [ ] **Step 5: Commit**

```bash
git add services/storage-service
git commit -m "fix(storage): require a state nonce on the Google OAuth callback"
```

---

### Task 3: Config Server git backend

**Files:**
- Modify: `infrastructure/config-server/src/main/resources/application.yml`
- Create: `infrastructure/config-server/src/main/resources/application-native.yml`
- Test: `infrastructure/config-server/src/test/java/com/bloom/eventmanager/config/GitBackendConfigurationTests.java`

**Interfaces:**
- Produces: the Config Server serves the same `{application}/{profile}` documents as before. Backend is git by default (`CONFIG_GIT_URI`); the `native` Spring profile keeps the old filesystem behaviour for offline work.

**Before starting:** create the git repository that will hold the config. Push the current `config-repo/` contents to it:

```bash
cd config-repo
git init && git add . && git commit -m "Initial config"
git remote add origin <your config repo url>
git push -u origin main
```

Keep `config-repo/` in this repository for now; Task 5 removes it once the git backend is proven.

- [ ] **Step 1: Write the failing test**

`GitBackendConfigurationTests.java`:

```java
package com.bloom.eventmanager.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.server.environment.MultipleJGitEnvironmentRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend is a deployment-shaped decision, so it is worth a test that fails
 * loudly if someone reverts to the filesystem by accident.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.server.git.uri=https://example.invalid/bloom-config.git",
        "spring.cloud.config.server.git.clone-on-start=false",
        "eureka.client.enabled=false"
})
class GitBackendConfigurationTests {

    @Autowired
    private MultipleJGitEnvironmentRepository repository;

    @Test
    void defaultsToTheGitBackend() {
        assertThat(repository).isNotNull();
        assertThat(repository.getUri()).isEqualTo("https://example.invalid/bloom-config.git");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl infrastructure/config-server test -Dtest=GitBackendConfigurationTests`
Expected: FAIL — no `MultipleJGitEnvironmentRepository` bean, because `spring.profiles.active=native` pins the filesystem backend.

- [ ] **Step 3: Implement**

Replace `application.yml`'s `spring` block:

```yaml
spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        git:
          # Versioned, reviewable, and the same for every replica -- unlike the
          # native backend, which needs a host path mounted into the container.
          uri: ${CONFIG_GIT_URI}
          default-label: ${CONFIG_GIT_BRANCH:main}
          # Read-only deploy credentials. Never commit these.
          username: ${CONFIG_GIT_USERNAME:}
          password: ${CONFIG_GIT_PASSWORD:}
          clone-on-start: true
          timeout: 10
          # The Config Server fetches from git at most every 30 s, so pushes are
          # picked up without restarting it. A client still needs its own
          # POST /actuator/refresh, authenticated with a JWT or X-Internal-Token.
          refresh-rate: 30
```

`application-native.yml` — the escape hatch for working offline:

```yaml
# Activated with SPRING_PROFILES_ACTIVE=native. Keeps Phase 5's filesystem
# backend for working without network access to the config repository.
spring:
  cloud:
    config:
      server:
        native:
          search-locations: ${CONFIG_REPO:file:./config-repo}
```

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl infrastructure/config-server test`
Expected: PASS. Existing config-server tests that relied on the native backend must set `spring.profiles.active=native` in their `@SpringBootTest(properties=…)`; they are testing the filesystem path deliberately, so that is correct rather than a workaround.

- [ ] **Step 5: Verify live**

```bash
CONFIG_GIT_URI=<your config repo url> ./mvnw -q -pl infrastructure/config-server spring-boot:run
curl -s http://localhost:8888/application/default | head -40
curl -s http://localhost:8888/storage-service/default | head -40
```

Expected: the same properties Phase 5 served from the filesystem, now with `"version"` holding the git commit SHA.

- [ ] **Step 6: Commit**

```bash
git add infrastructure/config-server
git commit -m "feat(config): git backend, with the native filesystem backend behind a profile"
```

---

### Task 4: Encrypt the secrets that currently live in the environment

**Files:**
- Modify: `infrastructure/config-server/src/main/resources/application.yml`
- Modify: the config git repository's `application.yml` and `storage-service.yml`
- Modify: `deploy/.env.example`, `README.md`

**Interfaces:**
- Produces: `POST /encrypt` and `POST /decrypt` on the Config Server; config values may be written as `'{cipher}<ciphertext>'` and are decrypted before being served.

A git-backed config repository is only an improvement if secrets stop travelling as plaintext environment variables. Spring Cloud Config's symmetric encryption lets `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET` be committed as ciphertext, leaving exactly one secret in the environment: `ENCRYPT_KEY`.

- [ ] **Step 1: Turn on encryption**

Add to the Config Server's `application.yml`:

```yaml
encrypt:
  # The one secret that cannot live in the config repo. 32+ random bytes.
  key: ${ENCRYPT_KEY}
```

Generate one and keep it out of the repository:

```bash
openssl rand -base64 32
```

- [ ] **Step 2: Verify the round trip**

```bash
ENCRYPT_KEY=<generated> ./mvnw -q -pl infrastructure/config-server spring-boot:run
curl -s -X POST --data-urlencode "test-value" http://localhost:8888/encrypt
curl -s -X POST --data-urlencode "<the ciphertext>" http://localhost:8888/decrypt
```

Expected: `/decrypt` returns `test-value`. If `/encrypt` returns `NOT_INSTALLED`, the JVM lacks unlimited-strength JCE — note it and stop; Java 25 should not have this problem.

- [ ] **Step 3: Move the secrets**

In the config repository, replace the environment placeholders:

```yaml
bloom:
  security:
    internal-token: '{cipher}<ciphertext from /encrypt>'
```

and in `storage-service.yml`:

```yaml
bloom:
  storage:
    client-secret: '{cipher}<ciphertext>'
```

Leave `JWT_PRIVATE_KEY_PEM` and `JWT_PUBLIC_KEY_PEM` in the gateway's environment. The gateway reads them before it can talk to anything, and a signing key is worth keeping out of a config repository entirely.

- [ ] **Step 4: Verify the fleet still starts**

Start the Config Server with `ENCRYPT_KEY` set, then a service with `INTERNAL_SERVICE_TOKEN` **unset**.

Expected: the service starts and authenticates peers normally, because the token now arrives decrypted from config. If it fails `@NotBlank` binding, the cipher value is not being decrypted — check that the value is single-quoted in YAML.

- [ ] **Step 5: Commit**

```bash
git add infrastructure/config-server/src/main/resources/application.yml deploy/.env.example README.md
git commit -m "feat(config): encrypt shared secrets with {cipher}, leaving only ENCRYPT_KEY in the environment"
```

---

### Task 5: Compose without the bind mount

**Files:**
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/.env.example`
- Delete: `config-repo/` (after Task 3 proves the git backend serves the same values)
- Modify: `README.md`

This is the payoff for the whole phase: Compose stops needing any host path, which is what blocked Phase 5's full-stack Compose verification on a machine whose Docker file sharing did not include the drive. There are two to remove, not one: the Config Server's `../config-repo` mount, and ingestion-service's `${PDF_DIRECTORY:-./pdfs}:/data/pdfs:ro`. Removing only the first leaves Compose needing file sharing, and the exit criterion below still fails.

- [ ] **Step 1: Replace the mount with git configuration**

In `deploy/docker-compose.yml`, the `config-server` service:

```yaml
  config-server:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: infrastructure/config-server } }
    profiles: ["platform"]
    environment:
      # No bind mount: the backend is a git repository, so nothing from the host
      # needs to be shared into the container.
      CONFIG_GIT_URI: ${CONFIG_GIT_URI}
      CONFIG_GIT_BRANCH: ${CONFIG_GIT_BRANCH:-main}
      CONFIG_GIT_USERNAME: ${CONFIG_GIT_USERNAME:-}
      CONFIG_GIT_PASSWORD: ${CONFIG_GIT_PASSWORD:-}
      ENCRYPT_KEY: ${ENCRYPT_KEY}
      ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans
    ports: ["127.0.0.1:8888:8888"]
    mem_limit: 512m
    # Unchanged. Keep the anchor: every other Java service merges *java-health.
    healthcheck: &java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8888/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 40s
```

Remove the `volumes:` block entirely.

Extend `deploy/.env.example`:

```
CONFIG_GIT_URI=https://github.com/<org>/bloom-config.git
CONFIG_GIT_BRANCH=main
CONFIG_GIT_USERNAME=
CONFIG_GIT_PASSWORD=
ENCRYPT_KEY=
```

- [ ] **Step 2: Replace ingestion-service's PDF bind mount with a named volume**

`ingestion-service` reads the PDFs for the local ingest path (`POST /api/v1/rag/ingest`) from `/data/pdfs`. Keep that container path and the `:ro` flag; change only the source, from a host directory to a named volume:

```yaml
  ingestion-service:
    # build, profiles, environment, depends_on, mem_limit, healthcheck: unchanged
    volumes:
      # A named volume, not a host path: nothing from the host is shared into
      # the container, so no Docker file-sharing configuration is needed.
      - ingest-pdfs:/data/pdfs:ro
```

and declare it with the other named volumes at the bottom of the file:

```yaml
volumes:
  # ...existing volumes unchanged...
  ingest-pdfs:
```

Remove `PDF_DIRECTORY=./pdfs` from `deploy/.env.example`: in Compose it only ever named the host directory to mount. `PDF_DIRECTORY` itself stays in `config-repo/ingestion-service.yml` for IDE runs, and the container keeps its literal `PDF_DIRECTORY: /data/pdfs`. Update the README's `PDF_DIRECTORY` row to match.

Load PDFs into the volume through the Docker API, which streams a tar archive and needs no file sharing. `docker cp` refuses to write into a read-only mount (`mounted volume is marked read-only`), so copy through a short-lived helper that mounts the volume read-write, then ingest:

```bash
docker run -d --name bloom-pdf-loader -v bloom-v2_ingest-pdfs:/data/pdfs alpine sleep 300
docker cp ./my-pdfs/. bloom-pdf-loader:/data/pdfs/
docker rm -f bloom-pdf-loader
curl -s -X POST http://localhost:8080/api/v1/rag/ingest -H "Authorization: Bearer $TOKEN"
```

`bloom-v2_ingest-pdfs` is Compose's `<project>_<volume>` name for the project `bloom-v2`. Drive-sourced PDFs never touch this volume: ingestion downloads those from storage-service. Document the loading commands in the README under "Running it".

- [ ] **Step 3: Verify the full stack**

```bash
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps up -d --build
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps ps
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps config | grep -c "type: bind"
```

Expected: all sixteen containers healthy within ~3 minutes — `frontend` included, since it now declares a healthcheck — with no Docker Desktop file-sharing configuration required, and `0` host binds. This is the Phase 5 exit criterion that could not be demonstrated.

- [ ] **Step 4: Remove the filesystem config**

Once the stack is healthy on the git backend, delete `config-repo/` from this repository — leaving it invites edits that silently do nothing, since the Config Server no longer reads it. The `native` profile from Task 3 still works against an external checkout via `CONFIG_REPO`.

- [ ] **Step 5: Commit**

```bash
git add deploy/docker-compose.yml deploy/.env.example README.md
git rm -r config-repo
git commit -m "chore(deploy): Config Server reads git and PDFs live in a volume, so Compose needs no host mount"
```

---

## Phase 6 Exit Criteria

- [ ] The Google OAuth callback rejects any `code` that arrives without a matching, unexpired, single-use `state`, and does not exchange it or start the sync scheduler
- [ ] A nonce can be redeemed exactly once, and expires on its own via a Mongo TTL index
- [ ] The Config Server serves every property from a git repository, with the commit SHA visible as the document `version`
- [ ] `SPRING_PROFILES_ACTIVE=native` still serves from a filesystem checkout, for offline work
- [ ] `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET` are `{cipher}` ciphertext in the config repository; `ENCRYPT_KEY` is the only shared secret left in the environment
- [ ] `docker compose --profile infra --profile platform --profile apps up -d --build` brings up all sixteen containers healthy, `frontend` included, **with no Docker file-sharing configuration** — neither the config-repo mount nor the PDF mount remains
- [ ] `./mvnw clean install` passes with no skipped tests
- [ ] No plaintext secret anywhere in either repository

## What Phase 6 Does Not Do

- No per-user accounts — still one shared login, as V1 had
- No asymmetric config encryption (a keystore); the symmetric `ENCRYPT_KEY` is enough for one deployment
- No automatic `/actuator/refresh` fan-out on a config push — each client is still refreshed deliberately, with an authenticated `POST /actuator/refresh`
- No authentication on Eureka — `discovery-server` still accepts anonymous registrations, mitigated only by Compose binding 8761 to `127.0.0.1`; HTTP Basic on the registry, with credentials in every client's `defaultZone`, is the follow-up
- No Kubernetes; Compose remains the deployment target
