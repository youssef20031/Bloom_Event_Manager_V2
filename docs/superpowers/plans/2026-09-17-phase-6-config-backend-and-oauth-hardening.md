# Phase 6 — Config Without Host Mounts, Encrypted Secrets, and OAuth Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Revised 2026-09-25.** The first draft moved the Config Server to a git backend in a separate repository. That was chosen for spec fidelity; weighed on its own merits for a single-machine, single-deployment project, it adds a second repository to keep in step with the code, a deploy credential, and a network dependency at boot, and buys nothing the goals need. Two of its stated benefits were also not git-specific: `{cipher}` decryption works for every Config Server backend (it wraps whichever repository is configured), and removing the bind mount only requires the config to reach the container some other way. Tasks 3 and 5 were rewritten accordingly; Tasks 1, 2 and 4 are unchanged in intent.

**Goal:** Remove every host bind mount from Compose, move the shared secrets out of the environment as `{cipher}` ciphertext, and close the OAuth CSRF hole that lets an attacker bind their own Google account as the fleet's Drive identity.

**Architecture:** The Config Server keeps its `native` filesystem backend and keeps serving the same properties on the same port; only how the files reach it changes. Instead of a bind mount of `../config-repo`, the config-server image carries `config-repo/` at `/config-repo`, copied as its last layer so a config-only edit rebuilds in seconds. For live editing, Compose Watch's `sync` action copies each changed file into the running container through the Docker API — no bind mount, so no Docker Desktop file sharing — and the native backend re-reads its files on every request. Config stays versioned with the code, in the same commits and PRs. Ingestion-service's PDF mount becomes a named volume, so Compose needs no host path at all. Encryption: the Config Server decrypts `{cipher}` values before serving them, so `INTERNAL_SERVICE_TOKEN` and the Google client secret are committed as ciphertext and `ENCRYPT_KEY` is the only shared secret left in the environment. Separately, storage-service's Google OAuth flow gains a `state` nonce: `authUrl()` mints one, stores it, and the callback rejects any `code` that does not arrive with a matching, unexpired nonce.

**Tech Stack:** Spring Cloud Config Server 5.0.x (native backend, `{cipher}` symmetric encryption), Docker Compose v5 (multi-stage build targets, `develop.watch`), Spring Boot 4.0.8, Java 25, MongoDB (nonce store, reusing storage-service's existing `tokendb`).

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` — section 11's deviation table records `Config Server native backend → Git backend | One less moving part to demo`. This phase keeps the deviation deliberately and records the new reason; section 9 covers the OAuth flow.

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
services/storage-service/
  .../oauth/OAuthStateStore.java                       new: mint/consume single-use nonces
  .../mongo/OAuthStateDocument.java                    new
  .../mongo/OAuthStateIndexInitializer.java            new: TTL index (auto-index-creation is off)
  .../config/OAuthStateConfig.java                     new: builds OAuthStateStore from stateTtlMs
  .../oauth/DriveAuthManager.java                      MODIFY: authUrl() sets state
  .../api/AuthController.java                          MODIFY: callback requires state
  .../config/StorageProperties.java                    MODIFY: stateTtlMs
  test: OAuthStateStoreTest, OAuthStateIndexInitializerTest, AuthControllerStateTest, DriveAuthManagerTest
deploy/Dockerfile.service                              MODIFY: named runtime stage + config-server target
deploy/docker-compose.yml                              MODIFY: no host bind mounts; Compose Watch for config; secrets out of env
infrastructure/config-server/src/main/resources/application.yml   MODIFY: encrypt.key; backend comment
infrastructure/config-server/src/test/...              MODIFY: test key + a {cipher} round-trip test
infrastructure/api-gateway/src/main/resources/application.yml     MODIFY: internal token from shared config
config-repo/application.yml, config-repo/storage-service.yml      MODIFY: {cipher} values
deploy/.env.example, README.md                         MODIFY
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

### Task 3: The Config Server image carries config-repo

**Files:**
- Modify: `deploy/Dockerfile.service`
- Modify: `deploy/docker-compose.yml` — the `config-server` service only
- Modify: `infrastructure/config-server/src/main/resources/application.yml` — the backend comment only
- Modify: `README.md` — the config-server row of the Architecture table, "Two things that look like misconfiguration and are not", and the `CONFIG_REPO` row of the environment-variable table

**Interfaces:**
- Produces: the `config-server` image serves `config-repo/` from `/config-repo`, copied in at build time. Other services' images are byte-for-byte what they were: they build the Dockerfile's default (last) stage, which the new stage does not touch. Host runs are unchanged — started from the repository root, the Config Server still reads `file:./config-repo`.
- Produces: while `docker compose … watch config-server` (or `up --watch`) runs, every save under `config-repo/` is copied into the running container and served on the next request.

- [ ] **Step 1: Give the Config Server its own build target**

In `deploy/Dockerfile.service`, name the runtime stage and add two stages after it. The existing runtime instructions do not change:

```dockerfile
FROM eclipse-temurin:25-jre AS runtime
ARG MODULE_PATH
# ...curl, JAVA_TOOL_OPTIONS, WORKDIR, COPY --from=build, ENTRYPOINT: unchanged...

# The Config Server's image also carries the configuration it serves, so Compose
# needs no bind mount -- which Docker Desktop only allows for drives shared in its
# File Sharing settings. Deliberately the last layer: a config-only edit leaves
# every layer above it, the Maven stage included, cached, so it rebuilds in
# seconds. Selected in Compose with `target: config-server`.
FROM runtime AS config-server
COPY config-repo /config-repo
ENV CONFIG_REPO=file:/config-repo

# The default target is the last stage, so this must stay last: every other
# service builds exactly the runtime image it built before.
FROM runtime
```

`config-repo/` is inside the build context (the repository root) and not excluded by `.dockerignore`. BuildKit builds only the stages the requested target depends on, so no other service's build touches `config-server`.

- [ ] **Step 2: Build config-server from that target, with no volume**

In `deploy/docker-compose.yml`, the `config-server` service becomes:

```yaml
  config-server:
    build: { context: .., dockerfile: deploy/Dockerfile.service, target: config-server, args: { MODULE_PATH: infrastructure/config-server } }
    profiles: ["platform"]
    environment:
      # Best-effort span export: ...existing comment unchanged...
      ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans
    # No volume: config-repo/ is baked into the image (deploy/Dockerfile.service,
    # target config-server). For live edits run
    #   docker compose -f deploy/docker-compose.yml --profile platform watch config-server
    # (or `up --watch`): Compose copies each changed file into the running
    # container through the Docker API, which needs no file sharing either, and
    # the native backend re-reads its files on every request. Without Watch, an
    # edit takes `up -d --build config-server`, which rebuilds only the last layer.
    develop:
      watch:
        - action: sync
          path: ../config-repo
          target: /config-repo
    ports: ["127.0.0.1:8888:8888"]
    mem_limit: 512m
    # Unchanged. Keep the anchor: every other Java service merges *java-health.
    healthcheck: &java-health
      # ...unchanged...
```

Remove the `CONFIG_REPO: file:/config-repo` environment entry (the image sets it) and the `volumes:` block.

- [ ] **Step 3: Update the backend comment**

In `infrastructure/config-server/src/main/resources/application.yml`, keep `spring.profiles.active: native` and `search-locations: ${CONFIG_REPO:file:./config-repo}` exactly, and replace the two comments so they say where the files come from: in Docker, the image's `/config-repo` (baked at build time, kept live by Compose Watch); from the IDE, `./config-repo` relative to the repository root.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q -pl infrastructure/config-server test`
Expected: PASS — no Java changed; `ConfigServerApplicationTests` already pins the native backend.

- [ ] **Step 5: Verify live**

A Phase 5 stack may already be running from a throwaway override (`deploy/docker-compose.yml` plus a scratchpad file that swaps the binds for named volumes). Recreate only config-server, from the real Compose file:

```bash
docker compose -f deploy/docker-compose.yml --profile platform build config-server
docker compose -f deploy/docker-compose.yml --profile platform up -d --no-deps config-server
docker inspect bloom-v2-config-server-1 --format '{{json .Mounts}}'      # expect []
curl -s http://localhost:8888/storage-service/default | head -c 600      # storage-service.yml's properties
```

Then prove the two edit paths:

1. **Rebuild.** Add a harmless line to a config file (e.g. a comment in `config-repo/retrieval-service.yml`), time `docker compose … build config-server`, and record it — expected seconds, with every layer but the last cached. Revert the edit.
2. **Watch.** Start `docker compose -f deploy/docker-compose.yml --profile platform watch --no-up config-server` in the background. Create `config-repo/watch-probe.yml` containing `bloom.watch-probe: synced`, and within a few seconds `curl -s http://localhost:8888/watch-probe/default` must contain `synced`. Delete the probe file (confirm it disappears from the container, or remove it with `docker exec` if Watch does not propagate deletes, and say which), then stop Watch.

Expected: no mounts on the container, the same properties Phase 5 served, and both edit paths working on this machine, where E: is not in Docker Desktop's File Sharing list. If Watch cannot sync without file sharing, stop and report — it is the live-edit story this task depends on.

- [ ] **Step 6: Commit**

```bash
git add deploy/Dockerfile.service deploy/docker-compose.yml \
        infrastructure/config-server/src/main/resources/application.yml README.md
git commit -m "feat(config): the Config Server image carries config-repo, so Compose needs no config mount"
```

---

### Task 4: Encrypt the secrets that currently live in the environment

**Files:**
- Modify: `infrastructure/config-server/src/main/resources/application.yml`
- Modify: `infrastructure/config-server/src/test/java/com/bloom/eventmanager/config/ConfigServerApplicationTests.java`
- Modify: `infrastructure/config-server/src/test/resources/test-config-repo/knowledge-service.yml`
- Modify: `config-repo/application.yml`, `config-repo/storage-service.yml`
- Modify: `infrastructure/api-gateway/src/main/resources/application.yml`
- Modify: `deploy/docker-compose.yml`, `deploy/.env.example`, `README.md`
- Local only, never committed: `deploy/.env` (gitignored) gains `ENCRYPT_KEY`

**Interfaces:**
- Produces: `POST /encrypt` and `POST /decrypt` on the Config Server; config values may be written as `'{cipher}<ciphertext>'` and are decrypted before being served, whatever the backend.
- Produces: `bloom.security.internal-token` and `bloom.storage.client-secret` arrive at clients as plaintext from the Config Server; neither `INTERNAL_SERVICE_TOKEN` nor `GOOGLE_CLIENT_SECRET` is passed to any container.

Spring Cloud Config's symmetric encryption lets `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET` be committed as ciphertext, leaving exactly one shared secret in the environment: `ENCRYPT_KEY`. The committed ciphertext belongs to this deployment: a new deployment generates its own `ENCRYPT_KEY` and re-encrypts its own values.

**Handling real secrets.** Steps 3–5 read real values from `deploy/.env`. Never print, log, echo, or write a plaintext secret anywhere except `deploy/.env` — not to the terminal, a report, a commit, or a scratch file. Pipe values straight from `.env` into `curl`, and compare served values against `.env` by exit status or a match/mismatch line only. Ciphertext is safe to print and commit.

- [ ] **Step 1: Write the failing test**

In `ConfigServerApplicationTests`, add a test-only key to the `@SpringBootTest` properties under the environment variable's name — `"ENCRYPT_KEY=test-only-encrypt-key-not-a-secret"`, the same way the class already sets `ZIPKIN_ENDPOINT`, so the test passes only once `application.yml` maps `ENCRYPT_KEY` to `encrypt.key` — and a test:

```java
    /**
     * {cipher} values are decrypted before they are served, for the native backend
     * as for any other: clients never see ciphertext and never need the key.
     */
    @Test
    void servesCipherValuesDecrypted() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/knowledge-service/default", String.class);

        assertThat(response.getBody()).contains("decrypted-ok");
        assertThat(response.getBody()).doesNotContain("{cipher}");
    }
```

and in `test-config-repo/knowledge-service.yml` a value encrypted under that test key (produce it with Step 3's `/encrypt` call against a server started with the test key; it is a test fixture, not a secret):

```yaml
bloom:
  config-server:
    decrypted: '{cipher}<ciphertext of "decrypted-ok" under the test key>'
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl infrastructure/config-server test -Dtest=ConfigServerApplicationTests`
Expected: FAIL on `servesCipherValuesDecrypted` — `application.yml` does not yet map `ENCRYPT_KEY` to `encrypt.key`, so the server has no key, cannot decrypt the fixture, and never serves `decrypted-ok`.

- [ ] **Step 3: Turn on encryption**

Add to the Config Server's `application.yml`:

```yaml
encrypt:
  # The one secret that cannot live in config-repo. 32+ random bytes. Deliberately
  # no default: without the key the server would serve every {cipher} value as
  # invalid.<name>, and clients would fail later and more confusingly.
  key: ${ENCRYPT_KEY}
```

The test supplies `ENCRYPT_KEY` itself, so the suite stays hermetic and still proves the mapping.

Generate the real key into `deploy/.env` without printing it:

```bash
printf '\nENCRYPT_KEY=%s\n' "$(openssl rand -base64 32)" >> deploy/.env
```

Round trip (`/encrypt` takes the raw body, so send `text/plain`, not a form):

```bash
curl -s -X POST -H 'Content-Type: text/plain' --data-binary 'test-value' http://localhost:8888/encrypt
curl -s -X POST -H 'Content-Type: text/plain' --data-binary '<the ciphertext>' http://localhost:8888/decrypt
```

Expected: `/decrypt` returns `test-value`. If `/encrypt` returns `NO_KEY` or `NOT_INSTALLED`, stop and report.

Run: `./mvnw -q -pl infrastructure/config-server test`
Expected: PASS.

- [ ] **Step 4: Move the secrets**

With a Config Server running on the real `ENCRYPT_KEY`, encrypt `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET` from `deploy/.env` (piped, never printed), and replace the placeholders. `config-repo/application.yml`:

```yaml
bloom:
  security:
    internal-token: '{cipher}<ciphertext>'
```

`config-repo/storage-service.yml`:

```yaml
bloom:
  storage:
    client-secret: '{cipher}<ciphertext>'
```

Single quotes are required: YAML would otherwise read `{cipher}…` as a flow mapping.

The gateway does not read `bloom.security.internal-token`; its own `application.yml` binds `bloom.auth.internal-token: ${INTERNAL_SERVICE_TOKEN}`. Point it at the shared value it already receives from `config-repo/application.yml`, so the gateway and the services have one source:

```yaml
    internal-token: ${bloom.security.internal-token}
```

In `deploy/docker-compose.yml`: remove `INTERNAL_SERVICE_TOKEN` from the `x-service-env` anchor and `GOOGLE_CLIENT_SECRET` from storage-service's environment; give config-server `ENCRYPT_KEY: ${ENCRYPT_KEY:?set ENCRYPT_KEY in deploy/.env}` so a missing key fails `compose up` immediately. Update the anchor's comment. In `deploy/.env.example`: remove `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET`, add `ENCRYPT_KEY=` with a one-line comment on generating it. Leave `JWT_PRIVATE_KEY_PEM` and `JWT_PUBLIC_KEY_PEM` in the gateway's environment: the gateway reads them before it can talk to anything, and a signing key is worth keeping out of config entirely. `NEO4J_PASSWORD` stays too — the neo4j container itself needs it from the environment.

- [ ] **Step 5: Verify decryption live**

Recreate config-server with the real key and check what it serves — by match/mismatch only:

```bash
docker compose -f deploy/docker-compose.yml --profile platform up -d --build --no-deps config-server
# served internal token == .env's INTERNAL_SERVICE_TOKEN ? print "match"/"MISMATCH", never the value
# same for storage-service's bloom.storage.client-secret vs .env's GOOGLE_CLIENT_SECRET
# and: no "{cipher}" and no "invalid." key anywhere in /application/default or /storage-service/default
```

The whole fleet starting and authenticating peers with neither variable in its environment is proven by Task 5's full-stack bring-up.

- [ ] **Step 6: Document**

README: an "Encrypted configuration" subsection under "Login and security" — what `ENCRYPT_KEY` is, how to encrypt a new value (`/encrypt` with `text/plain`), single-quoting, that the committed ciphertext is this deployment's, and that the Config Server serves **decrypted** values to any caller that can reach it: port 8888 is bound to `127.0.0.1` and otherwise reachable only on the Compose network, the same trust boundary as the anonymous Eureka registry. Update the environment-variable table (`ENCRYPT_KEY` added; `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET` now config-repo ciphertext) and the dev run mode (the Config Server needs `ENCRYPT_KEY`; services no longer need `INTERNAL_SERVICE_TOKEN`).

- [ ] **Step 7: Commit**

```bash
git add infrastructure/config-server/src/main/resources/application.yml \
        infrastructure/config-server/src/test/java/com/bloom/eventmanager/config/ConfigServerApplicationTests.java \
        infrastructure/config-server/src/test/resources/test-config-repo/knowledge-service.yml \
        infrastructure/api-gateway/src/main/resources/application.yml \
        config-repo/application.yml config-repo/storage-service.yml \
        deploy/docker-compose.yml deploy/.env.example README.md
git commit -m "feat(config): encrypt shared secrets with {cipher}, leaving only ENCRYPT_KEY in the environment"
```

---

### Task 5: Compose without any host mount

**Files:**
- Modify: `deploy/docker-compose.yml` — ingestion-service's PDF volume
- Modify: `deploy/.env.example`
- Modify: `README.md`

This is the payoff for the whole phase: Compose stops needing any host path, which is what blocked Phase 5's full-stack Compose verification on a machine whose Docker file sharing did not include the drive. Task 3 removed the Config Server's `../config-repo` mount; the other is ingestion-service's `${PDF_DIRECTORY:-./pdfs}:/data/pdfs:ro`. Removing only the first leaves Compose needing file sharing, and the exit criterion below still fails.

- [ ] **Step 1: Replace ingestion-service's PDF bind mount with a named volume**

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

`bloom-v2_ingest-pdfs` is Compose's `<project>_<volume>` name for the project `bloom-v2`. Drive-sourced PDFs never touch this volume: ingestion downloads those from storage-service. Document the loading commands in the README under "Running it". (Git Bash needs `MSYS_NO_PATHCONV=1` in front of `docker run -v name:/path`.)

- [ ] **Step 2: Verify the full stack**

Bring the whole stack up from `deploy/docker-compose.yml` alone — no override file:

```bash
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps up -d --build
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps ps
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps config | grep -c "type: bind"
```

Expected: all sixteen containers healthy within ~3 minutes — `frontend` included — with no Docker Desktop file-sharing configuration, and `0` host binds. Also confirm: no container's environment carries `INTERNAL_SERVICE_TOKEN` or `GOOGLE_CLIENT_SECRET` (`docker inspect … --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -c` per container, printing counts only); login through the gateway succeeds; a query that fans out across services (e.g. `/api/v1/rag/retrieve`) answers 200, which proves the internal token decrypted from config authenticates peer calls; and a PDF copied in with Step 1's loader is visible at `/data/pdfs` inside ingestion-service.

- [ ] **Step 3: Record the deviation and retire the Phase 5 risk**

README: in the spec deviations log, reword the Config Server row — native backend kept, config baked into the image with Compose Watch for live edits; reason: one deployment, config versioned with the code in the same commits, no deploy credential or boot-time network dependency; move to a git backend when several deployments share config. Close out "Known risks carried into Phase 6" for the items this phase resolved, and add the Phase 6 "does not do" follow-ups (Config Server and Eureka unauthenticated inside the Compose network).

- [ ] **Step 4: Commit**

```bash
git add deploy/docker-compose.yml deploy/.env.example README.md
git commit -m "chore(deploy): PDFs live in a named volume, so Compose needs no host mount"
```

---

## Phase 6 Exit Criteria

- [ ] The Google OAuth callback rejects any `code` that arrives without a matching, unexpired, single-use `state`, and does not exchange it or start the sync scheduler
- [ ] A nonce can be redeemed exactly once, and expires on its own via a Mongo TTL index
- [ ] The config-server image carries `config-repo/` and serves it with no mount; `docker compose … watch config-server` syncs an edit into the running container, served on the next request, on a machine whose drive is not in Docker Desktop's File Sharing
- [ ] Host runs still serve `./config-repo` from the repository root
- [ ] `INTERNAL_SERVICE_TOKEN` and `GOOGLE_CLIENT_SECRET` are `{cipher}` ciphertext in `config-repo/`, and no container receives either in its environment; `ENCRYPT_KEY` is the only shared secret left in the environment
- [ ] `docker compose --profile infra --profile platform --profile apps up -d --build` brings up all sixteen containers healthy, `frontend` included, **with no Docker file-sharing configuration** — neither the config-repo mount nor the PDF mount remains
- [ ] `./mvnw clean install` passes with no skipped tests
- [ ] No plaintext secret anywhere in the repository

## What Phase 6 Does Not Do

- No git backend for the Config Server — config is versioned with the code in this repository and copied into the image. Move to a git remote when more than one deployment needs to share config
- No per-user accounts — still one shared login, as V1 had
- No asymmetric config encryption (a keystore); the symmetric `ENCRYPT_KEY` is enough for one deployment
- No authentication on the Config Server — it serves decrypted values to anything that can reach it, which Compose limits to `127.0.0.1` and the Compose network
- No automatic `/actuator/refresh` fan-out on a config edit — each client is still refreshed deliberately, with an authenticated `POST /actuator/refresh`
- No authentication on Eureka — `discovery-server` still accepts anonymous registrations, mitigated only by Compose binding 8761 to `127.0.0.1`; HTTP Basic on the registry, with credentials in every client's `defaultZone`, is the follow-up
- No Kubernetes; Compose remains the deployment target
