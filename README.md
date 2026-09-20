# Bloom Event Manager V2

Bloom Event Manager is a retrieval-augmented tool for an events agency: it keeps
the agency's PDF library in a Google Drive folder, indexes it, answers planning
questions against it (the Event Advisor), captures customer briefs, and produces
slide-shaped client proposals. V2 is a Spring microservices rebuild of that
system. The V1 monolith (Express 5 + TypeScript) lives at
`../Bloom_Event_Manager` and remains the reference implementation — behavioural
questions are settled by reading V1, and the phase verification log below
records the parity runs made against V1's own Neo4j, MongoDB and Drive folder.

- **Design spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md`
- **Current phase:** Phase 5 — cutover and polish

## Architecture

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

The gateway is the only public entry point. Ports below are the ones each
service's own `config-repo/*.yml` sets; the Compose healthchecks in
`deploy/docker-compose.yml` probe the same numbers.

| Module | Port | Owns | Purpose |
|---|---|---|---|
| `infrastructure/discovery-server` | 8761 | — | Eureka registry |
| `infrastructure/config-server` | 8888 | `config-repo/` | Config Server, native (filesystem) backend |
| `infrastructure/api-gateway` | 8080 | the JWT signing key | Routing, login, JWKS, internal-token stamping |
| `services/ai-service` | 8081 | Redis | Gemini embedding and generation, cached |
| `services/knowledge-service` | 8082 | Neo4j | Chunks, documents, Drive-file metadata, vector search |
| `services/ingestion-service` | 8083 | — | PDF parsing, OCR, chunking, embedding |
| `services/retrieval-service` | 8084 | — | Dual-search orchestration; owns no data |
| `services/chat-service` | 8085 | Mongo `chatdb` | Event Advisor sessions, chat PDF export |
| `services/storage-service` | 8086 | Mongo `tokendb`, Google Drive | Sole holder of the Drive OAuth token; Drive sync |
| `services/proposal-service` | 8087 | — | Proposal generation, customer briefs, event intake |
| `platform/bloom-bom` | — | — | Third-party and internal version pins |
| `platform/bloom-common` | — | — | RFC 9457 problem details, `ErrorCode`, `BloomException` |
| `platform/bloom-events` | — | — | Kafka topic names and event records |
| `platform/bloom-pdf` | — | — | Shared PDF text layout, Arabic shaping, bundled Amiri fonts |
| `platform/bloom-security` | — | — | Resource-server chain and `X-Internal-Token`, auto-configured |
| `frontend/` | 3000 / 5173 | — | React 19 + Vite SPA; nginx in the `apps` profile |

The four Kafka topics are declared in
`platform/bloom-events/.../BloomTopics.java`: `bloom.documents.discovered`,
`bloom.chunks.batch-ready`, `bloom.documents.indexed`, `bloom.documents.deleted`.
Every service declares a `NewTopic` for the topics it *consumes* as well as the
ones it produces — see the Phase 3a note below for why that is not tidy-uppable.

## Stack and version pins

| Pin | Version | Why this exact version |
|---|---|---|
| Spring Boot | 4.0.8 | Spring Cloud 2025.1.3 supports exactly this Boot version. Do not bump Boot independently. |
| Spring Cloud | 2025.1.3 | Newest release train; sets the Boot baseline above. |
| Java | 25 | `java.version` in the root `pom.xml`; images are `eclipse-temurin:25`. |
| Spring AI | 2.0.1 | **Core modules only** — `spring-ai-google-genai` and `spring-ai-google-genai-embedding`. The Spring AI *starters* depend on Boot 4.1.1 and would break the 4.0.8 baseline, so `GeminiClientConfig` wires the beans by hand. |
| PDFBox | 3.0.8 | Text extraction on ingest and every generated PDF (proposals, briefs, chat exports). |
| ICU4J | 78.3 | Arabic shaping and bidi reordering for `bloom-pdf`. |
| Tess4J | 5.20.0 | OCR for scanned PDFs with no text layer. |
| `google-api-services-drive` | `v3-rev20260823-2.0.0` | Drive v3 client. |
| `google-api-client` | 2.9.1 | OAuth plumbing behind it. |
| `neo4j` image | `2026.06.0` | Matches what V1 runs. Neo4j upgrades the store format in place, so this tag is pinned, never `latest`. |
| `mongo` image | `8.2` | MongoDB 8.0 refuses to start on Linux kernel 6.19+ (SERVER-121912); 8.2 is the first tag that starts on Docker Desktop's VM. |
| `apache/kafka` image | `4.1.0` | Same major as the `kafka-clients` 4.1.2 that Spring Kafka 4.0.7 pulls in, and as the Testcontainers broker. |
| `redis` image | `7-alpine` | Bounded at 512 MB with `allkeys-lru`. |
| `openzipkin/zipkin` image | `3` | Trace collector for the `infra` profile. |

Boot 4.0 split its test support into per-technology modules that
`spring-boot-starter-test` no longer pulls in. A module needing
`TestRestTemplate`, `@WebMvcTest` or `WebTestClient` declares
`spring-boot-resttestclient` (plus `spring-boot-restclient`),
`spring-boot-webmvc-test` or `spring-boot-webtestclient` test-scoped.

## Running it

Prerequisites: Java 25, Docker, Node 22, and a `deploy/.env`:

```bash
cp deploy/.env.example deploy/.env
# then fill it in — see "Login and security" for what each variable is
```

`deploy/.env` is gitignored and `deploy/.env.example` holds placeholders only.
On Windows use `mvnw.cmd` wherever `./mvnw` appears below.

### Dev — infrastructure and platform in Docker, services from the IDE

```bash
docker compose -f deploy/docker-compose.yml --profile infra --profile platform up -d
./mvnw clean install
```

Then run whichever services you are working on from the IDE or with
`./mvnw -pl services/<name> spring-boot:run`. Their defaults already point at
the published ports: Config Server on `localhost:8888`, Eureka on
`localhost:8761`, Kafka on `localhost:29092` (the broker advertises a second
`PLAINTEXT_HOST` listener for exactly this), Zipkin on `localhost:9411`. Give
the IDE run configuration at least `INTERNAL_SERVICE_TOKEN`, plus whatever that
service needs — `GEMINI_API_KEY` for `ai-service`, `NEO4J_*` for
`knowledge-service`, and so on.

The SPA runs against the gateway through Vite's proxy:

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

`frontend/vite.config.ts` proxies `/api` and `/.well-known` to
`http://localhost:8080`, so the browser only ever talks to its own origin.

### Full — everything in Docker

```bash
docker compose -f deploy/docker-compose.yml --profile infra --profile platform --profile apps up -d --build
```

Sixteen containers: five in `infra` (neo4j, mongodb, redis, kafka, zipkin),
three in `platform` (config-server, discovery-server, api-gateway) and eight in
`apps` (the seven services plus the nginx-served SPA). The SPA is on
<http://localhost:3000>, and nginx proxies `/api` and `/.well-known` to
`api-gateway:8080` on the Compose network — the browser never learns the
gateway's address. Every Java container's healthcheck probes its own
`/actuator/health/readiness`, and the `apps` services wait on
`api-gateway: service_healthy`.

`ingestion-service` builds from its own `services/ingestion-service/Dockerfile`
rather than the shared `deploy/Dockerfile.service`, because Tess4J loads
`libtesseract` from the system on Linux. Everything else builds from the shared
one with a `MODULE_PATH` build arg.

### Tests

```bash
./mvnw clean install
cd frontend && npm install && npm test
```

Testcontainers-backed tests (Neo4j, Kafka, MongoDB) skip when the Docker daemon
is down rather than failing, so a green build without Docker covers less than a
green build with it. Start Docker before trusting the number.

### Two things that look like misconfiguration and are not

The Config Server reads `config-repo/` from the repository root.
`spring-boot:run` defaults its working directory to the module being run, so the
config-server module pins `<workingDirectory>` to the multi-module root —
without that, the native backend resolves `file:./config-repo` inside
`infrastructure/config-server/` and silently serves zero property sources.

A 503 from the gateway on the first attempt after startup is normal. Eureka
caches its registry response for 30s and clients refresh on their own interval,
so the gateway can take up to a minute to see a service. Retry before debugging;
`curl http://localhost:8080/actuator/health` shows what the gateway has actually
discovered.

## Login and security

One shared login, as V1 had. The gateway authenticates it and hands out an RS256
JWT that every service verifies for itself.

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username": "'"$APP_AUTH_USER"'", "password": "'"$APP_AUTH_PASSWORD"'"}'
# {"token":"eyJ…","tokenType":"Bearer","expiresIn":43200}
```

`expiresIn` is `bloom.auth.token-ttl` in seconds (default `PT12H`). The
credentials are compared as fixed-length SHA-256 digests, so neither content nor
length leaks through timing. The token carries `sub` and `scope: user` and
nothing else — there are no per-user accounts or roles. Its issuer is
`https://bloom-gateway`.

The public key is published as a JWKS:

```bash
curl -s http://localhost:8080/.well-known/jwks.json
```

Services fetch it through `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`
(`JWT_JWKS_URI`, default `http://localhost:8080/.well-known/jwks.json`) and cache
it. No key material is ever distributed through the Config Server.

Anonymous at the gateway, from `GatewaySecurityConfig` — everything else needs a
bearer token:

| Path | Why |
|---|---|
| `/api/v1/auth/login` | issues the token |
| `/.well-known/jwks.json` | public key, fetched before any token exists |
| `/actuator/health`, `/actuator/health/**` | Compose healthchecks run without credentials |
| `/api/rag/auth/google/callback` | Google's browser redirect cannot carry a Bearer token |
| `/api/v1/rag/sync/auth/callback` | the versioned target of that redirect |

Inside each service, `bloom-security`'s auto-configuration permits
`/actuator/health` and `/actuator/health/**` plus anything listed under
`bloom.security.permit-all` — only `storage-service` uses that, for
`/api/v1/storage/auth/callback` — and requires authentication everywhere else.

### `X-Internal-Token`

The gateway is the trust boundary: it strips any inbound `X-Internal-Token` as a
forgery and stamps the real one onto authenticated traffic it forwards, and each
service's Feign interceptor stamps it on outbound service-to-service calls.
`InternalTokenFilter` compares the header against `bloom.security.internal-token`
with a constant-time digest comparison and, on a match, authenticates the request
as the principal `internal-service` with `ROLE_SERVICE` — which is what lets a
Kafka-triggered hop, where there is no user and therefore no JWT to forward,
still reach a protected endpoint.

### Two credentials, and which one is used when

Every service accepts either credential, and a service-to-service call normally
carries both:

- **The caller's JWT.** `InternalTokenFeignInterceptor` copies the incoming
  `Authorization` header onto outbound Feign calls, so the original user's token
  travels the whole chain and each hop validates it against the gateway's JWKS.
- **`X-Internal-Token`.** A machine identity for hops that have no user at all —
  a Kafka-triggered call such as storage reacting to `bloom.documents.indexed`
  has no request to copy a header from.

Verified rather than assumed: restarting `retrieval-service` with a deliberately
mismatched `INTERNAL_SERVICE_TOKEN` and then asking the Event Advisor a question
still succeeds, which is only possible if the forwarded JWT is what authenticates
the `chat -> retrieval` hop. The concern that `RequestContextHolder`, being a
`ThreadLocal`, would be empty inside the circuit breaker's supplier does not
apply here: with no bulkhead configured, Spring Cloud CircuitBreaker's
Resilience4j implementation runs the supplier on the calling thread.

### Generating the keys

Never commit any of these values. `deploy/.env.example` contains placeholders
only, and the Config Server resolves `${ENV_VAR}` placeholders from the
environment.

```bash
# RS256 signing key pair for the gateway
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl rsa -pubout -in jwt-private.pem -out jwt-public.pem

# Shared service-to-service token
openssl rand -base64 32

# Login password
openssl rand -base64 24
```

Set `JWT_PRIVATE_KEY_PEM` and `JWT_PUBLIC_KEY_PEM` to the *contents* of those two
files. Both or neither — `JwtKeys` refuses a half-configured pair. Leave both
blank and the gateway generates an ephemeral 2048-bit pair at startup and logs a
warning: every token dies with the process, which is fine for development and
never for anything else.

### Environment variables

Gateway and platform:

| Variable | Default | Meaning |
|---|---|---|
| `APP_AUTH_USER` | — (required) | The single login username |
| `APP_AUTH_PASSWORD` | — (required) | The single login password |
| `INTERNAL_SERVICE_TOKEN` | — (required, every service) | Shared `X-Internal-Token` value |
| `JWT_PRIVATE_KEY_PEM` | blank | RS256 private key PEM; blank ⇒ ephemeral key |
| `JWT_PUBLIC_KEY_PEM` | blank | Matching public key PEM |
| `JWT_TTL` | `PT12H` | Token lifetime |
| `JWT_JWKS_URI` | `http://localhost:8080/.well-known/jwks.json` | Where services fetch the public key |
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Config Server for `spring.config.import` |
| `CONFIG_REPO` | `file:./config-repo` | Config Server's native search location |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Registry |
| `EUREKA_HOSTNAME` | `localhost` | The registry's own advertised hostname |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | `kafka:9092` inside Compose |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` | Span collector |

Data stores and external services:

| Variable | Default | Used by |
|---|---|---|
| `NEO4J_URI` | `bolt://localhost:7687` | knowledge-service |
| `NEO4J_USER` / `NEO4J_PASSWORD` | `neo4j` / `password` | knowledge-service, and the Compose `neo4j` container |
| `MONGO_URI` | `mongodb://localhost:27017` | chat-service, storage-service |
| `MONGO_CHAT_DB` | `chatdb` | chat-service — set to V1's database to reuse its sessions |
| `MONGO_TOKEN_DB` | `tokendb` | storage-service — set to V1's database to reuse its Drive token |
| `REDIS_URL` | `redis://localhost:6379` | ai-service |
| `GEMINI_API_KEY` | — (required) | ai-service |
| `GEMINI_MODEL` | `gemini-3-flash-preview` | ai-service |
| `GEMINI_FALLBACK_MODELS` | `gemini-2.5-flash,gemini-2.0-flash-001` | ai-service |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-001` | ai-service — changing it changes every vector and every cache key |
| `CACHE_KEY_PREFIX` | `bloom:` | ai-service |
| `RAG_TOP_K` | `15` | retrieval-service, chat-service |
| `PDF_DIRECTORY` | `./ISM_PDF` | ingestion-service's local ingest path. In Compose it names the **host** directory to mount (default `./pdfs`); the container always reads `/data/pdfs` |
| `CHUNK_SIZE` / `CHUNK_OVERLAP` | `1000` / `200` | ingestion-service |
| `TESSDATA_PATH` | blank | ingestion-service; blank ⇒ the `eng.traineddata` bundled in the jar |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | blank | storage-service |
| `GOOGLE_CREDENTIALS_PATH` | `./secrets/client_secret.json` | storage-service, if the pair above is not set |
| `GOOGLE_OAUTH_REDIRECT_URI` | `http://localhost:8080/api/rag/auth/google/callback` | storage-service — must match the Console |
| `GOOGLE_DRIVE_FOLDER_ID` | blank | storage-service — the folder to sync |
| `DRIVE_SYNC_INTERVAL_MS` | `300000` | storage-service auto-sync poll (minimum 10000) |
| `FRONTEND_URL` | `http://localhost:5173` | Where storage-service sends the browser after consent (`http://localhost:3000` in Compose) |

## Google Drive

`storage-service` is the only holder of the Drive OAuth token. Everything else
reaches Drive through it.

**Register the redirect URI.** In the Google Cloud Console, the OAuth client's
authorized redirect URI must be:

```
http://localhost:8080/api/rag/auth/google/callback
```

That is V1's path, kept deliberately — changing it is an external console change.
The gateway answers on it and 307-redirects to
`/api/v1/rag/sync/auth/callback` with the query string (and therefore the
authorization `code`) intact, which routes to storage-service's
`/api/v1/storage/auth/callback`. Both paths are anonymous, on both sides, because
a browser navigation cannot carry a Bearer token.

The consent endpoint is pinned to `https://accounts.google.com/o/oauth2/v2/auth`.
The Java client's default is the legacy `/o/oauth2/auth`, which answers
`Error 400: invalid_scope` *after* sign-in against this client. See the Phase 3b
note below.

**Authenticate from the SPA.** Settings → *Connect Google Drive*. The button
follows the `authUrl` the SPA already has from `GET /api/v1/rag/sync/status`, or
fetches one from `GET /api/v1/rag/sync/auth?force=true`, which answers
`{status, message, authUrl}` rather than redirecting. After consent,
storage-service exchanges the code, writes the token to Mongo
(`MONGO_TOKEN_DB`, collection `drivetokens`, in V1's document shape), starts the
auto-sync poll and sends the browser to `FRONTEND_URL/?auth=success` — or
`?auth=error` if the exchange failed. The Settings badge flips to *Connected*
within one 30-second status poll.

**What the poll does.** `AutoSyncScheduler` runs `DriveSyncService.autoSync`
immediately and then every `DRIVE_SYNC_INTERVAL_MS` (default five minutes). It
starts on a successful callback, and on startup when a token is already stored.
Each run lists every PDF under `GOOGLE_DRIVE_FOLDER_ID` recursively and diffs it
against the `driveFileId → modifiedTime` map that knowledge-service keeps on its
`Document` nodes:

- an id that is new, or whose `modifiedTime` differs, publishes
  `bloom.documents.discovered` → ingestion-service downloads, chunks, embeds and
  publishes `bloom.chunks.batch-ready` → knowledge-service indexes and publishes
  `bloom.documents.indexed`;
- an id Drive no longer returns publishes `bloom.documents.deleted`, and
  knowledge-service removes the document and its chunks.

The recorded `modifiedTime` is only updated when `bloom.documents.indexed` comes
back, so a file that failed to ingest is picked up again on the next poll rather
than being silently marked done. `POST /api/v1/rag/sync` runs the same diff on
demand; `GET /api/v1/rag/sync/check` reports it without acting.

## Reference trace

Every service reports to Zipkin with `management.tracing.sampling.probability: 1.0`
— all spans, which is right for a demo stack and not for production.

1. Bring up the full stack and log in at <http://localhost:3000>.
2. Ask the Event Advisor anything.
3. Open <http://localhost:9411> and query for service `api-gateway`.

The trace to look for spans gateway → chat → retrieval → ai / knowledge → ai.
A Drive-triggered ingest should produce a second trace that crosses Kafka:
`spring.kafka.template.observation-enabled` and
`spring.kafka.listener.observation-enabled` are both set in
`config-repo/application.yml`, so the producer's context rides the message
headers to the consumer. Both traces are checks for the live walkthrough, not
claims already made.

A screenshot of the chat trace belongs at `docs/images/reference-trace.png`. It
is captured during that walkthrough, from a real run; the file is not in the
repository yet.

## Spec deviations log

Every place the build disagrees with the design spec or the phase plan, and why.

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
| Port V1's `embeddingService.ts` — the SPA embeds the query in the browser | Deleted. `QueryForm` posts `{query, useAI}`; `RetrievalService` falls back to `embed(List.of(query))` when `queryEmbedding` is null | Vite inlines `VITE_GEMINI_API_KEY` into the bundle, shipping the Gemini key to every visitor — and ai-service already owns embedding |
| JWT issuer `bloom-gateway` | `https://bloom-gateway` | `Jwt#getIssuer()` returns `java.net.URL` and throws for a non-absolute-URI issuer |
| Add `X-Internal-Token` headers to every `@WebMvcTest` | Slices unchanged; two properties added to the `@SpringBootTest` classes only | Boot 4's `@WebMvcTest` slice applies no `SecurityAutoConfiguration`, so the library cannot 401 it; the real failure was `@NotBlank internalToken` failing to bind under `spring.config.import=` |
| `@ConditionalOnClass` on `bloom-security`'s Feign interceptor `@Bean` method | The bean lives in a nested `@Configuration` that carries the condition | Spring reflects over method signatures before evaluating the condition, and the return type implements `feign.RequestInterceptor` — knowledge-service, which has no Feign, died with `ClassNotFoundException` |
| — | `frontend/.dockerignore` added | The image's `COPY . .` runs after `npm ci`; without it the host's `node_modules` would overwrite the container's |
| Login accepts `?next=` when it starts with `/` | `safeNextPath` rejects `//` and `/\`, then re-resolves against `window.location.origin` | A leading-slash check alone accepts `//evil.example`, which browsers resolve off-origin |

## Phase verification log

What was actually run at the end of each phase, against V1's real data. Each
entry is written after the live walkthrough, not from the plan.

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

## Phase 3a verification (2026-09-16)

`./mvnw clean install` — BUILD SUCCESS across eleven reactor projects.

Two purpose-built PDFs were ingested through the gateway with
`POST /api/v1/rag/ingest`, against the live stack and a real Gemini key:

| Source | Chunks | OCR | Retrieval rank for its own phrase |
| --- | --- | --- | --- |
| `phase3a-smoke-test.pdf` (text layer) | 1 | no | 1st, score 0.854 |
| `phase3a-scanned-test.pdf` (image only, no text layer) | 1 | **yes** | 1st, score 0.9282 |

The scanned file has no text layer at all — its words exist only as pixels —
so PDFBox extracted nothing and Tess4J's OCR produced the text that was
embedded and indexed. Retrieving it returns `SCANNED INGEST CHECK /
Kilimanjaro paper bridge challenge`, which is the OCR output.

`bloom.documents.indexed` carried
`{"source":"phase3a-smoke-test.pdf","driveFileId":null,"chunkCount":1,…}` —
`driveFileId` null because this is the local path, not Drive.

Re-running the same ingest left the chunk count at 1 with the id
`phase3a-smoke-test.pdf-0-0`: deterministic ids plus the delete-then-MERGE
order make re-ingestion idempotent.

A poison message on `bloom.chunks.batch-ready` was retried three times with
growing gaps (2s, 4s) and then parked on the dead-letter topic.

### The dead-letter topic is `-dlt`, not `.DLT`

Spring Kafka 4.x names it `bloom.chunks.batch-ready-dlt`. Searching the topic
list for `.DLT` finds nothing and looks like the error handler never fired.

### Every service declares the topics it consumes, not just the ones it produces

Phase 3a verification caught this the hard way. `knowledge-service` was started
before `ingestion-service`, its listener subscribed to
`bloom.chunks.batch-ready`, and the broker auto-created that topic with its
default **single** partition. `ingestion-service` then started and its
`NewTopic` bean raised the topic to three partitions — but the running consumer
kept its one-partition view and was assigned only partition 0. A test message
sat unconsumed on partition 2 for over ten minutes; it did not self-heal.

In production that is silent data loss: two thirds of every document's chunk
batches would never be indexed, with no error anywhere. The fix is that each
service now declares a `NewTopic` for the topics it *consumes* as well as the
ones it produces — `KafkaAdmin` is idempotent, both sides agree on three
partitions, and whichever starts first creates the topic with the right shape.
Restarting `knowledge-service` with the declarations in place assigned all
three partitions and drained the stranded message to the dead-letter topic.

Do not "tidy up" those consumer-side `NewTopic` beans on ownership grounds.

## Phase 3b verification (2026-09-16)

`./mvnw clean install` — BUILD SUCCESS across twelve reactor projects.

OAuth completed through the gateway's legacy shim against V1's own Google
client, and the token persisted to Mongo `tokendb.drivetokens` in V1's document
shape — `accountId` plus `token.{access_token, refresh_token, scope,
token_type, expiry_date}`, the expiry a `NumberLong` exactly 60 minutes out.
Restarting `storage-service` logged `Loaded existing Google Drive token`, so a
V2 deployment pointed at V1's database reuses the token V1 already stored.

`POST /api/v1/rag/sync` queued all 24 PDFs in V1's Drive folder. Every one was
downloaded, chunked, embedded and indexed with **no manual step**:

| Stage | Evidence |
| --- | --- |
| `storage-service` | `Sync: 24 of 24 Drive files queued for ingestion` |
| `ingestion-service` | `Ingesting from Drive: Dell Technologies/Customer_Brief_…pdf (1rqA4s…)` |
| `knowledge-service` | `Indexed Dell Technologies/Customer_Brief_…pdf (4 chunks)` |
| `storage-service` | 24 × `Recorded Drive metadata for …` |

`GET /api/v1/drive-files` then listed 24 ids against RFC 3339 `modifiedTime`
strings exactly as Drive returned them, and retrieving "team building
activities" came back with chunks from `Activity_Library.pdf` and
`dell/Customer_Brief_2026-07-22T09-30-45.pdf`. A second auto-sync poll over the
same 24 files queued nothing, so change detection is idempotent.

Subfolder paths survive intact — `dell/`, `Dell Technologies/`,
`Teambuilding/candylove/`. `Dell Technologies` keeping its space is exactly why
`sanitizeFolderName` must not replace whitespace: V1's `/\s+/` never matched,
so the live folders contain spaces and "fixing" the regex would create
duplicates.

The add/modify/delete lifecycle was then exercised on a throwaway PDF uploaded
through `POST /api/v1/storage/files`, so none of the 24 real files were touched:

| Step | Detected as | Result |
| --- | --- | --- |
| Upload via the storage API | `1 added` of 25 seen | ingested, `drive-files` 24 → 25 |
| Content replaced in Drive | `0 added, 1 modified, 0 deleted` | re-indexed, still **1** chunk |
| File deleted from Drive | `0 added, 0 modified, 1 deleted` | `Deleted 1 chunks`, `drive-files` back to 24 |

Re-indexing genuinely replaces rather than appends: after the modification the
file's only chunk contained the new marker and the old one was unfindable, and
the chunk count stayed at 1. After the deletion `/sync/check` reported
`hasChanges: false` — Drive and Neo4j fully reconciled.

A cold restart of the whole stack, containers included, reloaded the token from
`tokendb` and found all 24 files still recorded, with no re-consent.

### The Google consent endpoint must be v2, not the Java client default

`GoogleOAuthConstants.AUTHORIZATION_SERVER_URL` is the legacy
`https://accounts.google.com/o/oauth2/auth`. Against this OAuth client it
answers `Error 400: invalid_scope` *after* sign-in, reporting a garbled scope
(`invalid=[ht]`) even though the request carries both Drive scopes intact. The
byte-identical request to `https://accounts.google.com/o/oauth2/v2/auth`
completes. V1 never hit this because google-auth-library has always used v2
(`oauth2AuthBaseUrl`). `DriveAuthManager` now pins v2 and a test asserts it.

The failure is invisible to unit tests: the generated URL is well-formed, and
an unauthenticated probe of the consent endpoint returns the sign-in page, so
scope validation is never reached. Only a real browser sign-in surfaces it.

### Mongo properties are `spring.mongodb.*`, not `spring.data.mongodb.*`

Boot 4 deprecated `spring.data.mongodb.uri` and `.database` in favour of
`spring.mongodb.*`, and `spring.mongodb.uri` carries a non-null default of
`mongodb://localhost/test`. That default **silently shadows** the deprecated
keys — no warning, no failure, no startup error. `config-repo` said `tokendb`
while the OAuth token was being written to `test`.

Left unfixed this would have defeated the entire reason the token is stored in
V1's shape: `MONGO_TOKEN_DB` could never have pointed a V2 deployment at V1's
database, and every deployment would have demanded a fresh consent. Only
`auto-index-creation` still lives under `spring.data.mongodb`. Check the
database a service actually writes to, rather than the one its config names.

### MongoDB must not be a startup dependency

`spring.data.mongodb.auto-index-creation` builds indexes during context
refresh, so an unreachable MongoDB aborts startup with a
`BeanCreationException` from `CreateIndexesOperation` — long before
`MongoTokenStore.load`'s deliberate swallow-and-degrade ever runs. Pointing
`storage-service` at an unreachable cluster made it refuse to start at all,
rather than starting Drive-less. V1 logs a warning and carries on.

Auto-index creation is therefore off, and `DriveTokenIndexInitializer` creates
the unique `accountId` index from an `ApplicationRunner` that tolerates
failure. With MongoDB unreachable the service now starts, logs two warnings and
serves `/sync/status` with `isAuthenticated: false` and a usable `authUrl`.

It matches the existing index **on shape, never on name**. MongoDB rejects an
otherwise-identical index under a different name with error 85
(`IndexOptionsConflict`), and the name depends on who created the collection:
Spring Data's auto-index creation names it `accountId`, V1's Mongoose names it
`accountId_1`. Creating it blindly fails against either.

## Phase 4 verification (2026-09-17)

`./mvnw clean install` — BUILD SUCCESS across **fifteen** reactor projects.

The full stack ran against V1's real Google Drive folder and, for the
compatibility check, V1's own MongoDB Atlas cluster. Eureka listed
`CHAT-SERVICE` and `PROPOSAL-SERVICE` alongside the six services from earlier
phases.

| Surface | Evidence |
| --- | --- |
| Event Advisor | `session_1789634648490_ziic06w` — V1's `session_<epoch>_<7 chars>` — with ten real sources from V1's corpus and a 3808-character answer |
| History | A second turn on the same id produced a different theme ("The Archive Of Awesome" vs "The Great Odyssey Lounge") and six distinct activity names: the anti-repetition block engaged |
| Session list | One message per session from the server-side `$slice` projection; the full session returned all four, roles `user`/`model` |
| Chat PDF | 16613 bytes of Helvetica-only PDF for a Latin session |
| Arabic PDF | The same export for an Arabic session embeds `AATJYO+Amiri-Regular` and `AAUGWI+Amiri-Bold` (CID TrueType, Identity-H, subsetted) with 2372 presentation-form codepoints |
| Drive backup | `Chats/Session_2026-09-17T08-45/Chat_Backup_session_.pdf`, confirmed by walking the file's parents through the Drive API |
| Proposals | Nine slides, `cover summary concept agenda activities venue catering investment closing`, nine pages at exactly 1280 × 720 |
| Save vs download | `Dell Technologies_Proposal_2026-09-17.pdf` into the company's own Drive folder; the download attachment is the undated `Dell Technologies_Proposal.pdf` |
| Event intake | A `Parity Test Co` folder with `Customer_Brief_2026-09-17T08-48-01.pdf`, and `briefCount: 1` for it on the next `/proposals/companies` |
| Drive unauthenticated | `401` with `errorCode: DRIVE_NOT_AUTHENTICATED`, `authUrl: /api/v1/rag/sync/auth?force=true`, `error: "Google Drive not authenticated."` |
| Re-authentication | The token returned to `tokendb.drivetokens` in V1's shape — `accountId`, access and refresh tokens, and an expiry `Long` exactly sixty minutes out |

Every artifact written into V1's Drive during the run — the saved proposal, the
`Parity Test Co` folder and the chat session folder — was deleted afterwards and
re-checked as `404`. `/proposals/companies` returned to its original three
companies once the sixty-second cache expired.

### A V2 pointed at V1's database reads V1's sessions

`MONGO_CHAT_DB=test` against V1's Atlas cluster started `chat-service` cleanly
and listed all five of V1's sessions from July 2026, newest first. Reading one
in full returned its four messages with sources intact and V1's own content
("### Theme Concept: The Masterpiece Heist"); Mongo reported the same four.

`chat-service` was returned to the local database immediately afterwards. A
service left pointing at V1's cluster writes new sessions into real customer
data.

### The unique index is the thing that would have stopped it

V1's Mongoose created `chatsessions`' unique index under MongoDB's default name,
`sessionId_1`. Spring Data does not use the generated name: an unnamed
`@Indexed` resolves to the dot-path, `sessionId`. MongoDB rejects a same-key
index under a different name with error 85, and
`MongoPersistentEntityIndexCreator` rethrows it as a
`DataIntegrityViolationException` **during context refresh** — a startup abort,
not a degraded request.

So the one deployment mode `config-repo/chat-service.yml` advertises — "set
`MONGO_CHAT_DB` to V1's database to reuse its existing sessions" — was the one
mode that could not boot. No test could see it, because every test gets a fresh
container where Spring creates the index itself.

`@Indexed(unique = true, useGeneratedName = true)` lets the server name it, so
V1's existing index is a silent no-op. `auto-index-creation` is now `false`, as
in `storage-service`, and `ChatSessionIndexInitializer` creates the index when it
is genuinely absent — matching on shape, never on name, exactly as
`DriveTokenIndexInitializer` already did.

This is the same trap documented for `storage-service` above. Note that
`DriveTokenDocument.accountId` still lacks `useGeneratedName`; it is dormant only
because that service disables auto-index creation.

### PDFKit measures from the top, PDFBox from the bottom

V1 drew the customer brief's footer at `page.height - 40`, which in PDFKit's
top-left coordinate system means forty points from the *bottom*. PDFBox's origin
is bottom-left, so the same expression put the footer forty points from the
*top* — over the indigo header band on page one, and above the first line of
content on every page after it.

The test did not catch it because `PDFTextStripper` extracts text wherever it
was drawn. Asserting that the footer *appears* passes against a footer printed
in the wrong place. The assertion now walks the real `TextPosition` baselines;
on the brief generated during this run they sit at 40.0 from the bottom on all
four pages.

### Arabic wraps before it is shaped, never after

Shaping and reordering a whole string and *then* splitting it into lines puts
the logically last words on the first line, so a paragraph reads bottom to top.
Shaping each word separately and drawing the words left to right is the same
error one level down: the letters join correctly and the phrase runs backwards.

Both are avoided the same way — group logical words until the next would
overflow, then shape that group as a unit. `MixedTextWriter.measure` and
`writeLine` share one layout pass so the two can never disagree about where a
line breaks.

### `spring-boot:run` forks, so `pkill -f` misses the service

Clearing `tokendb.drivetokens` and restarting `storage-service` appeared to
leave Drive working, which looked like the re-auth path failing to engage. It
was not: `pkill -f storage-service` had matched nothing. `spring-boot:run` forks
a JVM whose command line carries the main class, not the `-pl` argument, so the
original process was still serving Drive from an in-memory credential while
Mongo held no token at all.

Find the process holding the port and kill that:

```bash
netstat -ano | grep ":8086 .*LISTENING"
```

## Phase 5 verification (2026-09-17)

`./mvnw clean install` — BUILD SUCCESS across **sixteen** reactor projects:
**355 tests, 0 failures, 0 errors, 0 skipped**. The phase began at 293 tests
with 30 skipped; those 30 were Docker-dependent and had been skipping silently,
so the count now means something.

Verified with infra in Docker and every Java process running from the built
jars. Eight Eureka registrations, all `UP`; Zipkin knew all nine services.

| Criterion | Evidence |
| --- | --- |
| Anonymous request rejected | `GET /api/v1/rag/ping` → `401`, `application/problem+json`, `errorCode: UNAUTHENTICATED` |
| Authenticated request | The same path with a Bearer token → `200 {"service":"knowledge-service","status":"ok"}` |
| Tampered token | One character appended to the signature → `401` |
| **Forged internal token** | `X-Internal-Token: forged-value` from outside → `401`. The gateway strips the header on the way in, so the edge cannot impersonate a service |
| Login | `POST /api/v1/auth/login` → RS256, `kid` matching the JWKS entry, `iss=https://bloom-gateway`, `sub=bloom`, `scope=user`, `expiresIn=43200` |
| Bad credentials | `401` `Invalid credentials`, constant-time compared |
| JWKS | One RSA key, `use=sig`, `kid` present, and no `d`/`p`/`q`/`dp`/`dq`/`qi` — checked explicitly |
| Reference trace | One trace, **48 spans, 6 services**: gateway → chat → retrieval → ai (`embed/batch`) and knowledge (`chunks/search`) → ai (`generate`). Redis appears too; the embedding cache is traced. `circuit-breaker` spans wrap every Feign hop and both Gemini calls. Captured in `docs/images/reference-trace.png` |
| Kafka trace | A single trace across two topics and three services: `ingestion bloom.chunks.batch-ready send` → `knowledge process` → `knowledge bloom.documents.indexed send` → `storage process` |
| Async ingest | `POST /api/v1/rag/ingest` → `202 {jobId}`; polling → `COMPLETED`, `chunkCount: 1`, `ocrUsed: false` |
| Breaker opens | With `ai-service` stopped, calls 1–3 took ~2.1 s (real attempts), calls 4+ ~0.03 s |
| Breaker response | `503` `SERVICE_UNAVAILABLE` — only after fixing a masking bug, see below |
| Breaker recovery | Automatic ~60 s after `ai-service` returned, with no restart. Longer than the 30 s `waitDurationInOpenState` because two breakers recover in sequence: retrieval→ai, then chat→retrieval |
| Readiness groups | knowledge `neo4j`, chat `mongo`, storage `mongo`, ai none (Redis deliberately excluded). Kafka and Eureka appear in no group |
| Build info | `/actuator/info` → `artifact=knowledge-service, version=0.0.1-SNAPSHOT` |
| SPA guard | `/intake` while logged out → `/login?next=%2Fintake` |
| SPA authenticated | App shell renders; `/api/v1/rag/chat/sessions` and `/api/v1/rag/sync/status` both `200` through the Vite proxy |
| SPA 401 handling | An invalid token → `apiFetch` clears it and redirects to `/login?next=%2Fadvisor` |

### Four defects that only running the system exposed

**chat-service masked its own 503.** `RetrievalClientFallback` throws
`ServiceUnavailableException` so the browser sees a clean 503, but
`ChatWithDocumentsUseCase` caught `RuntimeException` and re-wrapped everything
as `ExternalServiceException` — a 502. Every unit test passed, because none of
them exercised a *peer* failing.

**The test suite was not hermetic.** Each module's `application.yml` declares
`spring.config.import=optional:configserver:…`, and setting
`spring.config.import=` in a `@SpringBootTest` does **not** suppress it. Tests
silently reached whatever Config Server happened to be on `:8888` and took
config-repo's values. Starting the stack for this verification broke
previously-green `@WebMvcTest` slices, and it is also why a Kafka test's
`auto-offset-reset` flipped between runs. Every module now pins
`spring.cloud.config.enabled=false` in its test resources.

**A Kafka test had been passing by not running.** `KafkaWiringIntegrationTests`
published before the listener group resolved its first position; with Kafka's
default `auto.offset.reset=latest` the consumer seeks past the record. Roughly a
1-in-5 pass rate — invisible because Docker was down and
`@Testcontainers(disabledWithoutDocker = true)` skips silently. The merge-base
commit fails identically. Fixed by waiting for partition ownership, not by
raising the timeout.

**`@ConditionalOnClass` on a `@Bean` method does not work** when the method's
return type implements the optional class: Spring reflects over method
signatures before evaluating the condition. knowledge-service, the only service
with no Feign, died with `ClassNotFoundException: feign.RequestInterceptor`.
bloom-security's own tests could not catch it — its test classpath has the
optional dependency.

### Two behaviours worth knowing

`/actuator/info` now requires authentication. `bloom-security` opens only
`/actuator/health`, `/actuator/health/**` and each service's permit-all paths,
so an anonymous `/actuator/info` returns `401`. Health probes stay open.

`/actuator/health` no longer shows details anonymously. It is `when-authorized`:
with `always`, an unauthenticated caller on the published gateway port received
the whole Eureka topology, container IPs and ports, and host disk paths.
Anonymous callers get `{"status":"UP"}` and a `200`, which is all the Compose
probes read.

### Not verified

- **The full Compose stack.** Not brought up on 2026-09-17; done on 2026-09-20,
  see the re-verification below. Bringing it up is what exposed the tracing
  defect, and nothing else would have.
- **The login form's own submit handler.** The `POST /api/v1/auth/login` round
  trip and the authenticated app were both verified, the latter by seeding the
  `bloom.token` value that a successful login writes.
- **Google Drive OAuth.** Completing the consent flow needs a real Google grant.
  The Kafka-crossing trace was obtained through the local PDF ingest path, which
  drives the same topics and listeners.

## Phase 5 re-verification (2026-09-20)

Re-run with Docker available, and with the Compose stack actually brought up for
the first time.

`./mvnw clean install` — BUILD SUCCESS across sixteen reactor projects:
**355 tests, 0 failures, 0 errors, 0 skipped**, aggregated over 87 surefire
reports. An earlier run the same day with Docker stopped gave 355/0/0/**30** —
the thirty being exactly the eight Testcontainers classes. Worth knowing which
number you are looking at.

### The stack, up

`docker compose --profile infra --profile platform --profile apps up -d` →
exit 0, **16/16 containers running, 15/15 healthy**. The sixteenth, `frontend`,
declares no healthcheck, so it reports `Up` and never `healthy`; the exit
criterion's "fifteen containers" undercounts the stack by one.

| Criterion | Evidence |
| --- | --- |
| Anonymous request | `GET /api/v1/rag/ping` → `401` |
| Authenticated request | Same path with a Bearer token → `200 {"service":"knowledge-service","status":"ok"}` |
| Tampered token | One character appended → `401` |
| Forged internal token | `X-Internal-Token: forged-value` → `401` |
| **Real internal token, replayed from outside** | The genuine value from `.env`, sent from the host → `401`. The gateway strips the header on ingress, so knowing the secret does not help an external caller |
| Bad credentials | `401` |
| JWKS | One RSA key, `use=sig`, `kid` present, no `d`/`p`/`q`/`dp`/`dq`/`qi` |
| Eureka | **8 registrations, all `UP`** |
| SPA on nginx `:3000` | `200` — the containerised path, never exercised before |
| Zipkin | `200`, and after the fix below, all eight services reporting |
| Reference trace | One request → a single **12-span trace across api-gateway and knowledge-service**, security filter chain spans included |

### The defect that only the containers could expose

**Tracing was silently dead in every container.** Spring Boot 4 renamed
`management.zipkin.tracing.endpoint` to `management.tracing.export.zipkin.endpoint`
and deprecated the old name at `level=error`, which drops the key without a
warning. Every service therefore fell back to the built-in default,
`http://localhost:9411/api/v2/spans`.

On the host that default *is* Zipkin, which is why the 2026-09-17 evidence —
48 spans across 6 services — was real and yet proved nothing about the
deployment target. In containers each service posted its spans to its own empty
localhost. Zipkin held zero traces and listed zero services, while the services
logged `Dropped 5 spans due to ConnectException()` once at WARN and everything
after at FINE, which is why the logs looked quiet.

The chain that settled it, after `ZIPKIN_ENDPOINT` was confirmed set in every
container, config fetched, and `curl http://zipkin:9411` returning `200` from
inside the failing service: a thread dump named the target outright —
`AsyncReporter{ZipkinHttpClientSender{http://localhost:9411/api/v2/spans}}` —
and setting the property directly by environment variable did not move it, which
ruled out placeholder resolution and left the property name itself.

### Two more things the bring-up found

**A second host bind-mount.** `ingestion-service` mounts
`${PDF_DIRECTORY:-./pdfs}:/data/pdfs:ro`, which `.env` points at
`deploy/ISM_PDF`. The first bring-up aborted on it and left all seventeen
containers stopped. The directory is empty and untracked — Docker created it as
a side effect of the failed mount — so a fresh clone has nothing to ingest.

**The images under test were stale.** They had been built 2026-09-17 14:51–14:54,
40 minutes *before* the phase's own final commit at 15:34. Verification ran
against images that predated the code it was verifying. Rebuild before verifying.

### Deviation in this run

Docker Desktop File Sharing excludes this drive, so neither bind-mount could be
used. Both were redirected to named volumes — `config-repo` seeded by
`docker cp`, the PDF directory left empty to match the empty host directory —
via a throwaway override. The config-server image, the container paths and the
`:ro` flags are identical to the committed ones; only the mount source differs.
`docker compose config` over the merged files reports **0 host binds**. Phase 6
removes the config-repo mount for real by moving the Config Server to a git
backend.

### Still not verified

- **Google Drive OAuth consent.** Needs a real Google grant.
- **The login form's own submit handler.** The login round trip and the
  authenticated app are both verified; the form itself is not driven.

## Repository layout

```
.
├── config-repo/                  Config Server's native backend — application.yml plus one file per service
├── deploy/
│   ├── .env.example              Variable names and placeholders. Copy to deploy/.env (gitignored).
│   ├── Dockerfile.service        Shared multi-stage build, parameterised by MODULE_PATH
│   └── docker-compose.yml        Profiles: infra, platform, apps
├── docs/
│   └── superpowers/
│       ├── plans/                Per-phase implementation plans
│       └── specs/                2026-08-30-bloom-spring-microservices-design.md
├── frontend/                     React 19 + Vite SPA
│   ├── Dockerfile                node:22 build → nginx:alpine
│   ├── nginx.conf                SPA deep links; proxies /api and /.well-known to api-gateway:8080
│   ├── .dockerignore             Keeps the host's node_modules and dist out of the image
│   ├── vite.config.ts            Dev server on 5173, same proxy targets on localhost:8080
│   └── src/
│       ├── components/           Login, landing, intake, advisor, proposals, settings
│       ├── services/             apiFetch (JWT + 401 handling), auth, safeNext
│       ├── hooks/                useDriveAuthStatus, useTheme
│       ├── styles/               Design tokens, global, components, animations, responsive
│       └── utils/
├── infrastructure/
│   ├── api-gateway/              8080 — routes, /api/v1/auth/login, JWKS, X-Internal-Token stamping,
│   │                             and the legacy Google OAuth callback shim
│   ├── config-server/            8888 — native backend over config-repo/
│   └── discovery-server/         8761 — Eureka
├── platform/
│   ├── bloom-bom/                Third-party and internal version pins
│   ├── bloom-common/             RFC 9457 problem details, ErrorCode, BloomException, JsonExtractor
│   ├── bloom-events/             BloomTopics and the four event records
│   ├── bloom-pdf/                MixedTextWriter, ArabicText, ScriptRuns, bundled Amiri fonts
│   └── bloom-security/           Resource-server chain, InternalTokenFilter, Feign interceptor
├── services/
│   ├── ai-service/               8081
│   ├── knowledge-service/        8082
│   ├── ingestion-service/        8083 — own Dockerfile; needs system Tesseract
│   ├── retrieval-service/        8084
│   ├── chat-service/             8085
│   ├── storage-service/          8086
│   └── proposal-service/         8087
├── mvnw, mvnw.cmd, .mvn/
└── pom.xml                       Maven multi-module root — fifteen modules
```
