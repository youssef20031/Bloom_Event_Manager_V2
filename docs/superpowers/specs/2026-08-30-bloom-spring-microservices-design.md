# Bloom Event Manager V2 — Spring Microservices Design

**Date:** 2026-08-30
**Status:** Approved design, ready for implementation planning
**Reference implementation:** V1 at `E:/Bloom_Event_Manager` remains running and unmodified

---

## 1. Purpose and Goals

Rebuild the Bloom Event Manager — currently an Express 5 + TypeScript monolith of roughly
15,000 lines — as a Spring Boot microservices system.

The project has two stated drivers, and both shape the design:

1. **Academic / portfolio.** The result must *demonstrate* microservice architecture. A reviewer
   should be able to point at service discovery, an API gateway, centralized configuration,
   circuit breakers, distributed tracing, and asynchronous event-driven communication.
2. **Learning Spring in depth.** Bloom is the vehicle. Where two options are equally correct,
   prefer the one that exercises more of the Spring ecosystem.

Because of driver 1, some services are smaller than a production architect would allow. That is a
deliberate trade, not an oversight. Section 11 records where a production design would differ.

**Feature scope: full parity.** Every endpoint in V1 gets a Java equivalent, including the three
awkward ones — Tesseract OCR fallback, Arabic/English bidirectional PDF generation, and Google
Drive OAuth with polling sync. The cost of this was raised and accepted.

### Success criteria

- Every V1 endpoint is reachable through the gateway and returns equivalent output.
- Dropping a PDF into the configured Google Drive folder results in searchable chunks in Neo4j
  with no manual step.
- A single `/api/v1/chat` request produces one distributed trace spanning gateway, chat,
  retrieval, ai, and knowledge.
- The whole system starts with `docker compose up`, and any single service can be run from an IDE
  against the rest.

---

## 2. What V1 Is Today

**Stack.** Express 5 + TypeScript (pure ESM), React 19 SPA served from the same process via
`vite-express`. Mid-migration from static service classes to a hand-rolled DI container; both
patterns coexist.

**External systems.**

| System | Used for |
|---|---|
| Neo4j | Vector store — chunks, 3072-dim embeddings, cosine index `chunk_embeddings`, Drive file metadata |
| MongoDB Atlas | Chat sessions, Drive OAuth tokens |
| Redis | Embedding cache (LRU, bounded, persisted) |
| Google Gemini | Text generation and embeddings (`gemini-embedding-001`) |
| Google Drive | Document source, proposal and backup destination, OAuth2 |

**Capabilities and where they live.**

| Capability | V1 location | Notes |
|---|---|---|
| RAG retrieval | `controllers/retrievalController.ts` (17 KB) | Dual-search: AI splits query into specific/broad, parallel vector search, dedupe, AI re-rank |
| Chat and sessions | `chatRoutes.ts`, `ChatWithDocumentsUseCase` | Includes hand-rolled Arabic bidi PDF export |
| Ingestion | `scripts/ingestPdfs.ts`, `IngestDocumentUseCase` | pdf-parse, tesseract.js fallback, chunk, embed |
| Drive sync | `controllers/syncController.ts` (15 KB), `services/googleDrive/*` | OAuth lifecycle, polling auto-sync, change detection, re-ingest |
| Proposals | `proposalController.ts`, `GenerateProposalUseCase`, `proposalPdfService` | Generate, save to Drive, download PDF |
| Event intake | `eventIntakeController.ts` | Form submission to Drive |
| App auth | `middleware/requireAppAuth.ts` | HTTP Basic over the entire surface |

**Why decomposition is tractable.** V1's `src/server/interfaces/I*.ts` contracts already describe
the seams almost exactly: `IChunkRepository` maps to knowledge, `IAIService` to ai,
`IStorageService` to storage, `IChatRepository` to chat. The service boundaries below follow
interfaces that already exist rather than inventing new ones.

---

## 3. Version Baseline

Verified against Maven Central metadata on 2026-08-30.

| Component | Version | Rationale |
|---|---|---|
| Spring Boot | **4.0.8** | Highest version supported by any released Spring Cloud train |
| Spring Cloud | **2025.1.3** | Newest train; its POM pins `spring-boot.version` to 4.0.8 |
| Java | **25** | LTS; already the skeleton's target |
| Spring AI | **2.0.1** (core modules only) | See section 7 |
| Maven | multi-module aggregator | — |

**This downgrades the existing skeleton**, which is on Boot 4.1.1. Spring Cloud 2025.1.3 is built
for 4.0.8, and Gateway, Eureka, and Config all come from that train. Running Spring Cloud one minor
version off-baseline is the kind of defect that surfaces late; pinning down is the cheaper choice.

**Package rename.** The skeleton uses `com.Bloom.Event.Manager`, which violates Java package
convention (capitalized segments, dotted words). It becomes `com.bloom.eventmanager.<service>`.
Done now, while the tree is empty, this costs nothing.

---

## 4. Repository Layout

```
Bloom_Event_Manager_V2/
├── pom.xml                          aggregator, packaging: pom
├── platform/
│   ├── bloom-bom/                   dependencyManagement for all modules
│   ├── bloom-common/                shared DTOs, RFC 9457 ProblemDetail model, Feign config
│   └── bloom-events/                Kafka event records + topic name constants
├── infrastructure/
│   ├── config-server/        :8888
│   ├── discovery-server/     :8761  Eureka
│   └── api-gateway/          :8080  Spring Cloud Gateway + JWT issuance/validation
├── services/
│   ├── ai-service/           :8081  owns Redis
│   ├── knowledge-service/    :8082  owns Neo4j
│   ├── ingestion-service/    :8083
│   ├── retrieval-service/    :8084  owns no data
│   ├── chat-service/         :8085  owns Mongo/chatdb
│   ├── storage-service/      :8086  owns Mongo/tokendb
│   └── proposal-service/     :8087
├── frontend/                        React 19 SPA lifted from V1
├── config-repo/                     Config Server backing YAML (native profile)
└── deploy/                          Dockerfiles, Compose files
```

`bloom-common` and `bloom-events` are plain JARs, not Boot applications. They carry **wire
contracts only**. No entity classes, no repository interfaces, and no persistence code is shared
between services — that restriction is what keeps strict data ownership from degrading into a
shared-library backdoor.

---

## 5. Service Topology

Seven business services plus three infrastructure services. Every service registers with Eureka;
inter-service calls use OpenFeign against logical service names, never host and port.

```
                        ┌──────────────┐
    React SPA ─────────▶│ api-gateway  │◀─── eureka ◀─── config-server
                        └──────┬───────┘
         ┌───────────────┬─────┴─────┬───────────────┬──────────────┐
         ▼               ▼           ▼               ▼              ▼
   retrieval-svc     chat-svc   proposal-svc    storage-svc   ingestion-svc
         │               │           │               │              │
         └───────┬───────┴───────────┘               │              │
                 ▼                                   │              │
            ai-service ──────▶ Gemini            Drive API    PDFBox / Tess4J
             (Redis)                                 │              │
                 ▲                                   └──── Kafka ───┘
                 │                                            │
          knowledge-service ◀──────────────────────────────────┘
              (Neo4j)
```

### 5.1 Data ownership

Exactly one service writes each store. Everyone else goes through an API or an event. Physical
infrastructure is shared (one Neo4j container, one Mongo container) but logical ownership is
strict.

| Store | Owner | Others reach it via |
|---|---|---|
| Neo4j | `knowledge-service` | `knowledge-service` REST API, Kafka |
| Mongo `chatdb` | `chat-service` | `chat-service` REST API |
| Mongo `tokendb` | `storage-service` | `storage-service` REST API |
| Redis | `ai-service` | `ai-service` REST API |

### 5.2 Service contracts

The paths below are each service's **own** surface, reachable directly on its port and used for
service-to-service calls. They are not what the browser sees: the gateway exposes V1's legacy
`/api/v1/rag/**` paths and rewrites them onto these (section 5.3).

**`ai-service`** — Gemini facade. The only module holding the API key.

```
POST /api/v1/ai/embed         {text}           -> {embedding[3072]}
POST /api/v1/ai/embed/batch   {texts[]}        -> {embeddings[][]}
POST /api/v1/ai/generate      {input, config}  -> {text, thoughts?}
```

Redis-backed embedding cache (V1's `CachedAIService` logic) in front; Resilience4j circuit breaker
and retry behind. Cache misses fall through to source — a Redis outage degrades to slower, never
to broken, preserving V1's deliberate behaviour.

**`knowledge-service`** — sole Neo4j writer.

```
POST   /api/v1/chunks/search       {embedding[], topK}  -> ChunkResult[]
POST   /api/v1/chunks/batch        ChunkData[]          -> 202
DELETE /api/v1/documents/{source}                       -> {deletedCount}
GET    /api/v1/sources                                  -> string[]
POST   /api/v1/sources/chunks      {sources[], limit}   -> ChunkResult[]
GET    /api/v1/drive-files                              -> Map<fileId, modifiedTime>
PUT    /api/v1/drive-files/{id}    {source, modifiedTime}
```

Vector index lifecycle (`chunk_embeddings`, 3072-dim, cosine) runs as a startup
`ApplicationRunner`, replacing V1's `npm run init-db`. Access is through the Neo4j Java Driver with
raw Cypher — Spring Data Neo4j has no first-class support for `db.index.vector.queryNodes`.

**`retrieval-service`** — pure orchestration, owns no data.

```
POST /api/v1/retrieve   {query, topK, useAI}  -> {chunks[], strategy}
```

Ports V1's dual-search strategy: ask `ai-service` to split the query into specific and broad
variants, embed both, call `knowledge-service` twice in parallel, dedupe by chunk id, then ask
`ai-service` to re-rank before truncating to `topK`. Setting `useAI` to false collapses to a single
embed plus a single vector search — and that same path is the circuit-breaker fallback when
`ai-service` is unavailable.

**`chat-service`** — owns Mongo `chatdb`.

```
POST /api/v1/chat                       -> {answer, thoughts?, sources[]}
GET  /api/v1/chat/sessions
GET  /api/v1/chat/sessions/{id}
POST /api/v1/chat/sessions/{id}/pdf     -> application/pdf
POST /api/v1/chat/sessions/{id}/backup  -> uploads PDF via storage-service
```

Chat flow: retrieve context from `retrieval-service`, generate via `ai-service`, persist to Mongo.

**`storage-service`** — owns Mongo `tokendb`; sole holder of Google Drive OAuth.

```
GET  /api/v1/storage/auth              -> 302 to Google
GET  /api/v1/storage/auth/callback
GET  /api/v1/storage/auth/status
POST /api/v1/storage/sync
GET  /api/v1/storage/sync/status
GET  /api/v1/storage/sync/check
POST /api/v1/storage/sync/auto
POST /api/v1/storage/files             upload
GET  /api/v1/storage/files/{id}/content
POST /api/v1/storage/folders           findOrCreate
```

Carries V1's Bloom folder conventions (company folders, chat-backup folders) and the polling
auto-sync loop, which now publishes events instead of calling ingestion inline.

**Change detection crosses a service boundary.** V1 compares Drive state against file metadata
stored in Neo4j. Since `knowledge-service` owns Neo4j, `storage-service` obtains the known-file map
by calling `GET /api/v1/drive-files` on `knowledge-service`, then diffs Drive against it locally.
It never touches Neo4j directly.

**`proposal-service`** — proposals and event intake.

```
POST /api/v1/proposals/generate
POST /api/v1/proposals/save
POST /api/v1/proposals/download        -> application/pdf
GET  /api/v1/proposals/companies
POST /api/v1/event-intake
```

Event intake is folded in rather than given its own service: it is a single endpoint in the same
event-planning domain, and a one-endpoint service would be noise.

**`ingestion-service`** — Kafka-driven; minimal synchronous surface.

```
POST /api/v1/ingest   {source?}  -> 202 {jobId}
```

There are exactly two ways ingestion starts, and they must not be confused:

1. **Local directory** — `POST /api/v1/ingest` reads from the configured local path and processes
   files in-process. This is the successor to V1's `npm run ingest`.
2. **Drive-sourced** — `POST /api/v1/storage/sync` on `storage-service` (or its polling auto-sync
   loop) detects changes and publishes one `DocumentDiscovered` per changed file.
   `ingestion-service` consumes those. `POST /api/v1/ingest` never enumerates Drive.

### 5.3 Gateway routing and legacy compatibility

The gateway preserves V1's public paths as route rewrites, so the SPA needs no fetch-URL changes
on day one:

- `/api/v1/rag/chat/**` to `chat-service`
- `/api/v1/rag/retrieve` to `retrieval-service`
- `/api/v1/rag/sync/**` to `storage-service`
- `/api/v1/rag/ingest` to `ingestion-service`
- `/api/v1/rag/proposals/**`, `/generate-proposal`, `/event-intake` to `proposal-service`

**The `GET /api/rag/auth/google/callback` 307 shim must be preserved.** That exact URI is
registered in Google Cloud Console; changing it requires an external change outside this project.

---

## 6. Event-Driven Pipeline

Topics and record types live in `bloom-events`.

```
storage-service            ingestion-service              knowledge-service
     │                            │                              │
     │  DocumentDiscovered        │                              │
     ├───────────────────────────▶│  1. download (Feign)         │
     │  {driveFileId, name,       │  2. PDFBox parse             │
     │   mimeType, modifiedTime,  │  3. Tess4J OCR fallback      │
     │   folderPath}              │  4. chunk                    │
     │                            │  5. ai-service embedBatch    │
     │                            │                              │
     │                            │  ChunkBatchReady (xN)        │
     │                            ├─────────────────────────────▶│  MERGE -> Neo4j
     │                            │  {source, driveFileId, seq,  │
     │                            │   isLast, chunks[<=20]}      │
     │      DocumentIndexed       │                              │
     │◀───────────────────────────┼──────────────────────────────┤
     │                            │                              │
     │  DocumentDeleted           │                              │
     ├────────────────────────────┼─────────────────────────────▶│  detach delete
```

**Batching is a hard requirement, not an optimization.** A 3072-dimension `float[]` serializes to
roughly 12 KB. A 200-chunk document would produce a 2.4 MB message against Kafka's 1 MB default
limit. Therefore:

- `ChunkBatchReady` carries at most **20 chunks** per message, with `seq` and `isLast` for
  completion detection.
- `compression.type=lz4`.
- `max.request.size` and `max.partition.fetch.bytes` raised to 5 MB as headroom.

**Idempotency.** `knowledge-service` writes with Cypher `MERGE` keyed on chunk id, so redelivery
and replay are safe.

**Failure handling.** Spring Kafka `DefaultErrorHandler` with exponential backoff, then
`DeadLetterPublishingRecoverer` to a `.DLT` topic.

**What this buys.** V1's `syncController.ts` is a 15 KB module that polls Drive, detects changes,
downloads, parses, chunks, embeds, and writes Neo4j inline. That single flow becomes three services
that each do one thing, connected by durable, replayable, observable events.

---

## 7. Gemini Client: Spring AI Core Modules

**Decision:** use `spring-ai-google-genai:2.0.1` and `spring-ai-google-genai-embedding:2.0.1` —
the **core modules only**, with manual `@Bean` wiring. Do not use the Spring AI starters.

**Why this works despite the version tension.** Spring AI 2.0.1's autoconfigure and starter modules
depend on Spring Boot 4.1.1, which conflicts with the Boot 4.0.8 baseline. The core modules do not:

```
spring-ai-google-genai:2.0.1
├── com.google.genai:google-genai:1.65.0
├── org.springframework.ai:spring-ai-model:2.0.1
├── org.springframework.ai:spring-ai-retry:2.0.1
└── org.springframework:spring-context-support:7.0.9
```

Zero Boot dependency, and Spring Framework 7.0.9 is what Boot 4.0.8 ships. The embedding module has
the identical shape. Skipping the starter costs `spring.ai.*` property autoconfiguration — roughly
twenty lines of `@Bean` configuration — and nothing else.

**Thoughts parity is preserved.** V1's `IAIService` returns `{text, thoughts}` and the frontend
parses `<think>` tags out of responses. Spring AI supports this end to end:
`GoogleGenAiChatOptions` exposes `includeThoughts`, `thinkingBudget`, and `thinkingLevel`; and
`GoogleGenAiChatModel` writes `isThought` into per-part message metadata, making thought text
separable from answer text.

**Rejected alternatives.** Raw `google-genai:1.68.0` — simplest, but skips a major Spring ecosystem
component, which cuts against the learning goal. Spring AI starters on Boot 4.1.1 — full
autoconfiguration, but forces Spring Cloud one minor off its supported baseline.

---

## 8. Full-Parity Library Mapping

All versions verified present on Maven Central on 2026-08-30.

| V1 (Node) | V2 (Java) | Service |
|---|---|---|
| `@google/generative-ai` | Spring AI 2.0.1 core modules (using `google-genai` 1.65.0) | ai |
| `pdf-parse`, `pdfjs-dist` | Apache PDFBox 3.0.8 (`PDFTextStripper`) | ingestion |
| `tesseract.js` + `eng.traineddata` | Tess4J 5.20.0 — reuses the **same** `eng.traineddata` file | ingestion |
| `pdfkit`, `pdf-lib` | PDFBox 3.0.8 content streams | chat, proposal |
| `arabic-persian-reshaper` + hand-rolled bidi | **ICU4J 78.3** — `ArabicShaping` and `Bidi` | chat, proposal |
| `googleapis` | `google-api-services-drive:v3-rev20260823-2.0.0`, `google-auth-library-oauth2-http` | storage |
| `neo4j-driver` | Neo4j Java Driver, raw Cypher | knowledge |
| `mongoose` | `spring-boot-starter-data-mongodb` | chat, storage |
| `ioredis` | `spring-boot-starter-data-redis` | ai |
| `zod` | Jakarta Bean Validation | all |
| `pino`, `pino-http` | Logback JSON encoder + Micrometer | all |
| hand-rolled DI container | Spring's container | all |
| `jest`, `supertest` | JUnit 5, MockMvc, Testcontainers | all |

### The Arabic PDF path improves

V1's `CLAUDE.md` warns that the segment-splitting logic in `generateChatPdf` is intentionally
hand-rolled and fragile because it handles script-switching mid-line. ICU4J treats this as a solved
problem:

- `ArabicShaping(ArabicShaping.LETTERS_SHAPE)` performs contextual glyph joining.
- `Bidi.setPara()` and `Bidi.writeReordered()` perform the Unicode Bidirectional Algorithm for
  mixed Arabic and English runs.

The Arabic fonts in V1's `src/server/assets/fonts/` move to `chat-service` resources and load via
`PDType0Font.load()`. This is the one place in the port where the Java version is a simplification
rather than a translation, and the hand-rolled segment splitting is retired rather than ported.

---

## 9. Cross-Cutting Concerns

### 9.1 Configuration

Spring Cloud Config Server with `spring.profiles.active=native`, backed by the `config-repo/`
directory rather than a git remote — simpler to run and demonstrate. Shared `application.yml` plus
per-service overrides.

**Secrets are `${ENV_VAR}` placeholders resolved from the environment and are never committed.**
This is the structural fix for a real defect found in V1: `.env.example` contains what appear to be
live credentials — a Gemini API key, a Neo4j password, a MongoDB Atlas URI with password, a Redis
URL with password, and `APP_AUTH_PASSWORD` — while `.gitignore` excludes only `.env`, not
`.env.example`. **Those credentials should be rotated regardless of this project.**

### 9.2 Authentication — Spring Security with JWT

An upgrade over V1's HTTP Basic gate, chosen deliberately for the portfolio value.

- The gateway exposes `POST /api/v1/auth/login`, validating credentials against configured values
  (the successor to `APP_AUTH_USER` and `APP_AUTH_PASSWORD`) and issuing an **RS256-signed JWT**.
- The gateway acts as a Spring Security resource server, validating the JWT on every other route
  and rejecting unauthenticated requests before they reach any service.
- The `Authorization` header propagates downstream. Each service is *also* configured as a resource
  server validating the same signature — defense in depth, and it lets services read claims rather
  than trusting a header.
- The private key lives only in the gateway; the public key is distributed through Config Server.
- The SPA stores the token and attaches it to fetch calls. This is the one change required in the
  frontend.

Google Drive OAuth is unrelated and unchanged: it remains entirely inside `storage-service`.

### 9.3 Resilience

Resilience4j circuit breakers on the two genuinely external boundaries: `ai-service` to Gemini, and
`storage-service` to Google Drive. Fallbacks are meaningful rather than rethrown:

- `retrieval-service` degrades `useAI: true` to a plain single-vector search (a path that already
  exists in V1 and is already tested).
- `chat-service` returns a ProblemDetail describing temporary unavailability.
- `ai-service` cache misses fall through to source; a Redis outage never fails a request.

### 9.4 Observability

Micrometer Tracing exporting to Zipkin. The reference trace is a single `/api/v1/chat` request
spanning gateway, chat, retrieval, ai, and knowledge.

Actuator replaces V1's hand-rolled `healthRoutes.ts`, with liveness and readiness groups. V1
encodes a deliberate judgment that must carry over: **cache availability is excluded from
readiness**, so a Redis blip cannot pull a service out of a load balancer for what is purely a
performance layer. In V2 this becomes explicit health-group configuration rather than a code
comment.

### 9.5 Error model

One RFC 9457 `ProblemDetail` model defined in `bloom-common`. This retires V1's two competing
`AppError` classes (`middleware/errorHandler.ts` and `errors/DomainErrors.ts`), which is a known
source of confusion documented in V1's own `CLAUDE.md`.

---

## 10. Frontend, Runtime, and Testing

### 10.1 Frontend

The React 19 SPA lifts into `frontend/` essentially unchanged. `vite-express` disappears — the SPA
is no longer served by a backend process.

- **Dev:** Vite dev server proxying `/api` to the gateway on port 8080.
- **Prod:** `npm run build`, with static assets served by an nginx container behind the gateway.
- **Required change:** JWT acquisition and attachment (section 9.2). Because the gateway preserves
  the `/api/v1/rag/**` paths, no fetch URL needs rewriting.

### 10.2 Running it

Compose profiles, so the full stack is never mandatory:

| Profile | Contains |
|---|---|
| `infra` | neo4j, mongo, redis, kafka, zipkin |
| `platform` | config-server, discovery-server, api-gateway |
| `apps` | the seven business services |

Developing one service means running `--profile infra --profile platform` and starting that service
from the IDE. The full stack is approximately 7–8 GB; each service container is capped at 512 MB
with `-XX:MaxRAMPercentage=75`.

### 10.3 Testing

- JUnit 5 and AssertJ.
- **Unit tests port nearly directly.** V1's `usecases/__tests__` are already written against mocked
  interfaces, which is exactly the shape Spring constructor injection wants.
- `@SpringBootTest` slice tests per service.
- Testcontainers for Neo4j, MongoDB, and Kafka integration tests.
- WireMock for Gemini and Google Drive.
- *Stretch:* Spring Cloud Contract between `retrieval-service` and `knowledge-service`.

---

## 11. Deliberate Trade-offs

Recorded so they are defensible rather than accidental.

| Decision | Production alternative | Why this way |
|---|---|---|
| Seven business services | Four (`document`, `chat`, `storage`, `planning`) | Demonstrates more patterns; boundaries still follow real seams |
| `retrieval-service` owns no data | Merge into `knowledge-service` | Separates orchestration from persistence; shows a pure-composition service |
| `ai-service` as a service | Shared library | Gives the external-API circuit breaker something to protect, and centralizes the cache and the API key |
| Shared DB infrastructure | Separate instance per service | Same logical guarantee at a third of the RAM |
| Config Server `native` backend | Git backend | One less moving part to demo |
| Big-bang rewrite | Strangler fig | No production traffic to protect, and full parity moves everything anyway |

---

## 12. Risks and Open Items

| Risk | Mitigation |
|---|---|
| **Leaked credentials in V1 `.env.example`** | Rotate the Gemini key, Neo4j password, Mongo Atlas password, Redis password, and app password. Independent of this project; do it first. |
| Boot downgrade 4.1.1 to 4.0.8 | Required by Spring Cloud 2025.1.3. Verify a clean build in Phase 0 before writing service code. |
| Tess4J native dependencies in Docker | The `ingestion-service` image must install `tesseract-ocr` and its native libraries. Verify in Phase 3, not at the end. |
| Kafka message size | Batching at 20 chunks plus lz4 (section 6). Validate with a large real PDF. |
| Embedding dimension coupling | The Neo4j index is 3072-dim, matching `gemini-embedding-001`. Changing the model requires an index migration — V1 has a `migrate-index` precedent. |
| Google OAuth redirect URI | Must remain byte-identical; the gateway shim is not optional. |
| Manual Spring AI wiring | No `spring.ai.*` autoconfiguration. Document the `@Bean` config clearly for future readers. |
| RAM footprint | Compose profiles (section 10.2) make partial runs the normal case. |

---

## 13. Phasing

Big-bang rewrite in V2, with **V1 kept running as the reference implementation to diff outputs
against**.

| Phase | Builds | Done when |
|---|---|---|
| **0 — Foundation** | Aggregator POM, `bloom-bom`, package rename, `bloom-common`, `bloom-events`, config-server, discovery-server, api-gateway, Compose `infra` | Gateway routes to a hello endpoint through Eureka; clean build on Boot 4.0.8 |
| **1 — Data spine** | `knowledge-service`, `ai-service` | Embed a query and get real chunks back **from the existing Neo4j data** |
| **2 — Read path** | `retrieval-service` | `/api/v1/retrieve` output matches V1's for the same query |
| **3 — Write path** | `ingestion-service`, `storage-service`, Kafka pipeline | A PDF dropped in Drive lands in Neo4j with no manual step |
| **4 — Product surfaces** | `chat-service` (including Arabic PDF), `proposal-service` (including event intake) | Full feature parity with V1 |
| **5 — Cutover and polish** | JWT auth, frontend rewiring, tracing, circuit breakers, actuator, diagrams | SPA runs entirely against the gateway; reference trace captured |

Phase 1 deliberately points at the **existing** Neo4j instance. That yields a real end-to-end
validation signal before any ingestion code exists, and de-risks the vector-search port — the
single most likely place for a silent behavioural difference.

### Scope note for implementation planning

This document is the architecture for the whole migration; it is deliberately **not** a single
implementation plan. Each phase is its own plan-and-build cycle. Implementation planning should
target **Phase 0 alone** first, and only revisit this spec for the next phase once Phase 0's exit
criterion is met. Writing one plan spanning all six phases would produce a document too coarse to
execute and too stale to trust by Phase 3.
