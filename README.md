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

Boot 4.0 also split its test support into per-technology modules that
`spring-boot-starter-test` no longer pulls in. A module needing
`TestRestTemplate`, `@WebMvcTest` or `WebTestClient` declares
`spring-boot-resttestclient` (plus `spring-boot-restclient`),
`spring-boot-webmvc-test` or `spring-boot-webtestclient` test-scoped.

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

The Config Server reads `config-repo/` from the repository root. `spring-boot:run`
defaults its working directory to the module being run, so the config-server
module pins `<workingDirectory>` to the multi-module root — without that, the
native backend resolves `file:./config-repo` inside
`infrastructure/config-server/` and silently serves zero property sources.

Verify the whole chain:

```bash
curl -s http://localhost:8080/api/v1/rag/ping
# {"service":"knowledge-service","status":"ok"}
```

A 503 on the first attempt is normal, not a misconfiguration. Eureka caches its
registry response for 30s and clients refresh on their own interval, so the
gateway can take up to a minute after startup to see `knowledge-service`. Retry
before debugging; `curl http://localhost:8080/actuator/health` shows what the
gateway has actually discovered.

## Configuration and secrets

All configuration is served by the Config Server from `config-repo/`.
Secrets are `${ENV_VAR}` placeholders resolved from the environment and are
never committed. Copy `deploy/.env.example` to `deploy/.env` for local
infrastructure credentials.

There is no authentication in Phase 0 — JWT and Spring Security arrive in
Phase 5. The Config Server on 8888 serves `config-repo/` to any caller that
can reach it, so keep real secrets in `deploy/.env` and out of `config-repo/`,
and keep these ports on localhost.
