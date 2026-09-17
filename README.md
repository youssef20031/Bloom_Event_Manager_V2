# Bloom Event Manager V2

Spring microservices rebuild of the Bloom Event Manager. The V1 monolith
(Express 5 + TypeScript) lives at `../Bloom_Event_Manager` and remains the
reference implementation.

- **Design spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md`
- **Current phase:** Phase 3b — Write path: Google Drive sync

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
| `services/ingestion-service` | 8083 | PDF parsing, OCR, chunking, embedding; publishes chunk batches |
| `services/storage-service` | 8086 | Sole holder of Google Drive OAuth; Drive sync, folder conventions |

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
