# small-rag

[简体中文](README.md) | **English**

A **plain-text RAG retrieval** MCP service built on Spring AI: it only ingests and retrieves vectors and never calls a Chat / generation model. It exposes document management, `rag_search`, and `rag_query` over MCP (Streamable HTTP); the vector store is **Qdrant**. Input is standard Markdown / Obsidian-compatible Markdown in Chinese or English. `rag_query` returns augmented context, and the calling agent generates the answer.

![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.2.0--RC1-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-blue)
![License](https://img.shields.io/badge/license-MIT-green)

## Table of contents

- [Features](#features)
- [Version matrix](#version-matrix)
- [Documentation](#documentation)
- [Quick start (local)](#quick-start-local)
- [Container deployment (single container)](#container-deployment-single-container)
- [Distributed mode (optional)](#distributed-mode-optional)
- [Configuration](#configuration)
- [MCP tools](#mcp-tools)
- [Client integration](#client-integration)
- [Chunking strategies](#chunking-strategies)
- [Directory import (multi-directory Markdown)](#directory-import-multi-directory-markdown)
- [Testing](#testing)
- [Security](#security)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Plain-text RAG**: ingest, chunk, vector-search, and context assembly only; no Chat model.
- **4 chunking strategies**: `fixed` / `recursive` / `semantic` / `parent-child` (default), optimized for Markdown structure.
- **Parent-Child retrieval**: child chunks are matched, parent chunks provide context, and parents are stored separately to avoid payload amplification.
- **MMR diversity**: `MMR_LAMBDA` controls the relevance/diversity trade-off.
- **Async reindex tasks**: progress queries, cooperative cancellation, watchdog hard timeout, and resume after restart.
- **Multi-directory Markdown import**: fingerprint-based incremental sync, scheduled / watcher / MCP triggers, and delete propagation.
- **Two runtime modes**: single-container `all-in-one` (default) or `app-only` (distributed).
- **Multi-module**: `app` (MCP + RAG), `gateway` (Spring Cloud Gateway), and `config-server` (Spring Cloud Config Server), built together from the repository root.
- **Optional distributed mode**: Spring Cloud Gateway (Consul + LoadBalancer), Spring Cloud Config Server (native), Redis lease lock, and directory-scan leader election.
- **Circuit breaker and health checks**: fail fast when Embedding / Qdrant keeps failing; diagnose via `/actuator/health`.

## Version matrix

| Component | Version | Notes |
| --- | --- | --- |
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | GA not released (as of 2026-11) |
| Spring Cloud | 2026.0.0-RC1 | Matches Boot 4.2.0-RC1 |
| Spring AI | 2.0.2 | Maven Central stable |
| Qdrant | 1.x | gRPC port 6334 |
| MCP | Streamable HTTP / Stateless | Endpoint `/mcp`; Streamable for standalone, Stateless for distributed |

> Boot / Cloud resolve from `https://repo.spring.io/milestone`, already configured in `pom.xml`.

## Documentation

The documents below belong to the current version **v0.0.3** and include post-release Spring Cloud data-plane changes. The next version gets its own `docs/vX.Y.Z/` directory.

| Document | Contents |
| --- | --- |
| [ROADMAP.md](ROADMAP.md) | Version goals, milestones, and acceptance criteria |
| [docs/v0.0.3/plan.md](docs/v0.0.3/plan.md) | v0.0.3 standalone and cluster plan |
| [docs/v0.0.3/design/architecture.md](docs/v0.0.3/design/architecture.md) | Architecture, packages, key data flows |
| [docs/v0.0.3/design/limitations.md](docs/v0.0.3/design/limitations.md) | Known limitations and risks |
| [DEV-MEMORY.md](DEV-MEMORY.md) | Current development state and pitfalls |
| [AGENTS.md](AGENTS.md) | Version baseline, build commands, code and documentation rules |
| [docs/v0.0.3/adr/0001-spring-cloud-scope.md](docs/v0.0.3/adr/0001-spring-cloud-scope.md) | Spring Cloud component scope decision (superseded) |
| [docs/v0.0.3/adr/0002-spring-cloud-data-plane.md](docs/v0.0.3/adr/0002-spring-cloud-data-plane.md) | Spring Cloud Gateway / Config Server / CircuitBreaker decision |

> The design and planning documents are written in Chinese.

## Quick start (local)

Prerequisites: JDK 25, Maven 3.9+, Qdrant, and any OpenAI-compatible Embedding service (the example uses llama.cpp llama-server, the same runtime bundled in the container).

```bash
# Qdrant
docker run -d --name rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant

# llama.cpp llama-server with a local embedding model (Qwen3-Embedding-0.6B, 1024 dims)
mkdir -p models && curl -fL --retry 5 -C - -o models/qwen3-embedding-0.6b-q8_0.gguf \
  https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/master/Qwen3-Embedding-0.6B-Q8_0.gguf
llama-server --host 127.0.0.1 --port 11434 \
  -m models/qwen3-embedding-0.6b-q8_0.gguf \
  --embeddings --pooling last --no-webui

# Embedding (base-url must include /v1; local services ignore the key, but the client requires a non-empty one)
export EMBEDDING_API_KEY=local
export EMBEDDING_BASE_URL=http://localhost:11434/v1
export EMBEDDING_MODEL=qwen3-embedding-0.6b
export EMBEDDING_DIMENSION=1024

mvn -pl app spring-boot:run
```

MCP endpoint: `http://localhost:18080/mcp`.

> `EMBEDDING_DIMENSION=1024` must match the Qdrant collection dimension. If you previously created a collection with a 1536-dim model, set a new `QDRANT_COLLECTION` or delete the old collection before starting.

### Faster dependency downloads (Aliyun mirror)

`pom.xml` puts the Aliyun public repository first in both `<repositories>` and `<pluginRepositories>` (`aliyun-public`, which aggregates Maven Central). Spring RC / milestone artifacts fall back to `repo.spring.io/milestone`, so both local and container builds use a domestic mirror by default; no `~/.m2/settings.xml` changes are needed.

Container builds additionally use `docker/maven-settings.xml` (`mirrorOf=*,!spring-milestones`) to mirror all repositories to Aliyun:

```bash
docker build -t small-rag:0.0.3 .          # uses the settings automatically
```

To switch back to the official repositories or another mirror, edit the repository order / URLs in `pom.xml` or `docker/maven-settings.xml`. If your local `~/.m2/settings.xml` already contains `<mirrorOf>*</mirrorOf>`, it overrides the POM; in that case add `spring-milestones` to the mirror exclusions.

## Container deployment (single container)

The image runs the application, Qdrant, and llama.cpp (llama-server for Embedding inference) together, managed by `supervisord`. **Only `18080` is exposed**; other services bind to loopback. All data lives under `/data` (Qdrant vectors and reindex task state + Embedding model + document archive), so mount a volume.

> The default is `SMALL_RAG_RUNTIME=all-in-one`. Set it to `app-only` to run only the application, with Qdrant / Redis / Consul / Embedding provided externally (distributed mode, see below).

```bash
# Docker
docker compose up -d --build

# Podman
podman compose up -d --build   # or podman-compose up -d --build
```

On first start, `start-llama.sh` downloads the default model from ModelScope (about 640 MB, resumable). Once loaded, the log shows `embedding model ready` and the service listens on `18080`. To pre-download: `make pull-model`.

Build / run manually:

```bash
docker build -t small-rag:0.0.3 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:0.0.3
```

You can override `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE` via `.env` for domestic mirrors. The llama.cpp source tag is `LLAMA_CPP_TAG` (default `v0.9.0`; llama-server is compiled from source inside the image, so the build and runtime bases match and there is no glibc incompatibility). Maven dependencies use the Aliyun mirror (`docker/maven-settings.xml`) and apt uses the Tsinghua mirror. For bind mounts, make sure container UID `10001` can write: `sudo chown -R 10001:10001 /path/to/data`.

### Mounting host Markdown directories

`SMALL_RAG_SOURCE_DIRS` uses **container paths**, so host directories must first be mounted with `-v` / Compose `volumes`. Mounting under `/data/sources/...` is recommended:

```bash
# Multiple separate host directories: one -v per directory (read-only)
docker run -d --name small-rag -p 18080:18080 \
  -v small_rag_data:/data \
  -v /host/notes:/data/sources/notes:ro \
  -v /host/wiki:/data/sources/wiki:ro \
  -e SMALL_RAG_SOURCES_ENABLED=true \
  -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes,wiki=/data/sources/wiki' \
  small-rag:0.0.3
```

```bash
# Or mount one host root and import its subdirectories
docker run -d --name small-rag -p 18080:18080 \
  -v small_rag_data:/data \
  -v /host/knowledge:/data/sources:ro \
  -e SMALL_RAG_SOURCES_ENABLED=true \
  -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes,wiki=/data/sources/wiki' \
  small-rag:0.0.3
```

Compose users can use the optional `compose.sources.yaml`:

```bash
# Mounts .env SMALL_RAG_SOURCE_HOST (default ./sources) to /data/sources
SMALL_RAG_SOURCE_HOST=/host/knowledge \
  docker compose -f compose.yaml -f compose.sources.yaml up -d --build

# Or write it to .env first, then:
make up-sources
```

`compose.sources.yaml` enables `SMALL_RAG_SOURCES_ENABLED=true` by default and imports `/data/sources/notes` and `/data/sources/wiki`. If your directories are elsewhere, change `SMALL_RAG_SOURCE_DIRS` in `.env` (using `label=container-path`) or edit `compose.sources.yaml`.

Notes:

- Host directories must be readable by container UID `10001`; use `:ro` for read-only mounts since the app only reads source files. If permissions are insufficient: `sudo chmod -R a+rX /host/notes` or `sudo chown -R 10001:10001 /host/notes`.
- `SMALL_RAG_SOURCE_DIRS` is **not** a host path; it is a container path. Host paths only appear on the left side of `-v` / `volumes`.
- The label keeps document IDs stable: keep the label unchanged when moving a directory to reuse existing documents. Duplicate labels cause the scan to be rejected.

### Changing the model / external Embedding

- Change the in-container model: update `EMBEDDING_MODEL_URL` (GGUF download URL) and `EMBEDDING_DIMENSION`, and switch to a new `QDRANT_COLLECTION`; `EMBEDDING_MODEL` is only an API-side name and is not validated by llama-server.
- External Embedding: set `SMALL_RAG_ENSURE_EMBEDDING_MODEL=false` and configure `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`.

## Distributed mode (optional)

The default is still single-container standalone. For multiple replicas, use the overlay: Qdrant / Redis / Consul / Embedding and Config Server are deployed separately, the application runs in `app-only` mode, and Spring Cloud Gateway exposes `18080`.

```bash
docker compose -f compose.yaml -f compose.distributed.yaml build
docker compose -f compose.yaml -f compose.distributed.yaml up -d --scale small-rag=2
# or: make up-distributed
```

- Requires Docker Compose v2.24+ (the overlay uses `!reset []` to drop the application's own port mapping).
- Replicas share `small_rag_data:/data` as the archive volume; multi-host deployments need RWX storage such as NFS / CephFS.
- Set `SMALL_RAG_DISTRIBUTED_ENABLED=true` to enable the Redis lease lock; directory import, reindex tasks, and startup re-chunk are serialized by the `index-activity` lock, so only one replica writes at a time.
- Reindex tasks additionally hold a dedicated `reindex-task` lease with a monotonic fencing epoch; every write re-checks "lease still held + epoch still current", so a recovered old holder cannot keep writing.
- Any replica can query tasks: when a task is absent from local memory it is read from the Qdrant state collection (2s read-through cache by default). `rag_task_resume` / `rag_task_cancel` first try to acquire the task lease and take over on success (epoch increments); when the original holder is still renewing, the error returns the holder instance and taskId.
- MCP must be stateless across replicas: `SMALL_RAG_MCP_PROTOCOL=STATELESS` (the overlay sets it). Otherwise the session lives in one replica's memory and requests routed to another replica return 404. Standalone keeps `STREAMABLE`.
- The lease defaults to 30s with renewal every 10s. If the holder disappears, another replica takes over after the lease expires. A failed renewal immediately stops local writes (`IndexActivityGuard.isHeldBy=false`).
- Automatic directory scans (startup / scheduled / watcher) run only on the leader: replicas compete for the `sources-leader` lease, and another replica takes over if the leader disappears. Manual MCP `rag_sync_sources` is not leader-restricted and is still serialized by the index activity lock.
- With `CONSUL_ENABLED=true`, the application registers with Consul (`small-rag` service, health check `/actuator/health`); the gateway discovers replicas through Consul + Spring Cloud LoadBalancer. The overlay includes a single-node Consul server (three nodes are recommended in production).
- Configuration uses Spring Cloud Config Server (`config-server` module, native backend): `config-repo/` is mounted read-only at `/config`; set `CONFIG_SERVER_ENABLED=true` and `SPRING_CONFIG_IMPORT=optional:configserver:http://config-server:8888`. The `optional:` prefix allows startup when the config server is unavailable.
- The gateway uses Spring Cloud Gateway (WebFlux) to route `Path=/mcp` → `lb://small-rag`, with a 1h long-response timeout. HAProxy is no longer used.

### Migrating from standalone to distributed

1. Stop the single-container instance and keep `small_rag_data` (archive) and `/data/qdrant/storage` (vector data).
2. Start external Qdrant / Redis / Consul / Embedding / Config Server with the overlay; reuse `small_rag_data` directly, or switch to an RWX volume for multi-host deployments.
3. Copy vector data to the new `qdrant_data` volume, or clear it and run `rag_rebuild` / re-ingest; keep Qdrant versions consistent when copying.
4. Set `SMALL_RAG_DISTRIBUTED_ENABLED=true`, `SMALL_RAG_RUNTIME=app-only`, share `SMALL_RAG_ARCHIVE_DIR`, set `CONFIG_SERVER_ENABLED=true` and `SPRING_CONFIG_IMPORT=optional:configserver:http://config-server:8888`, and enable `CONSUL_ENABLED` as needed.
5. Verify through the gateway's `/mcp`; check `/actuator/health` to confirm that exactly one replica has `sourcesLeader=true` and which one holds `activeLock`.

### Capacity and failure handling

- **Redis failure**: the index activity lock and leader election fail; reindex / directory import stop with a readable error, while retrieval is unaffected. Election resumes automatically after recovery.
- **Embedding failure**: `rag_search` / `rag_query` fail fast via the circuit breaker; ingest / reindex fail on timeout and retry later without permanently occupying threads.
- **Qdrant failure**: retrieval is bounded by the gRPC deadline; reindex tasks may be marked `stalled`, and after the hard timeout write permission is revoked. Use `rag_task_resume` after recovery.
- **Replica loss**: the index activity lock is taken over after the lease (default 30s) expires; the directory-scan leader is taken over in the next election round.
- **Consul failure**: `CONSUL_CONFIG_FAIL_FAST=false` allows startup to continue; the service re-registers after discovery recovers.
- **Archive volume**: must be shared and writable; prefer RWX storage and include it in backups, otherwise replicas cannot auto re-chunk / resume.

### Circuit breaker and health checks

- `rag_search` / `rag_query` go through a Resilience4j circuit breaker: default window 20 calls, minimum 10 calls, opens at 50% failure rate, half-open after 30s. When Embedding or Qdrant keeps failing, it returns "knowledge base retrieval temporarily unavailable (circuit open)" without occupying request threads; ingest and reindex are unaffected.
- Tune with `RAG_QUERY_BREAKER_*` environment variables, or disable with `RAG_QUERY_BREAKER_ENABLED=false`.
- `GET /actuator/health` returns the instance id, whether distributed mode is on, the active index lock, the directory-scan leader, the reindex task (owner / epoch / heartbeat / state store), the archive volume (enabled / writable / dir / shared), circuit state, and active profiles, making it easy to see which replica is writing.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `EMBEDDING_API_KEY` | falls back to `OPENAI_API_KEY`, then `demo-key` | Embedding API key |
| `EMBEDDING_BASE_URL` | falls back to `OPENAI_BASE_URL`, then `https://api.openai.com/v1` | Must include `/v1` |
| `EMBEDDING_MODEL` | falls back to `OPENAI_EMBEDDING_MODEL`, then `text-embedding-3-small` | Embedding model |
| `EMBEDDING_DIMENSION` | falls back to `QDRANT_DIMENSION`, then `1536` | Must match model output |
| `EMBEDDING_CONNECT_TIMEOUT_MS` | `10000` | Embedding HTTP connect timeout |
| `EMBEDDING_READ_TIMEOUT_MS` | `120000` | Embedding per-request read/write/total timeout, preventing a hung service from blocking reindex threads forever |
| `REINDEX_STALL_TIMEOUT_SECONDS` | `1800` | Stall threshold: mark `stalled` and warn after this long without progress; `0` disables |
| `REINDEX_HARD_TIMEOUT_SECONDS` | `3600` | Hard timeout: revoke write permission, cancel in-flight Embedding calls, and interrupt the worker; `0` disables |
| `REINDEX_CHECK_INTERVAL_SECONDS` | `30` | Watchdog check interval |
| `REINDEX_JOIN_TIMEOUT_SECONDS` | `10` | Time to wait for the worker after a hard-timeout interrupt |
| `REINDEX_RECOVERY_GRACE_SECONDS` | `120` | Grace period (seconds) after revoking write permission before the task lock is force-released; effective value is at least 2× `QDRANT_REQUEST_TIMEOUT_MS` |
| `REINDEX_RESUME_ON_STARTUP` | `true` | Auto-resume unfinished reindex tasks after restart; `false` leaves them `interrupted` while holding the task lease |
| `CHUNK_STRATEGY` | `parent-child` | `fixed` / `recursive` / `semantic` / `parent-child` |
| `MMR_LAMBDA` | `0.7` | MMR diversity weight λ (0 < λ < 1 enables MMR; 1 = pure relevance) |
| `CHUNK_SIZE` | `800` | Target chunk size for fixed / recursive, and the semantic default cap |
| `CHUNK_OVERLAP` | `100` | Fixed / recursive chunk overlap |
| `SEMANTIC_SIMILARITY_THRESHOLD` | `0.75` | Split when similarity to the current centroid falls below this (ignored when `breakpoint-percentile > 0`) |
| `SEMANTIC_BREAKPOINT_PERCENTILE` | `0` | Breakpoint percentile (0–1): when > 0, use this percentile of adjacent-chunk similarities as the threshold. Calibrated on real technical docs with Qwen3-Embedding: 0.1–0.4 produce identical output and about 5% fewer fragments than the absolute threshold; start with `0.2` for technical docs |
| `SEMANTIC_MIN_CHUNK_CHARS` | `200` | Minimum target chunk size for semantic chunking |
| `SEMANTIC_MAX_CHUNK_CHARS` | `0` | `0` falls back to `CHUNK_SIZE` |
| `PARENT_CHUNK_SIZE` | `2000` | Parent-Child parent size |
| `PARENT_CHUNK_OVERLAP` | `0` | Parent overlap (children never cross parent boundaries; disabled by default to avoid duplicate hits) |
| `CHILD_CHUNK_SIZE` | `400` | Parent-Child child size (stored in the vector store) |
| `CHILD_CHUNK_OVERLAP` | `50` | Parent-Child child overlap |
| `CHUNK_AUTO_RECHUNK` | `true` | Detect chunking config changes at startup and re-chunk archived documents |
| `SMALL_RAG_ARCHIVE_DIR` | `./data/documents` (`/data/documents` in the image) | Original-text archive, the source for auto re-chunking |
| `SMALL_RAG_SOURCES_ENABLED` | `false` | Enable multi-directory Markdown import |
| `SMALL_RAG_SOURCE_DIRS` | empty | Comma-separated container source directories, supporting `label=/path`; the label defaults to the directory name and is the stable namespace for document IDs |
| `SMALL_RAG_SOURCE_HOST` | `./sources` | Used only by `compose.sources.yaml`: host directory mounted to `/data/sources` |
| `SMALL_RAG_SOURCE_EXTENSIONS` | `.md,.markdown` | Extensions to import (case-insensitive) |
| `SMALL_RAG_SOURCE_EXCLUDE` | `**/node_modules/**,**/target/**,**/build/**` | Exclusion globs relative to the source root |
| `SMALL_RAG_SOURCES_SKIP_HIDDEN` | `true` | Skip directories / files starting with `.` (`.git`, `.obsidian`, etc.) |
| `SMALL_RAG_SOURCES_FOLLOW_SYMLINKS` | `false` | Follow symbolic links |
| `SMALL_RAG_SOURCES_SCAN_ON_STARTUP` | `true` | Scan once in the background after the application is ready |
| `SMALL_RAG_SOURCES_SCAN_INTERVAL_SECONDS` | `300` | Scheduled scan interval; `0` disables scheduled scans |
| `SMALL_RAG_SOURCES_WATCH_ENABLED` | `true` | Enable the filesystem watcher (debounced) |
| `SMALL_RAG_SOURCES_WATCH_DEBOUNCE_SECONDS` | `5` | Watcher debounce (seconds) |
| `SMALL_RAG_SOURCES_MAX_FILE_BYTES` | `2097152` | Per-file size limit; `0` means unlimited |
| `SMALL_RAG_SOURCES_MAX_FILES_PER_SCAN` | `0` | Maximum changed documents processed per scan; `0` means unlimited (unchanged files do not count) |
| `SMALL_RAG_SOURCES_DELETE_MISSING` | `false` | Delete the corresponding index and archive when a source file is removed |
| `SMALL_RAG_SOURCES_DELETE_ORPHAN_ROOTS` | `false` | Delete documents under a label whose root was removed from the configuration |
| `SMALL_RAG_SOURCES_STATE_FILE` | empty (`<archive-dir>/.sources/state.json`) | Incremental state file path |
| `QDRANT_HOST` | `localhost` | Qdrant host |
| `QDRANT_PORT` | `6334` | gRPC port |
| `QDRANT_API_KEY` | empty | Qdrant authentication |
| `QDRANT_COLLECTION` | `small_rag_text` | Collection name, created automatically at startup |
| `QDRANT_USE_TLS` | `false` | Enable TLS |
| `QDRANT_REQUEST_TIMEOUT_MS` | `30000` | Deadline for regular Qdrant RPCs (write / delete / search); separate from the init timeout |
| `SMALL_RAG_DISTRIBUTED_ENABLED` | `false` | Enable distributed mode (Redis lease lock); standalone does not connect to Redis |
| `SMALL_RAG_TASK_STATUS_CACHE_MS` | `2000` | Cross-replica task-status read-through cache (ms); minimum interval for re-reading Qdrant when a task is not local |
| `SMALL_RAG_ARCHIVE_SHARED` | `false` | Declare the archive directory as shared across replicas (should be `true` in distributed mode); used by startup warning and health |
| `SMALL_RAG_MCP_PROTOCOL` | `STREAMABLE` | MCP transport: `STREAMABLE` for standalone, `STATELESS` required for multiple replicas (otherwise cross-replica sessions return 404) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / empty | Redis connection (distributed only) |
| `SMALL_RAG_LOCK_LEASE` | `30s` | Index activity lock lease |
| `SMALL_RAG_LOCK_RENEW_INTERVAL` | `10s` | Renewal interval, must be clearly shorter than the lease |
| `SMALL_RAG_LOCK_PREFIX` | `smallrag:lock:` | Lock key prefix |
| `CONSUL_ENABLED` | `false` (app) / `true` (gateway) | Enable Consul service registration and discovery (service name `small-rag`) |
| `CONSUL_HOST` / `CONSUL_PORT` | `localhost` / `8500` | Consul agent address |
| `CONSUL_REGISTER` | `true` | Whether to register with Consul |
| `CONSUL_INSTANCE_ID` | `${spring.application.name}-${HOSTNAME}-${server.port}` | Unique instance id for multiple replicas |
| `CONSUL_CONFIG_FAIL_FAST` | `false` | Whether to block startup when Consul KV is unreachable |
| `CONFIG_SERVER_ENABLED` | `false` | Enable the Config Server client; the distributed overlay sets it |
| `CONFIG_SERVER_FAIL_FAST` | `false` | Whether to block startup when Config Server is unreachable |
| `CONFIG_SERVER_PORT` | `8888` | Config Server listen port |
| `CONFIG_SERVER_SEARCH_LOCATIONS` | `file:/config` | Native config repository path |
| `SPRING_CONFIG_IMPORT` | empty | e.g. `optional:configserver:http://config-server:8888` or `optional:consul:` |
| `GATEWAY_PORT` | `18080` | Gateway listen port |
| `GATEWAY_RESPONSE_TIMEOUT` | `1h` | Gateway long-response timeout for MCP |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `5000` | Gateway backend connect timeout (ms) |
| `RAG_QUERY_BREAKER_ENABLED` | `true` | Circuit breaker for `rag_search` / `rag_query` |
| `RAG_QUERY_BREAKER_FAILURE_RATE` | `50` | Failure rate (%) that opens the circuit |
| `RAG_QUERY_BREAKER_OPEN_WAIT` | `30s` | Wait time before half-open after opening |
| `SMALL_RAG_ENSURE_EMBEDDING_MODEL` | `true` (in the image) | Wait for the in-container Embedding service at startup (the model is downloaded by `start-llama.sh`) |
| `EMBEDDING_MODEL_URL` | Qwen3-Embedding-0.6B Q8_0 (ModelScope) | GGUF model download URL |
| `EMBEDDING_MODEL_PATH` | `/data/models/qwen3-embedding-0.6b-q8_0.gguf` | GGUF path (must be on a persistent volume) |
| `EMBEDDING_CTX_SIZE` | `8192` | llama-server context size |

> Chunk-size settings use **full-width character equivalents**: CJK characters count as 1 and Latin / half-width characters as 0.25, so Chinese and English have comparable semantic volume under the same settings. English hard splits fall back to word boundaries, and sentence detection includes an English abbreviation whitelist.

Image overrides: `EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`, `EMBEDDING_MODEL=qwen3-embedding-0.6b`, `EMBEDDING_DIMENSION=1024`, `QDRANT_HOST=127.0.0.1`.

⚠️ Changing the Embedding model requires updating the dimension and rebuilding the collection; startup fails if an existing collection's dimension does not match.

## MCP tools

| Tool | Purpose |
| --- | --- |
| `rag_ingest(text, metadata)` | Add a document: chunk it, write vectors to Qdrant, archive the original text; returns `documentId`, `chunkCount`, `parentCount` |
| `rag_update(documentId, text, metadata)` | Update an archived document; when `metadata` is omitted the existing metadata is kept; rebuilds the document's vectors immediately |
| `rag_delete(documentId)` | Delete all vectors and the archived original for a document |
| `rag_list(limit, offset)` | Paginate archived documents with id, metadata, chunking signature, and text length |
| `rag_reindex(documentId)` | Manual rebuild: with `documentId`, rebuild one document synchronously; without it, submit an async task to rebuild all archived documents and return `taskId` |
| `rag_rebuild(confirm)` | Full rebuild: clear fragments written by this service, then rebuild all archived documents asynchronously; requires `confirm=true`; returns `taskId` |
| `rag_task_status(taskId)` | Query reindex task progress; omit `taskId` for the latest task; returns `{"status":"IDLE"}` if none has run |
| `rag_task_resume(taskId)` | Resume a task left `interrupted` by a restart: skip completed documents and continue without clearing again; omit `taskId` for the latest task |
| `rag_task_cancel(taskId)` | Cancel a reindex task: cooperative while running (stops after the current document); an `interrupted` task is immediately set to `cancelled` and its lock is released |
| `rag_sync_sources(force)` | Trigger an async multi-directory Markdown scan: incremental by fingerprint, then chunk and index; `force=true` ignores fingerprints and re-chunks all files; returns immediately, poll with `rag_sync_status` |
| `rag_sync_status()` | Query directory import status: enabled / running / queued, last trigger, scan statistics, and errors |
| `rag_search(query, topK, expand)` | Retrieval only: returns matching chunks, similarity, and source metadata; in Parent-Child mode, results are deduplicated by parent chunk by default; `expand=false` returns raw child chunks |
| `rag_query(query, topK)` | Returns augmented context with numbering / source / similarity (does not call a generation model) |

- `rag_update`: updates the archive first, then chunks and replaces the old fragments; when `metadata` is omitted, the archived metadata is reused.
- `rag_reindex`: rebuilds only archived documents and does not clean fragments outside the archive; single-document rebuild still returns the fragment count synchronously.
- `rag_rebuild`: deletes fragments with `chunk_type=chunk` / `chunk_type=child` / `chunk_type=parent` first, then rebuilds everything; refuses to run when the archive is empty. After completion it removes parents without children and legacy fragments missing `chunk_type`.
- **Idempotent writes**: child point ids are deterministic (`chunk:<docId>:<chunk signature>:<index>`), so repeated upserts of the same document under the same chunking config overwrite the same points instead of creating duplicates. When the chunking config changes the signature changes and the "write new fragments → delete old fragments" flow still applies.
- `rag_reindex` (full) / `rag_rebuild` are **async tasks**: only one reindex task may run at a time; duplicate submissions return an error. Task snapshots (the latest 20 are kept) live in the separate Qdrant collection `<QDRANT_COLLECTION>_reindex_state` (same Qdrant / persistent volume as the vectors, not dependent on the application container filesystem; the collection does not affect retrieval, empty-KB probing, or `rag_rebuild` cleanup). **The authoritative task lock is implemented by `DistributedLock`**: an in-process lease in standalone, or the Redis `reindex-task` lease plus a monotonic epoch in distributed mode; the lock point in the state collection is only a diagnostic mirror (`ownerInstance` / `epoch` / `expiresAt`) and is not used for mutual exclusion. Progress is restored after a restart: unfinished tasks become `interrupted` and **re-acquire the task lock**; with the default `REINDEX_RESUME_ON_STARTUP=true` they resume automatically (skipping completed documents and not clearing again). When auto-resume is disabled, use `rag_task_resume` to continue manually or `rag_task_cancel` to abandon and release the lock. A graceful shutdown saves running tasks as `interrupted` and yields the lease, so a restart can resume immediately; after a crash, takeover waits for lease expiry (≤30s). Tasks whose write permission was revoked by a hard timeout are not resumed.
- `rag_task_status` returns `status` (`queued` / `clearing` / `reindexing` / `interrupted` / `completed` / `aborted` / `cancelled` / `failed`), `total`, `processed`, `percent`, `succeeded`, `failed`, `currentDocumentId`, `lastProgressAt`, `stalled`, `cancelRequested`, `startedAt`, `finishedAt`, `error`, `revoked`, `lockReleaseAt`, `ownerInstance`, `epoch`, `heartbeatAt`. When a task is absent locally it is read from the state collection, so any replica can see the owner and tasks submitted by other replicas.
- Watchdog: after `REINDEX_STALL_TIMEOUT_SECONDS` without progress it only marks `stalled` and warns, **keeping the task lock**; only after `REINDEX_HARD_TIMEOUT_SECONDS` does it **revoke write permission** (rejecting all subsequent Qdrant writes), cancel in-flight Embedding calls, and interrupt the worker.
- Lock release: if the worker exits within `REINDEX_JOIN_TIMEOUT_SECONDS`, the lock is released immediately; otherwise the task enters a `REINDEX_RECOVERY_GRACE_SECONDS` grace period, after which the task lock is force-released. Once revoked, the old task can no longer write, and the grace period only needs to cover requests in flight at the moment of revocation (bounded by `QDRANT_REQUEST_TIMEOUT_MS`), so **no restart is needed**; submitting a new task during this window returns "write permission revoked, releasing task lock".
- `rag_task_cancel`: cooperative while running; stops after the current document and ends with `status=cancelled`. If blocked in a call, the Embedding timeout, active cancellation, or the watchdog hard timeout provides the backstop. For an `interrupted` task it immediately sets `cancelled` and releases the task lock (abandoning the resume).
- Historical data without an archive cannot be listed / updated / reindexed; it can only be deleted by `docId` or re-ingested.
- If Qdrant data is cleared or the collection name changes, reindex progress and locks are lost with it: `ChunkingReindexRunner` rebuilds documents when the chunking signature changes, but after an interrupted full rebuild (`rag_rebuild`) under the same signature the remaining documents are not rebuilt automatically; run the rebuild again.

## Client integration

The server is a standard Streamable HTTP MCP server at `http://localhost:18080/mcp`.

**OpenCode** (`opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "small-rag": { "type": "remote", "url": "http://localhost:18080/mcp", "enabled": true }
  }
}
```

**Claude Code**: `claude mcp add --transport http small-rag http://localhost:18080/mcp`

| Client | Configuration |
| --- | --- |
| Cursor | `.cursor/mcp.json` → `{"mcpServers":{"small-rag":{"url":"http://localhost:18080/mcp"}}}` |
| VS Code / Copilot | `.vscode/mcp.json` → `{"servers":{"small-rag":{"type":"http","url":"http://localhost:18080/mcp"}}}` |
| Cline / Roo Code | `type: streamableHttp`, same `url` |
| Windsurf | `serverUrl` as above |
| Claude Desktop | stdio only: `npx -y mcp-remote http://localhost:18080/mcp` |
| Spring AI client | `spring-ai-starter-mcp-client` + `streamable-http` to `http://localhost:18080` |

Debugging: `npx @modelcontextprotocol/inspector`, choose Streamable HTTP, and enter the URL above.

## Chunking strategies

| Strategy | Value | Behavior | Best for |
| --- | --- | --- | --- |
| Fixed size | `fixed` | Sliding-window hard split by `chunk-size` / `chunk-overlap` | Unstructured long text |
| Recursive characters | `recursive` | Split by paragraph → newline → period → comma → space, hard-splitting only if still too long | Plain text, FAQ |
| Semantic | `semantic` | Markdown heading sections + merge/split text blocks by centroid similarity; code / tables are protected blocks; oversized text falls back to sentence-level splitting; trailing chunks below `min-chunk-chars` are reclaimed; `breakpoint-percentile` can replace the absolute threshold; one extra Embedding pass at ingest (per text block, cheaper than per sentence) | Clear topic shifts, willing to pay Embedding cost for quality |
| Parent-Child | `parent-child` (default) | Both parents and children are split by the Markdown engine; children are vectorized, and hits are deduplicated by `parent_id` to expand parent context; non-heading chunks are prefixed with `heading_path` | Precise recall + full context |

> `fixed` / `recursive` are not Markdown-aware; `semantic` and `parent-child` share the Markdown structure engine (the former standalone `markdown` strategy has been merged in). Deployments using `CHUNK_STRATEGY=markdown` should switch to `parent-child`.

```yaml
rag:
  chunking:
    strategy: parent-child      # fixed | recursive | semantic | parent-child
    markdown:
      heading-separator: " > "  # builds heading_path, e.g. "Guide > Install > Linux"
    recursive:
      separators: ["\n\n", "\n", "。", "！", "？", "；", "，", " "]
    semantic:
      similarity-threshold: 0.75   # cosine similarity threshold to the current centroid
      breakpoint-percentile: 0     # > 0 uses the percentile of adjacent-chunk similarities
      min-chunk-chars: 200
      max-chunk-chars: 0
    parent-child:
      parent-size: 2000
      parent-overlap: 0
      child-size: 400
      child-overlap: 50
```

Behavior of the Markdown-aware engine (shared by `semantic` and `parent-child`):

- Splits by `#` / Setext headings; `heading_path` records the full heading hierarchy, so search results show the section a fragment belongs to.
- Non-heading fragments are prefixed with `heading_path` so the embedding model sees the hierarchy; such fragments may exceed max-chars by the length of one heading path.
- Fenced code (``` / ~~~), indented code, tables, blockquotes, and Obsidian callouts are protected blocks and are not split mid-block at normal sizes; `#` comments inside code are not mistaken for headings.
- Oversized tables repeat the header row in every slice; oversized fenced code slices restore the fence (including the language tag); oversized math blocks restore `$$`.
- YAML frontmatter is parsed; `title` / `tags` / `aliases` / `description` go into `frontmatter_*` metadata and do not enter the body.
- Obsidian `[[wikilink]]` targets are extracted into `wikilinks` metadata.
- A block is only split when it exceeds the size limit: text uses recursive character splitting, code / tables split by line, marked `oversized_block=true`.
- Every fragment carries `content_format=markdown` and `markdown_block_types` (e.g. `heading,paragraph,code`).
- `rag_query` prefixes context with `heading=<heading path>` so the calling agent can cite the section.

Ingest metadata records `chunk_strategy` and `chunk_type`; Parent-Child children also carry `parent_id` and `parent_index`, and parents are stored as separate points (`chunk_type=parent`, id deterministically derived from `parent_id`) in the same collection. Different strategies produce different vector semantics, so **do not mix them in one collection**; archived documents are re-chunked automatically after a strategy change and restart, while unarchived historical data should use a new `QDRANT_COLLECTION` or be re-ingested.

### Auto re-chunk

With `rag.chunking.auto-rechunk` enabled (default `true`), startup will:

1. Compute the current chunking config signature (strategy, chunk size / overlap, separators, semantic threshold / percentile, Parent-Child sizes, heading separator);
2. Scan the original-text archive in `SMALL_RAG_ARCHIVE_DIR`;
3. For documents with a different signature, delete old fragments by `doc_id`, re-chunk with the current strategy, embed, and write to Qdrant;
4. Update the archive signature on success; a single failure only warns and retries on the next startup, while 3 consecutive failures abort the run.

After changing `CHUNK_STRATEGY` or chunking parameters, **restarting triggers an automatic re-chunk**. The archive is required: `/data/documents` in the container (inside the `small_rag_data` volume) or `./data/documents` locally (already in `.gitignore`). If the archive directory is not writable, ingest / retrieval still work but auto re-chunk is unavailable. Disable with `CHUNK_AUTO_RECHUNK=false`.

The signature also includes the target `QDRANT_COLLECTION`: switching to a new collection rebuilds archived documents into it at startup. If the new collection is an independent knowledge base, use a separate `SMALL_RAG_ARCHIVE_DIR` as well.

⚠️ This only applies to documents ingested after archiving was enabled. Historical data already in Qdrant without an archived original cannot be rebuilt; re-ingest it or switch to a new collection and rebuild from scratch.

## Directory import (multi-directory Markdown)

After configuring `rag.sources.directories`, the service scans Markdown files in one or more directories, parses / chunks / embeds them, and archives them automatically. Source changes are picked up incrementally; no manual `rag_ingest` is needed.

```yaml
rag:
  sources:
    enabled: true
    # The label is a stable namespace for document IDs; defaults to the directory name
    directories: notes=/data/sources/notes,wiki=/data/sources/wiki
    extensions: .md,.markdown
    exclude: "**/node_modules/**,**/target/**,**/build/**"
    skip-hidden: true
    scan-on-startup: true
    scan-interval-seconds: 300      # 0 = disable scheduled scans
    watch-enabled: true             # filesystem watcher (debounced)
    watch-debounce-seconds: 5
    max-file-bytes: 2097152         # per-file limit, 0 = unlimited
    max-files-per-scan: 0           # max changed documents per scan, 0 = unlimited
    delete-missing: false           # clean the index when a source file is deleted
    delete-orphan-roots: false      # clean documents under a label whose root was removed
```

Container example:

```yaml
services:
  small-rag:
    volumes:
      - /host/notes:/data/sources/notes:ro
      - /host/wiki:/data/sources/wiki:ro
    environment:
      SMALL_RAG_SOURCES_ENABLED: "true"
      SMALL_RAG_SOURCE_DIRS: notes=/data/sources/notes,wiki=/data/sources/wiki
```

You can also use the optional override from the repository (mounts `.env` `SMALL_RAG_SOURCE_HOST` to `/data/sources`):

```bash
make up-sources
# equivalent to:
docker compose -f compose.yaml -f compose.sources.yaml up -d --build
```

**Triggers**

| Trigger | Description |
| --- | --- |
| Startup scan | With `SMALL_RAG_SOURCES_SCAN_ON_STARTUP=true`, scan once in the background after the application is ready |
| Scheduled scan | Incremental scan every `SMALL_RAG_SOURCES_SCAN_INTERVAL_SECONDS` seconds; `0` disables |
| Filesystem watcher | With `SMALL_RAG_SOURCES_WATCH_ENABLED=true`, watch create / modify / delete and coalesce events into one incremental scan using the debounce interval |
| Manual trigger | MCP `rag_sync_sources(force)` runs asynchronously; `force=true` ignores fingerprints and re-chunks all files (and is not limited by `MAX_FILES_PER_SCAN`); poll with `rag_sync_status` |
| Limits | `MAX_FILE_BYTES` caps single-file size; `MAX_FILES_PER_SCAN` caps Embedding volume per scan |

**Incremental behavior and consistency**

- Document IDs are derived deterministically from "root label + relative path"; keep the label unchanged when moving a directory to reuse IDs. Duplicate labels cause the scan to be rejected; name them explicitly with `label=/path`.
- Compare `mtime + size` first, then content SHA-256; only content changes trigger re-chunking. Changing only mtime does not trigger a rebuild.
- Incremental state is written to `<archive-dir>/.sources/state.json` (atomic replace); if missing or corrupt, it is rebuilt from archive metadata. The worst case is one extra idempotent rebuild.
- When a source file is deleted, vectors and archive are removed only with `DELETE_MISSING=true`; deletion only applies to roots that are still configured and were scanned successfully in the current round, preventing accidental deletion when a mount is temporarily unreadable.
- Directory import and reindex tasks share the in-process index activity lock: imports are skipped / rejected while a rebuild runs, and new reindex tasks are rejected while an import runs.
- Imported document metadata includes `source`, `source_root`, `source_root_path`, `source_file`, `source_path`, `source_size`, `source_mtime`, `source_sha256`; `source` can be cited directly.
- The archive also maintains `<archive-dir>/.archive/index.json`; `rag_list` and startup re-chunk read only summaries and load originals on demand, avoiding loading all archive JSON into memory.

**Notes**

- The source file is authoritative: after `rag_update` on an imported document, the next scan keeps the manual update as long as the file is unchanged; once the file changes, the scan overwrites it with the file content.
- Manual `rag_delete` leaves a tombstone: the document is not re-imported while the source content is unchanged; after the content changes, the tombstone is invalidated and the document is re-imported. To delete permanently, remove the source file or the directory configuration.
- Root labels must be stable; changing a label creates new document IDs. `DELETE_MISSING=true` cleans disappeared files; `DELETE_ORPHAN_ROOTS=true` cleans all documents under labels removed from the configuration (disabled by default to avoid accidental deletion).
- UTF-8 text only; files above `MAX_FILE_BYTES` are skipped with a warning. Symlinks are not followed by default, and hidden directories / files are skipped.
- `rag_sync_sources` is asynchronous and returns immediately; use `rag_sync_status` to check `running/queued/lastRun`. The watcher is subject to platform inotify limits and falls back to startup / scheduled scans on failure.
- Bind-mounted source directories in the container must be readable by UID `10001` (`chmod -R a+rX /path/to/notes` or the matching owner).

## Testing

```bash
mvn test
```

Unit tests do not require Qdrant or model credentials (currently 176 app tests + 2 gateway tests + 1 config-server test); end-to-end tests require Qdrant and a configured Embedding service.

- Run `mvn test` at the repository root for all three modules; use `mvn -pl app test` for a single module.
- Container E2E (requires Docker / Podman): `mvn verify -Pcontainer-it`. Testcontainers starts a real Qdrant and covers "ingest → retrieve", idempotent repeated upserts, and orphan parent cleanup. The default image is `qdrant/qdrant:latest`; override with `-Dsmallrag.qdrant.image=...`.
- GitHub Actions runs two jobs: `mvn -B clean verify` for unit tests and `mvn -B verify -Pcontainer-it` for the container E2E.

- Offline evaluation: `RagPipelineEvaluationTest` uses deterministic hash vectors + `SimpleVectorStore` to run "ingest → chunk → retrieve → document-level metrics", covering recall@k / MRR / nDCG and MMR. It does not depend on external services, and the metrics do not represent real semantic model quality.
- Redis integration tests (skipped by default): `mvn test -Dtest='*RedisIT' -Dsmallrag.redis.it=true`, requires local Redis at `localhost:6390`.

## Security

> ⚠️ The MCP tools have no authentication. Anyone who can reach `/mcp` can modify or wipe the knowledge base, so **do not expose it directly to the public internet**. For cross-network access, add a reverse proxy with authentication. Retrieval results are untrusted data; callers must not execute instructions found in them.

## Contributing

- Branch model, build commands, code constraints, and documentation rules are in [AGENTS.md](AGENTS.md).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).
- Current development state and pitfalls are in [DEV-MEMORY.md](DEV-MEMORY.md).

## License

[MIT](LICENSE)
