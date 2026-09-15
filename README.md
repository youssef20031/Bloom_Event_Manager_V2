# Bloom Event Manager V2

Spring microservices rebuild of the Bloom Event Manager. The V1 monolith
(Express 5 + TypeScript) lives at `../Bloom_Event_Manager` and remains the
reference implementation.

- **Design spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md`
- **Current phase:** Phase 2 — Read path

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
| `services/ai-service` | 8081 | Gemini embedding and generation, Redis-cached |
| `services/retrieval-service` | 8084 | Dual-search orchestration; owns no data |

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

## Phase 1 verification (2026-09-15)

The data spine was verified end to end against a copy of V1's production
Neo4j (24 `Document` nodes, 110 `Chunk` nodes, 3072-dimension embeddings,
with the `chunk_embeddings` index already present) and a real Gemini key.

`./mvnw clean install` — BUILD SUCCESS across nine reactor projects.
`knowledge-service` logged `Vector index 'chunk_embeddings' already exists`,
confirming it was reading V1's data rather than an empty database.

Embedding the query *"team building activities for a non-profit"* through
`POST /api/v1/ai/embed` returned 3072 dimensions and wrote
`bloom:embed:gemini-embedding-001:cfb70af6119cb3667dd93529e6f937a0`;
repeating the call returned a byte-identical response from cache. Feeding
that vector to `POST /api/v1/chunks/search` returned, in descending score
order:

| source | score |
| --- | --- |
| `dell/Customer_Brief_2026-07-29T14-23-15.pdf` | 0.8361 |
| `Activity_Library.pdf` | 0.8354 |
| `Activity_Library.pdf` | 0.8342 |
| `Activity_Library.pdf` | 0.8334 |
| `dell/Customer_Brief_2026-07-22T14-11-44.pdf` | 0.8328 |

Use this as the reference corpus for later phases: three of the five top
hits come from `Activity_Library.pdf`, which is the semantically right
document for that query.

`POST /api/v1/ai/generate` with `includeThoughts` returned populated `text`
and `thoughts` fields, confirming thought/answer splitting works against the
live API and not only against mocks.

With Redis stopped, `POST /api/v1/ai/embed` still answered `200` with a
3072-dimension vector for an uncached phrase, and readiness stayed `UP`
while `/actuator/health` reported `DOWN`. That split is deliberate: a Redis
outage is visible to operators but never removes the service from rotation.

### Sources in V1's corpus

Ten of the 24 ingested documents carry slashes in their source names
(`Dell Technologies/…`, `Teambuilding/candylove/candylove.pdf`, `dell/…`).
This is why `DELETE /api/v1/documents` takes `?source=` as a query parameter
rather than a path variable — a path variable cannot carry them. Do not
"fix" it back.

V1's database also contains a second vector index,
`knowledge_chunk_embeddings`, alongside `chunk_embeddings`. It appears to be
residue from V1's abandoned `Neo4jChunkRepository.ts`, which is deliberately
not ported. Phase 1 addresses its index by configured name and is unaffected;
Phase 3 writes to this database and should account for it.

### Known limitation: Gemini retries

`GeminiClientConfig` passes Spring AI's `RetryUtils.DEFAULT_RETRY_TEMPLATE` to
the chat model, but that template only matches `TransientAiException` and
`ResourceAccessException`, and `spring-ai-google-genai` 2.0.1 throws neither —
the SDK raises `com.google.genai.errors.ApiException`. Nothing on the Gemini
path is therefore actually retried, so a transient 5xx or a socket blip fails
the request immediately, and only 429 and 404 walk the model-fallback chain.
This is not a regression from V1, which behaved the same way, and one side
effect is welcome: a quota error reaches the next model at once instead of
after ten backoff attempts. It does mean **Phase 5 must not assume a working
retry layer already exists** when it adds circuit breakers.

## Phase 2 verification (2026-09-15)

`./mvnw clean install` — BUILD SUCCESS across ten reactor projects, 115 tests.

`retrieval-service` started on 8084 and registered as `RETRIEVAL-SERVICE`
(Eureka registration status 204). Its `/actuator/health` reports only
`clientConfigServer`, `discoveryComposite`, `diskSpace`, `livenessState`,
`ping`, `readinessState`, `refreshScope` and `ssl` — **no datastore
component**, which is the phase's structural claim: retrieval owns no data
and reaches everything over Feign.

All four services plus V1 ran together for the parity check below:
`AI-SERVICE`, `KNOWLEDGE-SERVICE`, `RETRIEVAL-SERVICE` and `API-GATEWAY` all
registered in Eureka, and `knowledge-service` logged
`Vector index 'chunk_embeddings' already exists`, confirming it was reading
V1's corpus rather than an empty database.

Routing was confirmed end to end against a gateway built from this branch.
`POST /api/v1/rag/retrieve` returned a ProblemDetail whose `instance` field
read `/api/v1/retrieve`, proving the `RewritePath` filter rewrote V1's public
path and that `lb://retrieval-service` resolved through Eureka.

Failure classification, both directions, verified live:

| Condition | Result |
| --- | --- |
| `ai-service` not running, query given | `502` `EXTERNAL_SERVICE_ERROR`, detail `Could not obtain query embedding from ai-service` |
| `knowledge-service` failing, embedding supplied | `502` `EXTERNAL_SERVICE_ERROR`, detail `Vector search failed in knowledge-service` |
| Neither `query` nor `queryEmbedding` | `400` `VALIDATION_FAILED`, detail `queryEmbedding must be a non-empty array` (V1's message) |
| `topK: 0` | `400` `VALIDATION_FAILED` |

No request produced a `500` or a hang, which is the property Phase 5's
circuit breakers will wrap.

### V1 parity — measured

Both stacks were run against the same Neo4j (V1's `bloom-neo4j` container,
110 `Chunk` and 24 `Document` nodes, `chunk_embeddings` ONLINE), V1 on 3000
and V2 behind the gateway on 8080, for the query
*"team building activities for a non-profit"*.

**Plain path (`useAI: false`) — `IDENTICAL`, reproduced twice.** No model is
in the loop, so the two must agree exactly, and they do — same chunk ids in
the same order:

```
dell/Customer_Brief_2026-07-29T14-23-15.pdf-0-1
Activity_Library.pdf-0-5
Activity_Library.pdf-0-3
Activity_Library.pdf-0-0
dell/Customer_Brief_2026-07-22T14-11-44.pdf-0-1
```

That is also the Phase 1 reference ordering, top hit scoring 0.8361.

**AI path (`useAI: true`, `topK: 10`) — 7 of 10 overlap**, meeting the ≥ 70%
bar. Exact order is not expected: each side makes its own query-split and
re-rank calls. `metadata.aiEnhanced` was `true`, `metadata.totalRetrieved`
was 20 (two searches at `topK` each), and `metadata.variations` came back
populated and genuinely distinct:

```json
{
  "specific": "Awlady Bloom non-profit team building activities client brief proposal chat history",
  "broad": "team building activities for non-profit organizations best practices and guides"
}
```

The three ids unique to each side were all near-threshold chunks from the
same documents, which is the expected shape of re-rank disagreement.

Reproduce with `scripts` equivalents of:

```bash
BODY='{"query": "team building activities for a non-profit", "topK": 5, "useAI": false}'

curl -s -X POST http://localhost:3000/api/v1/rag/retrieve \
     -H "Content-Type: application/json" -d "$BODY" | jq -r '.chunks[].id' > v1.txt
curl -s -X POST http://localhost:8080/api/v1/rag/retrieve \
     -H "Content-Type: application/json" -d "$BODY" | jq -r '.chunks[].id' > v2.txt

diff v1.txt v2.txt && echo "IDENTICAL"
```

If they ever differ, check that both point at the same `NEO4J_URI` and that
V1's `.env` sets `GEMINI_EMBEDDING_MODEL=gemini-embedding-001` — a different
model yields a different vector and a different cache key.

### Running V1 and V2 side by side

V1's `.env` points at `bolt://localhost:7687` with credentials belonging to
its own `bloom-neo4j` container, **not** the `bloom-v2-neo4j` one from
`deploy/docker-compose.yml`. Both bind 7687, so only one can run at a time,
and `bloom-v2-neo4j` is empty. To reproduce the parity run, stop
`bloom-v2-neo4j`, start `bloom-neo4j`, and give the V2 services V1's
`NEO4J_USER` / `NEO4J_PASSWORD` / `GEMINI_API_KEY` (V1 spells the key
`VITE_GEMINI_API_KEY`). Both containers are pinned to `neo4j:2026.06.0`, so
swapping them does not migrate either store.

Note also that a Config Server started from a checkout without Phase 1's
`config-repo/knowledge-service.yml` serves a Neo4j-less config, and the
driver then connects with auth scheme `none` and fails against any
authenticated database. If `knowledge-service` reports
`Unsupported authentication token, scheme 'none'`, the Config Server is
serving a stale `config-repo`, not the service misreading its environment.

### Caching is enabled off the application class

`@EnableCaching` lives on `retrieval/config/CachingConfig`, not on
`RetrievalServiceApplication`. On the application class it installs the
caching advisor into every `@WebMvcTest` slice as well, and a slice applies no
`CacheAutoConfiguration`, so `RetrieveControllerTest` failed its context load
with `No qualifying bean of type 'CacheManager'`. As a scanned
`@Configuration` it is filtered out of web slices and still picked up by the
full context, where `QueryVariationCachingTests` proves the Caffeine cache
actually engages. Do not move it back.
