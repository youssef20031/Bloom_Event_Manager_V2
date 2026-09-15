# Phase 5 — Cutover and Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a JWT gate on the gateway, lift the React SPA out of V1 and point it at the gateway, and finish the cross-cutting layer the spec promised — distributed tracing into Zipkin, circuit breakers on every external and inter-service boundary, readiness groups, containers for the whole stack, and a README a reviewer can follow. When this phase ends the SPA runs entirely against the gateway and a single `/api/v1/rag/chat` request produces one trace across five services.

**Architecture:** The gateway issues RS256 JWTs from `POST /api/v1/auth/login` and publishes its public key at `/.well-known/jwks.json`. Every downstream service validates the same JWT through a shared `bloom-security` library, and also accepts an `X-Internal-Token` header the gateway stamps on forwarded traffic — because Kafka-triggered service-to-service calls have no user. The browser never reaches a service directly. Tracing is Micrometer/Brave → Zipkin; breakers are Spring Cloud CircuitBreaker over Resilience4j, expressed as Feign fallbacks between services and `CircuitBreakerFactory` wrappers around Gemini and Drive.

**Tech Stack:** Spring Boot 4.0.8, Spring Cloud 2025.1.3, Spring Security 7.0.7, Spring Cloud CircuitBreaker (Resilience4j 2.3.0), Micrometer Tracing (Brave) + Zipkin, Vite 6 + React 19 + Vitest, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` (sections 9, 10, 13)

**Prerequisite:** Phase 4 complete — `chat-service` (:8085) and `proposal-service` (:8087) registered, gateway routes for `/api/v1/rag/chat/**`, `/api/v1/rag/proposals/**`, `/api/v1/rag/generate-proposal`, `/api/v1/rag/event-intake` in place.

## Global Constraints

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.** Spring Security is `7.0.7` (managed): lambda DSL only, `requestMatchers(String...)` / `pathMatchers(String...)`.
- **Boot 4.0 test modules:** `spring-boot-webmvc-test` (`@WebMvcTest`, `@AutoConfigureMockMvc` at `org.springframework.boot.webmvc.test.autoconfigure`), `spring-boot-webtestclient` (`@AutoConfigureWebTestClient` at `org.springframework.boot.webtestclient.autoconfigure`), `spring-boot-security-test` (`SecurityMockMvcRequestPostProcessors.jwt()`), `@MockitoBean`.
- **JSON in application code is Jackson 3** (`tools.jackson.databind.json.JsonMapper`); annotations stay `com.fasterxml.jackson.annotation`.
- **Gateway route rewrites keep the `$\{rest}` escaping** from Phases 2–3.
- **Secrets are environment only.** `JWT_PRIVATE_KEY_PEM`, `JWT_PUBLIC_KEY_PEM`, `INTERNAL_SERVICE_TOKEN`, `APP_AUTH_USER`, `APP_AUTH_PASSWORD` are never written into `config-repo/`, tests, or the README. Generated dev keys are ephemeral and logged as such.
- **`.gitignore` gains `frontend/node_modules/`, `frontend/dist/`, `deploy/.env`.**
- **Kafka now advertises two listeners:** `kafka:9092` for containers, `localhost:29092` for the host. Host-run services default to `localhost:29092`. This changes Phase 3's default.
- **Commit with explicit paths, never `git add -A`.**

---

## File Structure

```
platform/bloom-common/.../error/ErrorCode.java                    MODIFY: SERVICE_UNAVAILABLE
platform/bloom-common/.../error/ServiceUnavailableException.java  new
platform/bloom-security/                                          new library module
  pom.xml
  src/main/java/com/bloom/eventmanager/security/
    BloomSecurityProperties.java
    BloomSecurityAutoConfiguration.java
    InternalTokenFilter.java
    InternalTokenFeignInterceptor.java
  src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/com/bloom/eventmanager/security/
    SecurityProbeApplication.java, ProbeController.java, BloomSecurityIntegrationTests.java,
    InternalTokenFeignInterceptorTest.java
infrastructure/api-gateway/
  pom.xml                                                         MODIFY
  .../gateway/auth/AuthProperties.java, JwtKeys.java, JwtIssuer.java, AuthController.java,
                   JwksController.java, GatewaySecurityConfig.java, ProblemDetailEntryPoint.java,
                   InternalTokenGatewayFilter.java
  test: auth/JwtIssuerTest, auth/GatewaySecurityTests, auth/InternalTokenGatewayFilterTest
services/*/pom.xml                                                MODIFY: bloom-security, zipkin, circuitbreaker, feign-micrometer
services/retrieval-service/.../client/AiClientFallback.java, KnowledgeClientFallback.java
services/chat-service/.../client/*Fallback.java, services/proposal-service/.../client/*Fallback.java
services/ingestion-service/.../client/*Fallback.java, services/storage-service/.../client/KnowledgeClientFallback.java
services/ai-service/.../gemini/GeminiEmbeddingService.java, GeminiGenerationService.java   MODIFY: breaker
services/storage-service/.../drive/GoogleDriveClient.java         MODIFY: breaker
frontend/                                                         new
  package.json, vite.config.ts, tsconfig.json, index.html, Dockerfile, nginx.conf
  src/**  (copied from V1 src/client) + services/apiFetch.ts, services/auth.ts,
          components/LoginPage.tsx, components/LoginPage.css, components/RequireAuth.tsx
  src/services/apiFetch.test.ts
deploy/Dockerfile.service, services/ingestion-service/Dockerfile, deploy/docker-compose.yml (MODIFY)
config-repo/application.yml (MODIFY), config-repo/*.yml (MODIFY: readiness groups, permit-all)
README.md                                                         REWRITE
```

---

### Task 1: Groundwork — new error code, shared config, ignores

**Files:**
- Modify: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/ErrorCode.java`
- Create: `platform/bloom-common/src/main/java/com/bloom/eventmanager/common/error/ServiceUnavailableException.java`
- Modify: `platform/bloom-common/src/test/java/com/bloom/eventmanager/common/error/GlobalExceptionHandlerTest.java`
- Modify: `config-repo/application.yml`, `.gitignore`

**Interfaces:**
- Produces: `ErrorCode.SERVICE_UNAVAILABLE` (503, "Service Temporarily Unavailable"); `ServiceUnavailableException(String message, Throwable cause)` — what Feign fallbacks throw when a peer is down.

- [ ] **Step 1: Write the failing test** — append to `GlobalExceptionHandlerTest`:

```java
    @Test
    void serviceUnavailableMapsTo503() {
        ProblemDetail problem = handler.handleBloom(
                new ServiceUnavailableException("retrieval-service is unavailable", new RuntimeException("open")));

        assertThat(problem.getStatus()).isEqualTo(503);
        assertThat(problem.getTitle()).isEqualTo("Service Temporarily Unavailable");
        assertThat(problem.getProperties()).containsEntry("errorCode", "SERVICE_UNAVAILABLE");
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: COMPILATION FAILURE — `cannot find symbol: class ServiceUnavailableException`

- [ ] **Step 3: Implement**

`ErrorCode.java` — add after `DRIVE_NOT_AUTHENTICATED`:

```java
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service Temporarily Unavailable"),
```

`ServiceUnavailableException.java`:

```java
package com.bloom.eventmanager.common.error;

/** A peer service's circuit is open or it cannot be reached. Retry later; nothing is wrong with the request. */
public class ServiceUnavailableException extends BloomException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, cause);
    }
}
```

Append to `config-repo/application.yml`:

```yaml
# ── Security (Phase 5) ─────────────────────────────────────────────────────
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # The gateway publishes the RS256 public key; every service validates against it.
          jwk-set-uri: ${JWT_JWKS_URI:http://localhost:8080/.well-known/jwks.json}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:29092}
    template:
      observation-enabled: true
    listener:
      observation-enabled: true

bloom:
  security:
    # Stamped by the gateway on forwarded requests; lets Kafka-triggered
    # service-to-service calls (no user, no JWT) authenticate as SERVICE.
    internal-token: ${INTERNAL_SERVICE_TOKEN}

# ── Tracing (Phase 5) ──────────────────────────────────────────────────────
management:
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: ${ZIPKIN_ENDPOINT:http://localhost:9411/api/v2/spans}
  info:
    env:
      enabled: true

# ── Circuit breakers (Phase 5) ─────────────────────────────────────────────
spring.cloud.openfeign.circuitbreaker.enabled: true

resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
  timelimiter:
    configs:
      default:
        # Gemini generation can take a while; the default 1s would trip on every call.
        timeoutDuration: 20s
```

The existing `spring.kafka.bootstrap-servers` line from Phase 3a is replaced by the one above (port 29092). Merge the two `spring:` and `management:` blocks into the existing ones — YAML does not allow duplicate top-level keys.

Append to `.gitignore`:

```
frontend/node_modules/
frontend/dist/
deploy/.env
```

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q -pl platform/bloom-common test`
Expected: PASS — 8 tests in `GlobalExceptionHandlerTest`

- [ ] **Step 5: Commit**

```bash
git add platform/bloom-common config-repo/application.yml .gitignore
git commit -m "feat(common): SERVICE_UNAVAILABLE; shared security, tracing and breaker config"
```

---

### Task 2: Gateway — JWT issuance, JWKS, security chain, internal token

**Files:**
- Modify: `infrastructure/api-gateway/pom.xml`
- Create: `infrastructure/api-gateway/src/main/java/com/bloom/eventmanager/gateway/auth/AuthProperties.java`
- Create: `.../gateway/auth/JwtKeys.java`, `JwtIssuer.java`, `AuthController.java`, `JwksController.java`, `GatewaySecurityConfig.java`, `ProblemDetailEntryPoint.java`, `InternalTokenGatewayFilter.java`
- Modify: `infrastructure/api-gateway/src/main/resources/application.yml`
- Test: `.../gateway/auth/JwtIssuerTest.java`, `GatewaySecurityTests.java`, `InternalTokenGatewayFilterTest.java`

**Interfaces:**
- Produces: `POST /api/v1/auth/login {username, password}` → `{token, tokenType: "Bearer", expiresIn}`; `GET /.well-known/jwks.json`; every other route requires `Authorization: Bearer <jwt>` except `/actuator/health/**`, `/api/rag/auth/google/callback`, `/api/v1/rag/sync/auth/callback`. Forwarded requests carry `X-Internal-Token`; incoming ones have it stripped.
- `AuthProperties(String user, String password, String privateKeyPem, String publicKeyPem, Duration tokenTtl, String internalToken)` — `bloom.auth`.

Key generation for real deployments:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem   # PKCS#8
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem                            # X.509 SubjectPublicKeyInfo
export JWT_PRIVATE_KEY_PEM="$(cat jwt-private.pem)"
export JWT_PUBLIC_KEY_PEM="$(cat jwt-public.pem)"
```

- [ ] **Step 1: Write the failing tests**

`JwtIssuerTest.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtIssuerTest {

    private final AuthProperties properties = new AuthProperties("bloom", "correct horse battery staple",
            "", "", Duration.ofHours(12), "internal-secret");
    private final JwtKeys keys = JwtKeys.from(properties);
    private final JwtIssuer issuer = new JwtIssuer(keys, properties);

    @Test
    void issuesAnRs256TokenValidatableWithThePublicKey() {
        String token = issuer.issue("bloom");

        Jwt decoded = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(token);
        assertThat(decoded.getSubject()).isEqualTo("bloom");
        assertThat(decoded.getIssuer()).hasToString("bloom-gateway");
        assertThat(decoded.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", keys.keyId());
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(11)));
    }

    @Test
    void checksCredentialsInConstantTimeAndRejectsWrongOnes() {
        assertThat(issuer.credentialsMatch("bloom", "correct horse battery staple")).isTrue();
        assertThat(issuer.credentialsMatch("bloom", "wrong")).isFalse();
        assertThat(issuer.credentialsMatch("someone", "correct horse battery staple")).isFalse();
        assertThat(issuer.credentialsMatch(null, null)).isFalse();
    }

    @Test
    void generatesAnEphemeralKeyPairWhenNoPemIsConfigured() {
        assertThat(keys.ephemeral()).isTrue();
        assertThat(keys.publicKey().getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void loadsAConfiguredPemPair() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());

        JwtKeys loaded = JwtKeys.from(new AuthProperties("u", "p", privatePem, publicPem, Duration.ofHours(1), "t"));

        assertThat(loaded.ephemeral()).isFalse();
        assertThat(loaded.publicKey().getModulus()).isEqualTo(((java.security.interfaces.RSAPublicKey) pair.getPublic()).getModulus());
    }

    @Test
    void refusesHalfAKeyPair() {
        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties("u", "p", "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----", "", Duration.ofHours(1), "t")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both");
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
```

`GatewaySecurityTests.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.import=",
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "bloom.auth.user=bloom",
                "bloom.auth.password=correct horse battery staple",
                "bloom.auth.internal-token=internal-secret"
        })
@AutoConfigureWebTestClient
class GatewaySecurityTests {

    @Autowired
    private WebTestClient client;

    @Test
    void loginWithGoodCredentialsReturnsABearerToken() {
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\": \"bloom\", \"password\": \"correct horse battery staple\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tokenType").isEqualTo("Bearer")
                .jsonPath("$.expiresIn").isEqualTo(43200)
                .jsonPath("$.token").value(token -> assertThat(token.toString().split("\\.")).hasSize(3));
    }

    @Test
    void loginWithBadCredentialsIs401ProblemDetail() {
        client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\": \"bloom\", \"password\": \"nope\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void jwksExposesOneRsaSigningKey() {
        client.get().uri("/.well-known/jwks.json").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.keys.length()").isEqualTo(1)
                .jsonPath("$.keys[0].kty").isEqualTo("RSA")
                .jsonPath("$.keys[0].use").isEqualTo("sig")
                .jsonPath("$.keys[0].kid").exists()
                .jsonPath("$.keys[0].d").doesNotExist();
    }

    @Test
    void protectedRoutesRejectAnonymousCallsWithAJsonProblem() {
        client.get().uri("/api/v1/rag/ping").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.errorCode").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void aFreshTokenGetsPastSecurity() {
        String token = client.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\": \"bloom\", \"password\": \"correct horse battery staple\"}")
                .exchange().expectBody(LoginResponse.class).returnResult().getResponseBody().token();

        // No route matches this path, so the gateway answers 404 — proof the request cleared security.
        client.get().uri("/api/v1/rag/no-such-route").header("Authorization", "Bearer " + token).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void googleCallbackPathsNeedNoToken() {
        client.get().uri("/api/rag/auth/google/callback?code=x").exchange()
                .expectStatus().isEqualTo(307);
    }

    @Test
    void healthNeedsNoToken() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    record LoginResponse(String token, String tokenType, long expiresIn) {
    }
}
```

`InternalTokenGatewayFilterTest.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenGatewayFilterTest {

    private final InternalTokenGatewayFilter filter = new InternalTokenGatewayFilter(
            new AuthProperties("u", "p", "", "", Duration.ofHours(1), "internal-secret"));

    @Test
    void stripsAForgedHeaderFromAnonymousTraffic() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("X-Internal-Token", "forged"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, ex -> { forwarded.set(ex); return Mono.empty(); }).block();

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Internal-Token")).isNull();
    }

    @Test
    void stampsTheRealTokenOnAuthenticatedTraffic() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/x").header("X-Internal-Token", "forged"))
                .principal(Mono.just(UsernamePasswordAuthenticationToken.authenticated("bloom", null, java.util.List.of())))
                .build();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, ex -> { forwarded.set(ex); return Mono.empty(); }).block();

        assertThat(forwarded.get().getRequest().getHeaders().get("X-Internal-Token")).containsExactly("internal-secret");
    }
}
```

- [ ] **Step 2: Update the POM** — add to `infrastructure/api-gateway/pom.xml`:

```xml
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-zipkin</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl infrastructure/api-gateway test -Dtest=JwtIssuerTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class JwtKeys`

- [ ] **Step 4: Implement**

`auth/AuthProperties.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The successor to V1's APP_AUTH_USER / APP_AUTH_PASSWORD gate. One shared
 * login, as before; what changes is that the gateway now hands out a JWT the
 * services can verify on their own instead of re-checking Basic auth.
 */
@Validated
@ConfigurationProperties(prefix = "bloom.auth")
public record AuthProperties(
        @NotBlank String user,
        @NotBlank String password,
        @DefaultValue("") String privateKeyPem,
        @DefaultValue("") String publicKeyPem,
        @DefaultValue("PT12H") Duration tokenTtl,
        @NotBlank String internalToken) {
}
```

`auth/JwtKeys.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.converter.RsaKeyConverters;

/** The gateway's signing key pair: configured PEMs in production, an ephemeral pair in development. */
public record JwtKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey, RSAKey jwk, boolean ephemeral) {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    public static JwtKeys from(AuthProperties properties) {
        boolean hasPrivate = !properties.privateKeyPem().isBlank();
        boolean hasPublic = !properties.publicKeyPem().isBlank();
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("bloom.auth.private-key-pem and public-key-pem must both be set, or neither");
        }
        if (hasPrivate) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8()
                    .convert(new ByteArrayInputStream(properties.privateKeyPem().getBytes(StandardCharsets.UTF_8)));
            RSAPublicKey publicKey = RsaKeyConverters.x509()
                    .convert(new ByteArrayInputStream(properties.publicKeyPem().getBytes(StandardCharsets.UTF_8)));
            return new JwtKeys(publicKey, privateKey, jwk(publicKey, privateKey), false);
        }
        log.warn("No JWT key pair configured — generating an EPHEMERAL RSA key. Every token dies with this process. Dev only.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
            return new JwtKeys(publicKey, privateKey, jwk(publicKey, privateKey), true);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA is mandatory in every JVM", ex);
        }
    }

    public String keyId() {
        return jwk.getKeyID();
    }

    private static RSAKey jwk(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            RSAKey unnamed = new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE).build();
            return new RSAKey.Builder(unnamed).keyID(unnamed.computeThumbprint().toString()).build();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException("Cannot compute JWK thumbprint", ex);
        }
    }
}
```

`auth/JwtIssuer.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
public class JwtIssuer {

    static final String ISSUER = "bloom-gateway";

    private final JwtKeys keys;
    private final AuthProperties properties;
    private final NimbusJwtEncoder encoder;

    public JwtIssuer(JwtKeys keys, AuthProperties properties) {
        this.keys = keys;
        this.properties = properties;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(keys.jwk())));
    }

    public String issue(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(properties.tokenTtl()))
                .claim("scope", "user")
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** V1's safeEqual: compare fixed-length digests so neither content nor length leaks through timing. */
    public boolean credentialsMatch(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        boolean userOk = MessageDigest.isEqual(sha256(username), sha256(properties.user()));
        boolean passOk = MessageDigest.isEqual(sha256(password), sha256(properties.password()));
        return userOk & passOk;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
```

`auth/AuthController.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresIn) {
    }

    private final JwtIssuer issuer;
    private final AuthProperties properties;

    public AuthController(JwtIssuer issuer, AuthProperties properties) {
        this.issuer = issuer;
        this.properties = properties;
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        if (!issuer.credentialsMatch(request.username(), request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ProblemDetailEntryPoint.unauthenticated("Invalid credentials"));
        }
        return ResponseEntity.ok(new LoginResponse(issuer.issue(request.username()), "Bearer", properties.tokenTtl().toSeconds()));
    }
}
```

`auth/JwksController.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public key only. Services fetch this once and cache it. */
@RestController
public class JwksController {

    private final JwtKeys keys;

    public JwksController(JwtKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(keys.jwk().toPublicJWK()).toJSONObject();
    }
}
```

`auth/ProblemDetailEntryPoint.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** 401 as RFC 9457 JSON, matching the ProblemDetails the services emit, instead of a WWW-Authenticate challenge. */
public class ProblemDetailEntryPoint implements ServerAuthenticationEntryPoint {

    private final JsonMapper json;

    public ProblemDetailEntryPoint(JsonMapper json) {
        this.json = json;
    }

    static ProblemDetail unauthenticated(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Authentication Required");
        problem.setType(URI.create("https://bloom.eventmanager/problems/unauthenticated"));
        problem.setProperty("errorCode", "UNAUTHENTICATED");
        return problem;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = json.writeValueAsString(unauthenticated("Authentication required")).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
```

`auth/GatewaySecurityConfig.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    JwtKeys jwtKeys(AuthProperties properties) {
        return JwtKeys.from(properties);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtKeys keys) {
        return NimbusReactiveJwtDecoder.withPublicKey(keys.publicKey()).build();
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder, JsonMapper json) {
        ProblemDetailEntryPoint entryPoint = new ProblemDetailEntryPoint(json);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/auth/login", "/.well-known/jwks.json", "/actuator/health/**", "/actuator/health").permitAll()
                        // Google's browser redirect cannot carry a Bearer token.
                        .pathMatchers("/api/rag/auth/google/callback", "/api/v1/rag/sync/auth/callback").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(decoder))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .build();
    }
}
```

`auth/InternalTokenGatewayFilter.java`:

```java
package com.bloom.eventmanager.gateway.auth;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway is the trust boundary. Anything arriving with X-Internal-Token
 * is a forgery and is dropped; authenticated traffic leaving toward a service
 * gets the real one, so the service can accept it even when the original
 * caller had no JWT to forward (Kafka-driven hops).
 */
@Component
public class InternalTokenGatewayFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Internal-Token";

    private final String internalToken;

    public InternalTokenGatewayFilter(AuthProperties properties) {
        this.internalToken = properties.internalToken();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal().map(principal -> true).defaultIfEmpty(false).flatMap(authenticated -> {
            ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
                headers.remove(HEADER);
                if (authenticated) {
                    headers.add(HEADER, internalToken);
                }
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
```

Gateway `application.yml` — add under `management.endpoints.web.exposure.include` nothing new (keep `health,info,gateway`), and add:

```yaml
bloom:
  auth:
    user: ${APP_AUTH_USER}
    password: ${APP_AUTH_PASSWORD}
    private-key-pem: ${JWT_PRIVATE_KEY_PEM:}
    public-key-pem: ${JWT_PUBLIC_KEY_PEM:}
    token-ttl: ${JWT_TTL:PT12H}
    internal-token: ${INTERNAL_SERVICE_TOKEN}
```

Also add `@ConfigurationPropertiesScan` to `ApiGatewayApplication`.

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl infrastructure/api-gateway test`
Expected: PASS — Phase 0–3 gateway tests (7) + 5 + 7 + 2 = 21. The `aFreshTokenGetsPastSecurity` test expects 404: with discovery disabled no route matches an unknown path, and the gateway's default handler returns 404 only once security has admitted the request.

- [ ] **Step 6: Commit**

```bash
git add infrastructure/api-gateway
git commit -m "feat(gateway): RS256 JWT login, JWKS endpoint, security chain, internal token stamping"
```

---

### Task 3: bloom-security — downstream validation library, wired into every service

**Files:**
- Create: `platform/bloom-security/pom.xml`
- Create: `platform/bloom-security/src/main/java/com/bloom/eventmanager/security/BloomSecurityProperties.java`, `BloomSecurityAutoConfiguration.java`, `InternalTokenFilter.java`, `InternalTokenFeignInterceptor.java`
- Create: `platform/bloom-security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `platform/bloom-security/src/test/java/com/bloom/eventmanager/security/SecurityProbeApplication.java`, `ProbeController.java`, `BloomSecurityIntegrationTests.java`, `InternalTokenFeignInterceptorTest.java`
- Modify: `pom.xml` (module, before `platform/bloom-common`'s consumers), `platform/bloom-bom/pom.xml` (manage `bloom-security`), every `services/*/pom.xml` (add `bloom-security`), `config-repo/storage-service.yml` (permit-all)

**Interfaces:**
- Produces: auto-configured `SecurityFilterChain` in every servlet service — `/actuator/health/**` and `bloom.security.permit-all` paths open; everything else needs a JWT validated against the gateway's JWKS **or** a matching `X-Internal-Token`; a Feign `RequestInterceptor` that forwards `Authorization` when there is a current request and always adds `X-Internal-Token`.
- `BloomSecurityProperties(String internalToken, List<String> permitAll)` — `bloom.security`.

- [ ] **Step 1: Write the failing tests**

Test-scope probe application `SecurityProbeApplication.java`:

```java
package com.bloom.eventmanager.security;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@SpringBootConfiguration
@EnableAutoConfiguration
public class SecurityProbeApplication {
}
```

`ProbeController.java` (test scope):

```java
package com.bloom.eventmanager.security;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {

    @GetMapping("/probe/secure")
    public Map<String, String> secure(Authentication authentication) {
        return Map.of("principal", authentication.getName());
    }

    @GetMapping("/probe/open")
    public Map<String, String> open() {
        return Map.of("status", "open");
    }
}
```

`BloomSecurityIntegrationTests.java`:

```java
package com.bloom.eventmanager.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SecurityProbeApplication.class, properties = {
        "bloom.security.internal-token=internal-secret",
        "bloom.security.permit-all=/probe/open",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/never-called"
})
@Import(ProbeController.class)
@AutoConfigureMockMvc
class BloomSecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousCallsAre401() throws Exception {
        mockMvc.perform(get("/probe/secure")).andExpect(status().isUnauthorized());
    }

    @Test
    void theInternalTokenAuthenticatesAsService() throws Exception {
        mockMvc.perform(get("/probe/secure").header("X-Internal-Token", "internal-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("internal-service"));
    }

    @Test
    void aWrongInternalTokenIsStill401() throws Exception {
        mockMvc.perform(get("/probe/secure").header("X-Internal-Token", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidJwtAuthenticatesAsItsSubject() throws Exception {
        mockMvc.perform(get("/probe/secure").with(jwt().jwt(j -> j.subject("bloom"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("bloom"));
    }

    @Test
    void permitAllPathsAndHealthAreOpen() throws Exception {
        mockMvc.perform(get("/probe/open")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
```

`InternalTokenFeignInterceptorTest.java`:

```java
package com.bloom.eventmanager.security;

import java.util.List;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenFeignInterceptorTest {

    private final InternalTokenFeignInterceptor interceptor = new InternalTokenFeignInterceptor(
            new BloomSecurityProperties("internal-secret", List.of()));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void alwaysAddsTheInternalToken() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("X-Internal-Token")).containsExactly("internal-secret");
        assertThat(template.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void forwardsTheCallersBearerTokenWhenThereIsARequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("Authorization")).containsExactly("Bearer abc");
    }
}
```

- [ ] **Step 2: Create the module**

`platform/bloom-security/pom.xml`:

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

    <artifactId>bloom-security</artifactId>
    <name>Bloom Security</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <!-- Only for the RequestInterceptor; services that use Feign already have this. -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Add `<module>platform/bloom-security</module>` to the root `pom.xml` after `platform/bloom-events`. In `platform/bloom-bom/pom.xml` `<dependencyManagement>` add:

```xml
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-security</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl platform/bloom-security test`
Expected: COMPILATION FAILURE — `cannot find symbol: class BloomSecurityProperties`

- [ ] **Step 4: Implement**

`BloomSecurityProperties.java`:

```java
package com.bloom.eventmanager.security;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bloom.security")
public record BloomSecurityProperties(
        @NotBlank String internalToken,
        /** Extra open paths, e.g. storage-service's OAuth callback. */
        @DefaultValue List<String> permitAll) {
}
```

`InternalTokenFilter.java`:

```java
package com.bloom.eventmanager.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts the header the gateway stamps on forwarded traffic and that
 * services stamp on their own Feign calls. Machine identity, not a user:
 * Kafka-triggered hops (storage → knowledge after an indexed event) have no
 * JWT to forward.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expected;

    public InternalTokenFilter(String internalToken) {
        this.expected = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && SecurityContextHolder.getContext().getAuthentication() == null
                && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
        }
        chain.doFilter(request, response);
    }
}
```

`InternalTokenFeignInterceptor.java`:

```java
package com.bloom.eventmanager.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Every outbound Feign call carries the internal token, plus the caller's JWT when one exists. */
public class InternalTokenFeignInterceptor implements RequestInterceptor {

    private final String internalToken;

    public InternalTokenFeignInterceptor(BloomSecurityProperties properties) {
        this.internalToken = properties.internalToken();
    }

    @Override
    public void apply(RequestTemplate template) {
        template.header(InternalTokenFilter.HEADER, internalToken);
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            String authorization = request.getHeader("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                template.header("Authorization", authorization);
            }
        }
    }
}
```

`BloomSecurityAutoConfiguration.java`:

```java
package com.bloom.eventmanager.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Applied to every servlet service via the auto-configuration imports file.
 * JWT validation uses Boot's resource-server auto-config against
 * spring.security.oauth2.resourceserver.jwt.jwk-set-uri (the gateway's JWKS).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(BloomSecurityProperties.class)
public class BloomSecurityAutoConfiguration {

    @Bean
    SecurityFilterChain bloomSecurityFilterChain(HttpSecurity http, BloomSecurityProperties properties) throws Exception {
        List<String> open = new ArrayList<>(List.of("/actuator/health", "/actuator/health/**"));
        open.addAll(properties.permitAll());

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(open.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new InternalTokenFilter(properties.internalToken()), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    @ConditionalOnClass(name = "feign.RequestInterceptor")
    InternalTokenFeignInterceptor internalTokenFeignInterceptor(BloomSecurityProperties properties) {
        return new InternalTokenFeignInterceptor(properties);
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.bloom.eventmanager.security.BloomSecurityAutoConfiguration
```

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl platform/bloom-security test`
Expected: PASS — 5 + 2

- [ ] **Step 6: Wire it into the seven services**

Add to each of `services/{ai,knowledge,retrieval,ingestion,storage,chat,proposal}-service/pom.xml`:

```xml
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-security</artifactId>
        </dependency>
```

(`knowledge-service` and `ai-service` import `bloom-bom`, so no version; the others do too.)

`config-repo/storage-service.yml` — append:

```yaml
bloom:
  security:
    permit-all:
      - /api/v1/storage/auth/callback
```

(The gateway already lets `/api/v1/rag/sync/auth/callback` through; the storage side must too.)

Every existing `@WebMvcTest` and `@SpringBootTest` in the services now runs under security. Add to each test class's `properties` the pair `"bloom.security.internal-token=test-token"`, `"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"`, and to every `@WebMvcTest` request add `.header("X-Internal-Token", "test-token")` — or, less invasively, add a test-scope `src/test/resources/application-test.yml` per service with those two properties and annotate MockMvc requests once via a shared `RequestPostProcessor`. Pick the header approach: it is explicit about what is being tested. Run the whole build:

Run: `./mvnw -q clean install`
Expected: BUILD SUCCESS. Any 401 in a previously green controller test means a request is missing the header.

- [ ] **Step 7: Commit**

```bash
git add pom.xml platform/bloom-bom platform/bloom-security services config-repo/storage-service.yml
git commit -m "feat(security): shared JWT/internal-token validation library wired into every service"
```

---

### Task 4: Frontend cutover

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`
- Copy: `E:\Bloom_Event_Manager\src\client\**` → `frontend/src/**`; `E:\Bloom_Event_Manager\public\**` → `frontend/public/**`
- Create: `frontend/src/services/apiFetch.ts`, `frontend/src/services/auth.ts`, `frontend/src/components/LoginPage.tsx`, `frontend/src/components/LoginPage.css`, `frontend/src/components/RequireAuth.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/components/AppLayout.tsx`, `frontend/src/components/Settings.tsx`, `frontend/src/hooks/useDriveAuthStatus.ts`, and the call sites listed below
- Test: `frontend/src/services/apiFetch.test.ts`

**Interfaces:**
- Produces: a Vite app on `:5173` proxying `/api` and `/.well-known` to the gateway; `apiFetch(input, init)` — `fetch` plus `Authorization: Bearer` from `localStorage['bloom.token']`, and on 401 clears the token and navigates to `/login?next=<current path>`; `login()`, `logout()`, `isLoggedIn()`.

- [ ] **Step 1: Scaffold and copy**

```bash
mkdir -p frontend/src frontend/public
cp -r /e/Bloom_Event_Manager/src/client/. frontend/src/
cp -r /e/Bloom_Event_Manager/public/. frontend/public/
cp /e/Bloom_Event_Manager/index.html frontend/index.html
sed -i 's|/src/client/main.tsx|/src/main.tsx|' frontend/index.html
```

`frontend/package.json`:

```json
{
  "name": "bloom-frontend",
  "private": true,
  "version": "0.0.1",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "preview": "vite preview",
    "test": "vitest run"
  },
  "dependencies": {
    "gsap": "^3.14.2",
    "lucide-react": "^0.575.0",
    "react": "^19.1.0",
    "react-dom": "^19.1.0",
    "react-markdown": "^10.1.0",
    "react-router-dom": "^7.18.1",
    "rehype-raw": "^7.0.0",
    "remark-gfm": "^4.0.1"
  },
  "devDependencies": {
    "@tailwindcss/vite": "^4.2.0",
    "@types/react": "^19.1.2",
    "@types/react-dom": "^19.1.2",
    "@vitejs/plugin-react": "^4.4.1",
    "jsdom": "^26.0.0",
    "tailwindcss": "^4.2.0",
    "typescript": "^5.8.3",
    "vite": "^6.3.3",
    "vitest": "^3.2.0"
  }
}
```

`frontend/vite.config.ts`:

```ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The SPA is no longer served by a backend process. In dev, Vite proxies the
// API to the gateway; in prod, nginx does (frontend/nginx.conf).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/.well-known": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
  },
});
```

`frontend/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "types": ["vitest/globals"]
  },
  "include": ["src"]
}
```

- [ ] **Step 2: Write the failing test** — `frontend/src/services/apiFetch.test.ts`:

```ts
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, TOKEN_KEY } from "./apiFetch";

describe("apiFetch", () => {
  const assign = vi.fn();

  beforeEach(() => {
    localStorage.clear();
    assign.mockReset();
    Object.defineProperty(window, "location", {
      value: { pathname: "/advisor", search: "", assign },
      writable: true,
    });
    globalThis.fetch = vi.fn();
  });

  it("attaches the stored token as a Bearer header", async () => {
    localStorage.setItem(TOKEN_KEY, "tok");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 200 }));

    await apiFetch("/api/v1/rag/chat/sessions");

    const init = (fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer tok");
  });

  it("sends no Authorization header when logged out", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 200 }));

    await apiFetch("/api/v1/rag/sync/status");

    const init = (fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).has("Authorization")).toBe(false);
  });

  it("clears the token and redirects to login on 401", async () => {
    localStorage.setItem(TOKEN_KEY, "expired");
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 401 }));

    const response = await apiFetch("/api/v1/rag/chat");

    expect(response.status).toBe(401);
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(assign).toHaveBeenCalledWith("/login?next=%2Fadvisor");
  });

  it("does not redirect when opted out (used by status polling)", async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValue(new Response("{}", { status: 401 }));

    await apiFetch("/api/v1/rag/sync/status", {}, { redirectOn401: false });

    expect(assign).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Run to verify failure**

Run (in `frontend/`): `npm install && npm test`
Expected: FAIL — `Failed to resolve import "./apiFetch"`

- [ ] **Step 4: Implement the new files**

`frontend/src/services/apiFetch.ts`:

```ts
export const TOKEN_KEY = "bloom.token";

export interface ApiFetchOptions {
  /** Status polling on the landing page must not bounce anonymous visitors to /login. */
  redirectOn401?: boolean;
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * fetch() with the gateway's JWT attached. A 401 means the token is missing,
 * expired, or the gateway's signing key changed: drop it and go log in.
 */
export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
  options: ApiFetchOptions = {},
): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(input, { ...init, headers });

  if (response.status === 401 && options.redirectOn401 !== false) {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable — nothing to clear */
    }
    if (window.location.pathname !== "/login") {
      const next = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.assign(`/login?next=${next}`);
    }
  }

  return response;
}
```

`frontend/src/services/auth.ts`:

```ts
import { TOKEN_KEY, getToken } from "./apiFetch";

export interface LoginResult {
  token: string;
  tokenType: string;
  expiresIn: number;
}

export async function login(username: string, password: string): Promise<void> {
  const response = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) {
    throw new Error(response.status === 401 ? "Invalid username or password." : `Login failed (${response.status}).`);
  }
  const result = (await response.json()) as LoginResult;
  localStorage.setItem(TOKEN_KEY, result.token);
}

export function logout(): void {
  localStorage.removeItem(TOKEN_KEY);
}

export function isLoggedIn(): boolean {
  return getToken() !== null;
}
```

`frontend/src/components/LoginPage.tsx`:

```tsx
import { FormEvent, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { login } from "../services/auth";
import "./LoginPage.css";

export default function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      const next = params.get("next");
      navigate(next && next.startsWith("/") ? next : "/intake", { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Login failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-container">
      <form className="login-card" onSubmit={handleSubmit} aria-labelledby="login-title">
        <h2 id="login-title">Sign in to Bloom</h2>
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        {error && <p className="login-error" role="alert">{error}</p>}
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
```

`frontend/src/components/LoginPage.css`:

```css
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}

.login-card {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 2rem;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
  background: var(--card-bg, #ffffff);
}

.login-card label {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.9rem;
}

.login-card input {
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  font: inherit;
}

.login-error {
  color: #b91c1c;
  margin: 0;
}
```

`frontend/src/components/RequireAuth.tsx`:

```tsx
import { Navigate, Outlet, useLocation } from "react-router-dom";

import { isLoggedIn } from "../services/auth";

export default function RequireAuth() {
  const location = useLocation();
  if (!isLoggedIn()) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  return <Outlet />;
}
```

- [ ] **Step 5: Wire routing, logout, and the call sites**

`App.tsx` — add imports `LoginPage`, `RequireAuth`; replace the route table:

```tsx
    <Routes>
      <Route path="/" element={<LandingRoute />} />
      <Route path="/login" element={<LoginPage />} />

      {/* Authenticated app shell — sidebar navigation + routed pages */}
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="/intake" element={<EventIntakeForm />} />
          <Route path="/proposals" element={<ProposalGenerator />} />
          <Route
            path="/advisor"
            element={
              <div style={{ width: "100%", height: "100%", display: "flex", flexDirection: "column" }}>
                <ChatInterface />
              </div>
            }
          />
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
```

`AppLayout.tsx` — import `logout` from `../services/auth`; add after the Settings `NavLink`:

```tsx
          <button
            type="button"
            className="nav-item nav-item-settings"
            onClick={() => {
              logout();
              navigate("/login", { replace: true });
            }}
          >
            <span className="nav-icon" aria-hidden="true">⎋</span>
            Log out
          </button>
```

`useDriveAuthStatus.ts` — poll only when logged in, never redirect:

```ts
import { useCallback, useEffect, useState } from "react";

import { apiFetch } from "../services/apiFetch";
import { isLoggedIn } from "../services/auth";

export interface DriveAuthStatus {
  isAuthenticated: boolean;
  lastSyncTime: string | null;
  authUrl?: string;
}

/** Polls GET /api/v1/rag/sync/status for Google Drive auth state, only while logged in. */
export function useDriveAuthStatus(pollIntervalMs: number = 30000) {
  const [status, setStatus] = useState<DriveAuthStatus | null>(null);

  const refresh = useCallback(async () => {
    if (!isLoggedIn()) return;
    try {
      const response = await apiFetch("/api/v1/rag/sync/status", {}, { redirectOn401: false });
      if (!response.ok) return;
      const data = await response.json();
      setStatus({ isAuthenticated: data.isAuthenticated, lastSyncTime: data.lastSyncTime, authUrl: data.authUrl });
    } catch (error) {
      console.error("Failed to check Google Drive auth status:", error);
    }
  }, []);

  useEffect(() => {
    refresh();
    if (pollIntervalMs <= 0) return;
    const interval = setInterval(refresh, pollIntervalMs);
    return () => clearInterval(interval);
  }, [refresh, pollIntervalMs]);

  return { status, refresh };
}
```

`Settings.tsx` — the connect handler (storage-service now returns JSON on `force=true`, Phase 3b):

```tsx
    const handleConnect = async () => {
        if (authStatus?.authUrl) {
            window.location.href = authStatus.authUrl;
            return;
        }
        const res = await apiFetch("/api/v1/rag/sync/auth?force=true");
        if (res.ok) {
            const data = await res.json();
            if (data.authUrl) window.location.href = data.authUrl;
        }
    };
```

Mechanical replacement — in each file add `import { apiFetch } from "../services/apiFetch";` and change the call:

| File | Before → After |
|---|---|
| `components/ChatHistorySidebar.tsx:27` | `fetch('/api/v1/rag/chat/sessions')` → `apiFetch('/api/v1/rag/chat/sessions')` |
| `components/ChatInterface.tsx:136` | ``fetch(`/api/v1/rag/chat/sessions/${id}`)`` → `apiFetch(…)` |
| `components/ChatInterface.tsx:166` | `fetch("/api/v1/rag/chat", {…})` → `apiFetch("/api/v1/rag/chat", {…})` |
| `components/ChatInterface.tsx:200` | ``fetch(`…/backup`, { method: "POST" })`` → `apiFetch(…)` |
| `components/EventIntakeForm.tsx:421` | `fetch("/api/v1/rag/event-intake", {…})` → `apiFetch(…)` |
| `components/PdfUploader.tsx:9` | `fetch("/api/v1/rag/ingest", { method: "POST" })` → `apiFetch(…)` |
| `components/ProposalGenerator.tsx:44` | `fetch("/api/v1/rag/proposals/companies")` → `apiFetch(…)` |
| `components/ProposalGenerator.tsx:67` | `fetch("/api/v1/rag/generate-proposal", {…})` → `apiFetch(…)` |
| `components/ProposalGenerator.tsx:95` | `fetch("/api/v1/rag/proposals/save", {…})` → `apiFetch(…)` |
| `components/ProposalGenerator.tsx:116` | `fetch("/api/v1/rag/proposals/download", {…})` → `apiFetch(…)` |
| `components/QueryForm.tsx:27` | `fetch("/api/v1/rag/retrieve", {…})` → `apiFetch(…)` |
| `components/Settings.tsx:14` | `window.location.href = "/api/v1/rag/sync/auth?force=true"` → handler above |
| `hooks/useDriveAuthStatus.ts:19` | replaced wholesale above |
| `services/embeddingService.ts` (any `fetch(`) | `apiFetch(`, same args |

`PdfUploader.tsx` also changes semantics: the endpoint now returns `202 {jobId}` immediately; poll `apiFetch(`/api/v1/rag/ingest/${jobId}`)` until `status !== "RUNNING"` before showing the result.

- [ ] **Step 6: Run the tests and a dev server**

Run (in `frontend/`): `npm test` → PASS, 4 tests. `npm run build` → `tsc` clean, `dist/` produced.

Then with the gateway running: `npm run dev`, open `http://localhost:5173/`, click through to `/intake` → redirected to `/login?next=%2Fintake`; log in with `APP_AUTH_USER`/`APP_AUTH_PASSWORD`; land on `/intake`. Every network call in the browser shows `Authorization: Bearer …`.

- [ ] **Step 7: Commit**

```bash
git add frontend .gitignore
git commit -m "feat(frontend): lift React SPA out of V1; JWT login, apiFetch, gateway proxy"
```

---

### Task 5: Distributed tracing to Zipkin

**Files:**
- Modify: every Boot module's `pom.xml` — `infrastructure/{config-server,discovery-server}` (gateway already has it) and `services/*` (add `spring-boot-starter-zipkin`; services with Feign also add `io.github.openfeign:feign-micrometer`)

**Interfaces:**
- Produces: one trace per request across gateway → services → Kafka → services, visible at `http://localhost:9411`.

- [ ] **Step 1: Add the dependencies**

To each module listed:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-zipkin</artifactId>
        </dependency>
```

To `retrieval-service`, `ingestion-service`, `storage-service`, `chat-service`, `proposal-service` (the Feign users):

```xml
        <dependency>
            <groupId>io.github.openfeign</groupId>
            <artifactId>feign-micrometer</artifactId>
        </dependency>
```

Version is managed by `spring-cloud-openfeign-dependencies`. Spring Cloud OpenFeign auto-configures `MicrometerObservationCapability` when this is on the classpath, which is what carries the `traceparent` header across Feign calls. Kafka propagation is the two `observation-enabled` properties added in Task 1.

- [ ] **Step 2: Verify the reference trace**

Run: `./mvnw -q clean install` → BUILD SUCCESS. Start infra, platform, and all services. Log in via the SPA, ask the Event Advisor a question. Open `http://localhost:9411`, "Run Query" for service `api-gateway`.

Expected: one trace whose spans, in parent order, are `api-gateway` → `chat-service` (`POST /api/v1/chat`) → `retrieval-service` → `ai-service` (`embed/batch`) and `knowledge-service` (`chunks/search`) → `ai-service` (`generate`). Every service's log line for that request carries the same `[traceId,spanId]` correlation.

Drop a PDF in Drive (or `POST /api/v1/rag/sync`). Expected: a trace starting in `storage-service` with a `bloom.documents.discovered send` span, continuing in `ingestion-service` (`receive`), then `ai-service` and a `bloom.chunks.batch-ready send`, then `knowledge-service`.

- [ ] **Step 3: Commit**

```bash
git add infrastructure/config-server/pom.xml infrastructure/discovery-server/pom.xml services/*/pom.xml
git commit -m "feat(observability): Zipkin tracing across HTTP, Feign and Kafka"
```

---

### Task 6: Circuit breakers

**Files:**
- Modify: `services/{retrieval,chat,proposal,ingestion,storage}-service/pom.xml` and `services/ai-service/pom.xml` (add `spring-cloud-starter-circuitbreaker-resilience4j`)
- Create: `services/retrieval-service/.../client/AiClientFallback.java`, `KnowledgeClientFallback.java`; `services/ingestion-service/.../client/AiClientFallback.java`, `KnowledgeClientFallback.java`, `StorageClientFallback.java`; `services/storage-service/.../client/KnowledgeClientFallback.java`; `services/chat-service/.../client/*Fallback.java`; `services/proposal-service/.../client/*Fallback.java`
- Modify: every `@FeignClient` in those services to `@FeignClient(name = "…", fallback = XClientFallback.class)`
- Modify: `services/ai-service/.../gemini/GeminiEmbeddingService.java`, `GeminiGenerationService.java`; `services/storage-service/.../drive/GoogleDriveClient.java`
- Test: `services/retrieval-service/.../client/AiClientFallbackTest.java`, `services/ai-service/.../gemini/GeminiCircuitBreakerIntegrationTests.java`, `services/storage-service/.../drive/DriveCircuitBreakerIntegrationTests.java`

**Interfaces:**
- Produces: when a peer is unreachable or its breaker is open, Feign returns the fallback instantly; fallbacks throw `ExternalServiceException` (retrieval, ingestion, storage — existing degradation/retry paths run) or `ServiceUnavailableException` (chat, proposal — a clean 503 to the browser). Gemini and Drive calls fail fast once the `gemini` / `drive` breaker opens.

- [ ] **Step 1: Write the failing tests**

`retrieval-service` `AiClientFallbackTest.java`:

```java
package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientFallbackTest {

    private final AiClientFallback fallback = new AiClientFallback();

    @Test
    void everyMethodThrowsSoRetrievalDegradesOrReports502() {
        assertThatThrownBy(() -> fallback.embedBatch(new EmbedBatchRequest(List.of("x"))))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
        assertThatThrownBy(() -> fallback.generate(new GenerateRequest("x")))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
    }
}
```

`ai-service` `GeminiCircuitBreakerIntegrationTests.java`:

```java
package com.bloom.eventmanager.ai.gemini;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.ai.api-key=test-key",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "resilience4j.circuitbreaker.configs.default.slidingWindowSize=2",
        "resilience4j.circuitbreaker.configs.default.minimumNumberOfCalls=2",
        "resilience4j.circuitbreaker.configs.default.failureRateThreshold=50",
        "resilience4j.circuitbreaker.configs.default.waitDurationInOpenState=60s"
})
class GeminiCircuitBreakerIntegrationTests {

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Autowired
    private GeminiEmbeddingService service;

    @Autowired
    private CircuitBreakerRegistry registry;

    @Test
    void opensAfterRepeatedGeminiFailuresAndThenFailsFast() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("503 UNAVAILABLE"));

        assertThatThrownBy(() -> service.embed("a")).isInstanceOf(ExternalServiceException.class);
        assertThatThrownBy(() -> service.embed("b")).isInstanceOf(ExternalServiceException.class);

        assertThat(registry.circuitBreaker("gemini").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> service.embed("c")).isInstanceOf(ExternalServiceException.class);
        verify(embeddingModel, times(2)).embed(anyString()); // third call never reached Gemini
    }
}
```

`storage-service` `DriveCircuitBreakerIntegrationTests.java` — same shape with `@MockitoBean DriveAuthManager auth` whose `credential()` throws `new RuntimeException("connection reset")`, `GoogleDriveClient.listFolders("root")` called twice, then `registry.circuitBreaker("drive").getState()` is `OPEN` and the third call throws `ExternalServiceException` with `verify(auth, times(2)).credential()`. Add `"spring.kafka.listener.auto-startup=false"` and `@MockitoBean DriveTokenRepository`.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/retrieval-service test -Dtest=AiClientFallbackTest`
Expected: COMPILATION FAILURE — `cannot find symbol: class AiClientFallback`

- [ ] **Step 3: Implement**

Add to the six POMs:

```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
        </dependency>
```

`retrieval-service` `client/AiClientFallback.java`:

```java
package com.bloom.eventmanager.retrieval.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Runs when ai-service is down or its breaker is open. Throwing (rather than
 * returning empties) hands control to RetrievalService's existing degradation:
 * variations/re-rank fall back to the plain path, and a missing embedding is a 502.
 */
@Component
public class AiClientFallback implements AiClient {

    @Override
    public EmbedBatchResponse embedBatch(EmbedBatchRequest request) {
        throw new ExternalServiceException("ai-service unavailable (circuit open)", null);
    }

    @Override
    public GenerateResponse generate(GenerateRequest request) {
        throw new ExternalServiceException("ai-service unavailable (circuit open)", null);
    }
}
```

`retrieval-service` `client/KnowledgeClientFallback.java`:

```java
package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.ChunkSearchRequest;

@Component
public class KnowledgeClientFallback implements KnowledgeClient {

    @Override
    public List<ChunkMatch> search(ChunkSearchRequest request) {
        throw new ExternalServiceException("knowledge-service unavailable (circuit open)", null);
    }
}
```

Change the annotations: `@FeignClient(name = "ai-service", fallback = AiClientFallback.class)` and `@FeignClient(name = "knowledge-service", fallback = KnowledgeClientFallback.class)`.

Apply the identical pattern in `ingestion-service` (`AiClientFallback`, `KnowledgeClientFallback`, `StorageClientFallback` — all throw `ExternalServiceException`, so the Kafka listener fails and `DefaultErrorHandler` retries / DLTs), in `storage-service` (`KnowledgeClientFallback` throwing `ExternalServiceException`), and in `chat-service` and `proposal-service` where every fallback throws `new ServiceUnavailableException("<peer> unavailable (circuit open)", null)` so the browser sees a 503 ProblemDetail rather than a 502.

`ai-service` — inject `CircuitBreakerFactory<?, ?> breakers` into `GeminiEmbeddingService` and `GeminiGenerationService` and wrap the Gemini call:

```java
    // GeminiEmbeddingService
    @Override
    public float[] embed(String text) {
        return breakers.create("gemini").run(
                () -> model.embed(text),
                failure -> { throw new ExternalServiceException("Gemini embedding failed", failure); });
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> slice = texts.subList(from, Math.min(from + batchSize, texts.size()));
            out.addAll(breakers.create("gemini").run(
                    () -> model.embed(slice),
                    failure -> { throw new ExternalServiceException("Gemini batch embedding failed", failure); }));
        }
        return out;
    }
```

In `GeminiGenerationService.generate`, replace `chatModel.call(new Prompt(...))` with:

```java
                ChatResponse response = breakers.create("gemini").run(
                        () -> chatModel.call(new Prompt(input, toChatOptions(model, options))),
                        failure -> { throw unwrap(failure); });
```

and add:

```java
    /** Keep the SDK's original exception so GeminiErrors can still read the status code from it. */
    private static RuntimeException unwrap(Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new RuntimeException(failure);
    }
```

Import `org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory`. Update the two unit tests' constructors to pass `new Resilience4JCircuitBreakerFactoryStub()`? No — simpler: the unit tests construct the services with a real, default-configured factory obtained from `io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()` via `new org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(), io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(), null, new org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties())`. If that constructor's arity differs on the installed version, use the one whose parameters are `(CircuitBreakerRegistry, TimeLimiterRegistry, Resilience4jBulkheadProvider, Resilience4JConfigurationProperties)` — the IDE's completion shows it; pass `null` for the bulkhead provider.

`storage-service` `GoogleDriveClient.call(...)` becomes:

```java
    private <T> T call(String operation, DriveCall<T> call) {
        return breakers.create("drive").run(
                () -> {
                    try {
                        return call.run();
                    }
                    catch (IOException ex) {
                        auth.handleAuthError(ex);
                        throw new ExternalServiceException("Google Drive " + operation + " failed", ex);
                    }
                },
                failure -> {
                    if (failure instanceof ExternalServiceException external) {
                        throw external;
                    }
                    throw new ExternalServiceException("Google Drive " + operation + " unavailable (circuit open)", failure);
                });
    }
```

with `CircuitBreakerFactory<?, ?> breakers` injected through the constructor.

- [ ] **Step 4: Run to verify passing**

Run: `./mvnw -q clean install`
Expected: BUILD SUCCESS; the two breaker integration tests report `OPEN` after two failures.

- [ ] **Step 5: Verify live**

With everything running, stop `ai-service`. Ask the Event Advisor a question five times quickly. Expected: the first attempts take the Feign connect-timeout, then responses become instant 503s (`SERVICE_UNAVAILABLE`) while `chat-service`'s breaker for `ai-service` is open; `retrieval-service` degrades `useAI` searches to the plain path; `/actuator/health` on `chat-service` still reports UP. Restart `ai-service`; after `waitDurationInOpenState` (30s) the half-open probe succeeds and chat recovers with no restart.

- [ ] **Step 6: Commit**

```bash
git add services
git commit -m "feat(resilience): Feign fallbacks and Resilience4j breakers around Gemini, Drive and every peer"
```

---

### Task 7: Actuator and build info

**Files:**
- Modify: `config-repo/knowledge-service.yml`, `chat-service.yml`, `storage-service.yml`, `ai-service.yml`, `retrieval-service.yml`, `ingestion-service.yml`, `proposal-service.yml`
- Modify: `pom.xml` (root — `build-info` goal in `pluginManagement`)

- [ ] **Step 1: Readiness groups**

Already in place from earlier phases: knowledge includes `neo4j`; ai leaves Redis out. Add:

`config-repo/chat-service.yml` and `config-repo/storage-service.yml`:

```yaml
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,mongo
```

Every other service keeps the default (`readinessState` only) — a Kafka or Eureka hiccup must not pull a service out of rotation.

- [ ] **Step 2: Build info**

Root `pom.xml` — add a `<build><pluginManagement>` block so every Boot module's `spring-boot-maven-plugin` writes `META-INF/build-info.properties`:

```xml
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <executions>
                        <execution>
                            <id>build-info</id>
                            <goals>
                                <goal>build-info</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
```

- [ ] **Step 3: Verify**

Run: `./mvnw -q clean install`, start a service, then:

```bash
curl -s http://localhost:8082/actuator/info | jq .build
curl -s http://localhost:8085/actuator/health/readiness | jq .components.mongo.status
```

Expected: `{"artifact":"knowledge-service","version":"0.0.1-SNAPSHOT",…}` and `"UP"`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml config-repo
git commit -m "chore(actuator): readiness groups per datastore, build info on /actuator/info"
```

---

### Task 8: Containers — the whole stack under Compose

**Files:**
- Create: `deploy/Dockerfile.service`, `services/ingestion-service/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`
- Modify: `deploy/docker-compose.yml`, `deploy/.env.example`

- [ ] **Step 1: Service image**

`deploy/Dockerfile.service`:

```dockerfile
# Build context is the repository root:  docker build -f deploy/Dockerfile.service --build-arg MODULE_PATH=services/ai-service .
ARG MODULE_PATH

FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE_PATH
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY platform platform
COPY infrastructure infrastructure
COPY services services
RUN mvn -q -pl ${MODULE_PATH} -am -DskipTests package

FROM eclipse-temurin:25-jre
ARG MODULE_PATH
# curl for the Compose healthcheck against /actuator/health/readiness.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
WORKDIR /app
COPY --from=build /workspace/${MODULE_PATH}/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`services/ingestion-service/Dockerfile` — the same, with the runtime stage's apt line replaced by:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl tesseract-ocr libtesseract-dev \
    && rm -rf /var/lib/apt/lists/*
```

and `ARG MODULE_PATH=services/ingestion-service` defaulted at the top. Tess4J loads `libtesseract` from the system on Linux; the bundled `eng.traineddata` still comes from the jar.

- [ ] **Step 2: Frontend image**

`frontend/nginx.conf`:

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;

    # SPA deep links
    location / {
        try_files $uri /index.html;
    }

    # Same-origin API: the browser never learns the gateway's address.
    location /api/ {
        proxy_pass http://api-gateway:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
    }

    location /.well-known/ {
        proxy_pass http://api-gateway:8080;
    }
}
```

`frontend/Dockerfile`:

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

- [ ] **Step 3: Compose profiles**

In `deploy/docker-compose.yml`, change the `kafka` service's environment so containers and the host both reach it:

```yaml
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,PLAINTEXT_HOST://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
```

and its ports to `- "29092:29092"`. Then append the `platform` and `apps` profiles:

```yaml
  # ── platform ────────────────────────────────────────────────────────────
  config-server:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: infrastructure/config-server } }
    profiles: ["platform"]
    environment:
      CONFIG_REPO: file:/config-repo
    volumes:
      - ../config-repo:/config-repo:ro
    ports: ["8888:8888"]
    mem_limit: 512m
    healthcheck: &java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8888/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 40s

  discovery-server:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: infrastructure/discovery-server } }
    profiles: ["platform"]
    environment:
      EUREKA_HOSTNAME: discovery-server
    ports: ["8761:8761"]
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8761/actuator/health/readiness"]

  api-gateway:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: infrastructure/api-gateway } }
    profiles: ["platform"]
    environment: &platform-env
      CONFIG_SERVER_URL: http://config-server:8888
      EUREKA_URL: http://discovery-server:8761/eureka/
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans
      JWT_JWKS_URI: http://api-gateway:8080/.well-known/jwks.json
      INTERNAL_SERVICE_TOKEN: ${INTERNAL_SERVICE_TOKEN}
      APP_AUTH_USER: ${APP_AUTH_USER}
      APP_AUTH_PASSWORD: ${APP_AUTH_PASSWORD}
      JWT_PRIVATE_KEY_PEM: ${JWT_PRIVATE_KEY_PEM:-}
      JWT_PUBLIC_KEY_PEM: ${JWT_PUBLIC_KEY_PEM:-}
    ports: ["8080:8080"]
    depends_on:
      config-server: { condition: service_healthy }
      discovery-server: { condition: service_healthy }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health/readiness"]

  # ── apps ────────────────────────────────────────────────────────────────
  ai-service:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: services/ai-service } }
    profiles: ["apps"]
    environment:
      <<: *platform-env
      GEMINI_API_KEY: ${GEMINI_API_KEY}
      REDIS_URL: redis://redis:6379
    depends_on: { api-gateway: { condition: service_healthy }, redis: { condition: service_healthy } }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8081/actuator/health/readiness"]

  knowledge-service:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: services/knowledge-service } }
    profiles: ["apps"]
    environment:
      <<: *platform-env
      NEO4J_URI: bolt://neo4j:7687
      NEO4J_USER: ${NEO4J_USER:-neo4j}
      NEO4J_PASSWORD: ${NEO4J_PASSWORD:-password}
    depends_on: { api-gateway: { condition: service_healthy }, neo4j: { condition: service_healthy }, kafka: { condition: service_healthy } }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8082/actuator/health/readiness"]

  ingestion-service:
    build: { context: .., dockerfile: services/ingestion-service/Dockerfile }
    profiles: ["apps"]
    environment:
      <<: *platform-env
      PDF_DIRECTORY: /data/pdfs
    volumes:
      - ${PDF_DIRECTORY:-./pdfs}:/data/pdfs:ro
    depends_on: { api-gateway: { condition: service_healthy }, kafka: { condition: service_healthy } }
    mem_limit: 768m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8083/actuator/health/readiness"]

  retrieval-service:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: services/retrieval-service } }
    profiles: ["apps"]
    environment: *platform-env
    depends_on: { api-gateway: { condition: service_healthy } }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8084/actuator/health/readiness"]

  chat-service:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: services/chat-service } }
    profiles: ["apps"]
    environment:
      <<: *platform-env
      MONGO_URI: mongodb://mongodb:27017
    depends_on: { api-gateway: { condition: service_healthy }, mongodb: { condition: service_healthy } }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8085/actuator/health/readiness"]

  storage-service:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: services/storage-service } }
    profiles: ["apps"]
    environment:
      <<: *platform-env
      MONGO_URI: mongodb://mongodb:27017
      GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID:-}
      GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET:-}
      GOOGLE_DRIVE_FOLDER_ID: ${GOOGLE_DRIVE_FOLDER_ID:-}
      GOOGLE_OAUTH_REDIRECT_URI: http://localhost:8080/api/rag/auth/google/callback
      FRONTEND_URL: http://localhost:3000
    depends_on: { api-gateway: { condition: service_healthy }, mongodb: { condition: service_healthy }, kafka: { condition: service_healthy } }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8086/actuator/health/readiness"]

  proposal-service:
    build: { context: .., dockerfile: deploy/Dockerfile.service, args: { MODULE_PATH: services/proposal-service } }
    profiles: ["apps"]
    environment: *platform-env
    depends_on: { api-gateway: { condition: service_healthy } }
    mem_limit: 512m
    healthcheck:
      <<: *java-health
      test: ["CMD", "curl", "-fsS", "http://localhost:8087/actuator/health/readiness"]

  frontend:
    build: { context: ../frontend }
    profiles: ["apps"]
    ports: ["3000:80"]
    depends_on: { api-gateway: { condition: service_healthy } }
```

`deploy/.env.example` — extend:

```
# Copy to deploy/.env and fill in. deploy/.env is gitignored. Never commit real values.
NEO4J_USER=neo4j
NEO4J_PASSWORD=changeme
GEMINI_API_KEY=
APP_AUTH_USER=bloom
APP_AUTH_PASSWORD=change-me-to-something-long
INTERNAL_SERVICE_TOKEN=change-me-to-a-random-string
# Optional; blank = ephemeral dev keys (tokens die with the gateway process)
JWT_PRIVATE_KEY_PEM=
JWT_PUBLIC_KEY_PEM=
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_DRIVE_FOLDER_ID=
PDF_DIRECTORY=./pdfs
```

- [ ] **Step 4: Verify**

```bash
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps up -d --build
docker compose -f deploy/docker-compose.yml ps
```

Expected: every service `healthy` within ~3 minutes on first build. Open `http://localhost:3000`, log in, submit an intake form, ask the advisor, generate a proposal — all through nginx → gateway → services. `http://localhost:8761` lists all seven services and the gateway. `http://localhost:9411` shows traces.

Host-mode development still works: `--profile infra --profile platform`, then run a service from the IDE with the defaults in `config-repo` (`localhost:29092`, `localhost:8888`, `localhost:8761`).

- [ ] **Step 5: Commit**

```bash
git add deploy services/ingestion-service/Dockerfile frontend/Dockerfile frontend/nginx.conf
git commit -m "chore(deploy): images and Compose profiles for the full stack; dual Kafka listeners"
```

---

### Task 9: README, deviations log, reference trace

**Files:**
- Rewrite: `README.md`

- [ ] **Step 1: Write the README**

Structure (each section written out in full in the commit; the shape is prescribed here):

1. **Title and one paragraph** — what Bloom is, that V1 lives at `../Bloom_Event_Manager` as the reference implementation.
2. **Architecture** — this Mermaid diagram:

````markdown
```mermaid
flowchart LR
  SPA[React SPA<br/>:3000 nginx / :5173 dev] -->|JWT| GW[api-gateway :8080]
  GW --> CHAT[chat-service :8085]
  GW --> PROP[proposal-service :8087]
  GW --> RET[retrieval-service :8084]
  GW --> STOR[storage-service :8086]
  GW --> ING[ingestion-service :8083]
  CHAT --> RET
  PROP --> RET
  PROP --> STOR
  CHAT --> STOR
  RET --> AI[ai-service :8081]
  RET --> KNOW[knowledge-service :8082]
  CHAT --> AI
  PROP --> AI
  PROP --> KNOW
  ING --> AI
  AI --> GEM[(Gemini)]
  AI --> REDIS[(Redis)]
  KNOW --> NEO[(Neo4j)]
  CHAT --> MCHAT[(Mongo chatdb)]
  STOR --> MTOK[(Mongo tokendb)]
  STOR --> DRIVE[(Google Drive)]
  STOR -.bloom.documents.discovered / deleted.-> K{{Kafka}}
  K -.-> ING
  ING -.bloom.chunks.batch-ready.-> K
  K -.-> KNOW
  KNOW -.bloom.documents.indexed.-> K
  K -.-> STOR
  EUREKA[discovery-server :8761] --- GW
  CONFIG[config-server :8888] --- GW
  ZIP[Zipkin :9411] --- GW
```
````

3. **Stack and version pins** — Boot 4.0.8 / Cloud 2025.1.3 / Java 25 / Spring AI 2.0.1 core modules, with the one-line reason for each pin.
4. **Running it** — three modes: *dev* (`--profile infra --profile platform` in Docker, services from the IDE, `npm run dev` on 5173), *full* (all three profiles, SPA on 3000), *tests* (`./mvnw clean install`; Docker-dependent tests skip without Docker; `cd frontend && npm test`).
5. **Login and security** — `POST /api/v1/auth/login`, the JWKS URL, the `X-Internal-Token` mechanism in two sentences, the `openssl` key commands, the list of every env var.
6. **Google Drive** — the redirect URI that must be registered (`http://localhost:8080/api/rag/auth/google/callback`), how to authenticate from Settings, what the poll does.
7. **Reference trace** — "Log in, ask the Event Advisor anything, open http://localhost:9411, query service `api-gateway`; the trace spans gateway → chat → retrieval → ai / knowledge → ai." Screenshot placeholder is a file path `docs/images/reference-trace.png` that Step 2 creates.
8. **Spec deviations log** — a table:

| Spec said | Built | Why |
|---|---|---|
| Public key distributed via Config Server | Gateway serves `/.well-known/jwks.json`; Config Server distributes the URL | Standard JWKS; no key material in config |
| Services trust the gateway | Services validate the JWT *and* accept `X-Internal-Token` from the gateway / peers | Kafka-triggered hops have no user JWT |
| `GET /sync/auth?force=true` redirects | Returns `{status, authUrl}` | A browser navigation cannot carry a Bearer token |
| `DELETE /documents/{source}` | `DELETE /documents?source=` | Sources contain slashes |
| — | `GET /drive-files/{id}` added | Deletion needs the source for a Drive id |
| Tess4J for Drive OCR | Tess4J for both ingest paths | One OCR strategy; V1's local path used Gemini Vision |
| `sanitizeFolderName` replaces whitespace | Keeps whitespace | V1's regex never matched; existing folders have spaces |
| Batches "≤20 chunks" | Enforced in the `ChunkBatchReady` constructor | Kafka 1 MB default |

9. **Phase verification log** — the entries appended during Phases 1–4, kept.
10. **Repository layout** — the tree from the spec, updated with `platform/bloom-security` and `frontend/`.

- [ ] **Step 2: Capture the reference trace**

Run the full stack, perform one chat, open Zipkin, save the trace view as `docs/images/reference-trace.png`.

- [ ] **Step 3: Commit and push**

```bash
git add README.md docs/images/reference-trace.png
git commit -m "docs: README with architecture, run modes, security, deviations log and reference trace"
git push
```

---

## Phase 5 Exit Criteria

- [ ] The SPA (dev :5173 and nginx :3000) runs entirely against the gateway: login, intake, advisor, proposals, settings — no direct service calls, every request carries a JWT
- [ ] `POST /api/v1/auth/login` issues RS256 tokens; `/.well-known/jwks.json` publishes the key; every service rejects anonymous calls and accepts both the JWT and the gateway's internal token
- [ ] Google OAuth still completes through the legacy shim with no token (permitAll on both callback paths)
- [ ] One `/api/v1/rag/chat` request produces a single Zipkin trace across gateway → chat → retrieval → ai / knowledge; a Drive-triggered ingest traces across Kafka
- [ ] Stopping `ai-service` opens breakers within five calls; chat returns 503 ProblemDetails, retrieval degrades to the plain path; recovery is automatic after 30 s
- [ ] `/actuator/health/readiness` reflects each service's own datastore and nothing else; `/actuator/info` shows the build version
- [ ] `docker compose --profile infra --profile platform --profile apps up -d --build` brings up all fifteen containers healthy
- [ ] No key, token or password appears anywhere in the repository

## Project complete — what to demo

1. `docker compose … up -d` → Eureka dashboard with eight registrations.
2. `http://localhost:3000` → login → the app.
3. Settings → Connect Google Drive → consent → back in the app with "Connected".
4. Drop a PDF in the Drive folder → watch `storage` → `ingestion` → `knowledge` logs → ask the advisor about it.
5. Zipkin: the chat trace; the ingest trace crossing Kafka.
6. `docker stop bloom-v2-ai-service` → advisor returns an instant, clean 503; retrieval keeps working; `docker start` → recovers on its own.
7. `curl -i http://localhost:8080/api/v1/rag/ping` → 401 ProblemDetail; with a token → 200.
8. Config Server: change `bloom.retrieval.topK` in `config-repo`, `POST /actuator/refresh` on retrieval — no restart.
9. Kafka: `kafka-topics.sh --list` shows the four topics and a `.DLT`.
10. The README's deviations log — every place the build disagreed with the spec, and why.

## What Phase 5 Does Not Do

- No per-user accounts or roles — one shared login, as V1 had; the JWT carries `sub` and `scope: user` only
- No Kubernetes — Compose is the deployment target the spec chose
- No Prometheus/Grafana — metrics are exposed on `/actuator/metrics` but not scraped
- No rate limiting on the gateway
