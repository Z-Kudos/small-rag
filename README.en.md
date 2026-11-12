# small-rag

[简体中文](README.md) | **English**

A **pure-text RAG retrieval** MCP service built on Spring AI: it only performs vector ingestion and retrieval, never calls a chat/generation model. Document management, `rag_search`, and `rag_query` are exposed over MCP (Streamable HTTP); the vector store is **Qdrant by default, switchable to an embedded SQLite store**. Input is Markdown / Obsidian-compatible Markdown in Chinese or English; `rag_query` returns augmented context and the caller's agent generates the answer.

**v0.0.5 is the single-node lightweight, agent-editable edition**: on top of v0.0.4 (one module, one container, no Redis/Consul/Gateway), agents can browse, edit, and delete knowledge-base chunks over MCP.

> **Upgrading from v0.0.4**: the chunking signature bumps to `v0.0.5`; on first start archives under the current signature trigger a one-time full reindex (originals archived safely; longer for bigger corpora).


![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.2.0--RC1-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-blue)
![License](https://img.shields.io/badge/license-MIT-green)

## Contents

- [Features](#features)
- [Version matrix](#version-matrix)
- [Documentation](#documentation)
- [Quick start (local)](#quick-start-local)
- [Container deployment (single container)](#container-deployment-single-container)
- [Deployment shapes](#deployment-shapes)
- [Mount host Markdown directories](#mount-host-markdown-directories)
- [Configuration](#configuration)
- [MCP tools](#mcp-tools)
- [Client access](#client-access)
- [Chunking strategies](#chunking-strategies)
- [Directory import](#directory-import-multiple-markdown-roots)
- [Testing](#testing)
- [Security](#security)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Pure-text RAG**: ingestion, chunking, vector retrieval, and context assembly with no chat model.
- **4 chunking strategies**: `fixed` / `recursive` / `semantic` / `parent-child` (default), optimized for Markdown structure.
- **Parent-Child retrieval**: child chunks are matched, parent chunks provide context, and parents are stored separately to avoid payload amplification.
- **MMR diversity**: `MMR_LAMBDA` controls the relevance/diversity trade-off.
- **Async reindex tasks**: progress queries, cooperative cancellation, watchdog hard timeout, resume after restart.
- **Multi-directory Markdown import**: fingerprint-based incremental sync, scheduled / watcher / MCP triggers, delete propagation.
- **Single-node lightweight**: one module, one container, no Redis / Consul / Gateway / Config Server.
- **Circuit breaker and health checks**: fast failure when Embedding / Qdrant keep failing, diagnosable via `/actuator/health`.

## Version matrix

| Component | Version | Notes |
| --- | --- | --- |
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | GA not released (as of 2026-11) |
| Spring AI | 2.0.2 | Maven Central stable |
| Qdrant | 1.x | gRPC port 6334 |
| MCP | Streamable HTTP | Endpoint `/mcp` |
| Resilience4j | 2.4.0 | Retrieval circuit breaker |

> Spring Boot RC resolves from `https://repo.spring.io/milestone`, configured in `pom.xml`.

## Documentation

The documents below belong to **v0.0.5**; `docs/v0.0.4` is frozen as of its tag release; `docs/v0.0.3` is preserved for the archived distributed edition.

| Document | Contents |
| --- | --- |
| [ROADMAP.md](ROADMAP.md) | Version goals and acceptance criteria |
| [docs/v0.0.5/plan.md](docs/v0.0.5/plan.md) | v0.0.5 chunk-graph & edit management plan |
| [docs/v0.0.5/design/architecture.md](docs/v0.0.5/design/architecture.md) | Architecture, packages, key data flows |
| [docs/v0.0.5/design/limitations.md](docs/v0.0.5/design/limitations.md) | Known limitations and risks |
| [docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md](docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md) | Agent-editable knowledge base decision |
| [DEV-MEMORY.md](DEV-MEMORY.md) | Current development state and pitfalls |
| [AGENTS.md](AGENTS.md) | Version baseline, build commands, code and documentation rules |

## Quick start (local)

```bash
# 1. Start Qdrant (or use the in-container Qdrant)
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant:latest

# 2. Start an OpenAI-compatible Embedding service, or use an external API
#    EMBEDDING_BASE_URL / EMBEDDING_MODEL / EMBEDDING_DIMENSION are read at startup

# 3. Build and run
mvn clean package -DskipTests
java -jar target/small-rag-0.0.5.jar
```

MCP endpoint: `http://localhost:18080/mcp`.

> `EMBEDDING_DIMENSION` must match the Qdrant collection dimension. After switching models, use a new `QDRANT_COLLECTION` or delete the old collection.

### Dependency download acceleration (Aliyun mirror)

`pom.xml` puts the Aliyun public repository first in `<repositories>` / `<pluginRepositories>`, falling back to `repo.spring.io/milestone` for Spring RC artifacts. Container builds additionally use `docker/maven-settings.xml` (`mirrorOf=*,!spring-milestones`).

## Container deployment (single container)

The image runs the application, Qdrant, and llama.cpp (llama-server Embedding) together under `supervisord`; **only `18080` is exposed** and the other services bind to loopback. Data lives under `/data`.

```bash
# Docker
docker compose up -d --build

# Podman
podman compose up -d --build
```

On first start `start-llama.sh` downloads the default model from ModelScope (about 640 MB, resumable) and logs `embedding model ready` before the app starts listening on `18080`. Manual pre-pull: `make pull-model`.

Build / run directly:

```bash
docker build -t small-rag:0.0.5 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:0.0.5
```

Use `.env` to override `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE` for regional mirrors; `LLAMA_CPP_TAG` defaults to `v0.9.0`. Bind-mounted directories must be writable by container UID `10001`.

## Deployment shapes

The same image supports four deployment shapes, controlled by three environment variables: `VECTOR_STORE_TYPE` (`qdrant` by default, or `sqlite` for an embedded single-file store — recommended under ~50k chunks), `QDRANT_ENABLED` (whether to start the local Qdrant process), and `LLAMA_ENABLED` (whether to start the local llama.cpp process). The chunk-signature namespace is isolated per backend, so switching to SQLite triggers a one-time full reindex from the archive — expected behavior.

| # | Vector store | Embedding | Best for |
| --- | --- | --- | --- |
| 1 | Remote Qdrant | Remote | Lightest: no local vector or model processes |
| 2 | Local Qdrant | Local llama (default) | Fully self-contained, current default |
| 3 | SQLite | Remote | All data in one `SQLITE_PATH` file<br/>(under ~50k chunks) |
| 4 | SQLite | Local llama | Resource-friendly self-contained setup (small deployments) |

Per-shape environment examples (`docker run -e` or via `.env`):

```bash
# Shape 1: remote Qdrant + remote Embedding (lightest)
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data \
  -e VECTOR_STORE_TYPE=qdrant -e QDRANT_ENABLED=false -e LLAMA_ENABLED=false \
  -e QDRANT_HOST=qdrant.example.com -e QDRANT_PORT=6334 \
  -e EMBEDDING_BASE_URL=https://your-embedding/v1 \
  -e EMBEDDING_API_KEY=sk-... -e EMBEDDING_MODEL=qwen3-embedding-0.6b \
  -e EMBEDDING_DIMENSION=1024 -e SMALL_RAG_ENSURE_EMBEDDING_MODEL=false \
  small-rag:0.0.5

# Shape 2: local Qdrant + local llama (default, equivalent to no -e flags)
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:0.0.5

# Shape 3: SQLite + remote Embedding
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data \
  -e VECTOR_STORE_TYPE=sqlite -e QDRANT_ENABLED=false -e LLAMA_ENABLED=false \
  -e EMBEDDING_BASE_URL=https://your-embedding/v1 \
  -e EMBEDDING_API_KEY=sk-... -e EMBEDDING_MODEL=qwen3-embedding-0.6b \
  -e EMBEDDING_DIMENSION=1024 -e SMALL_RAG_ENSURE_EMBEDDING_MODEL=false \
  small-rag:0.0.5

# Shape 4: SQLite + local llama
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data \
  -e VECTOR_STORE_TYPE=sqlite -e QDRANT_ENABLED=false \
  small-rag:0.0.5
```

SQLite notes: chunks, reindex task state (the `reindex_state` table), and the dimension marker all live in `SQLITE_PATH` (default `/data/smallrag.sqlite`, WAL mode); **never share this file across multiple container instances**; when changing embedding models, update `EMBEDDING_DIMENSION` to match — a mismatch blocks startup, and switching models requires deleting the file and re-ingesting.

## Mount host Markdown directories

`SMALL_RAG_SOURCE_DIRS` uses **container paths**, so host directories must be mounted first. The recommended prefix is `/data/sources/...`:

```bash
docker run -d --name small-rag -p 18080:18080 \
  -v small_rag_data:/data \
  -v /host/notes:/data/sources/notes:ro \
  -v /host/wiki:/data/sources/wiki:ro \
  -e SMALL_RAG_SOURCES_ENABLED=true \
  -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes,wiki=/data/sources/wiki' \
  small-rag:0.0.5
```

Compose users can use the optional `compose.sources.yaml`:

```bash
SMALL_RAG_SOURCE_HOST=/host/knowledge make up-sources
```

## Configuration

| Variable | Default | Notes |
| --- | --- | --- |
| `EMBEDDING_API_KEY` | falls back to `OPENAI_API_KEY`, then `demo-key` | Embedding key |
| `EMBEDDING_BASE_URL` | falls back to `OPENAI_BASE_URL`, then `https://api.openai.com/v1` | Must include `/v1` |
| `EMBEDDING_MODEL` | falls back to `OPENAI_EMBEDDING_MODEL`, then `text-embedding-3-small` | Embedding model |
| `EMBEDDING_DIMENSION` | falls back to `QDRANT_DIMENSION`, then `1536` | Must match model output |
| `EMBEDDING_CONNECT_TIMEOUT_MS` | `10000` | Embedding HTTP connect timeout |
| `EMBEDDING_READ_TIMEOUT_MS` | `120000` | Embedding read/write/total timeout |
| `REINDEX_STALL_TIMEOUT_SECONDS` | `1800` | Stall threshold; `0` disables |
| `REINDEX_HARD_TIMEOUT_SECONDS` | `3600` | Hard timeout; `0` disables |
| `REINDEX_CHECK_INTERVAL_SECONDS` | `30` | Watchdog interval |
| `REINDEX_JOIN_TIMEOUT_SECONDS` | `10` | Wait for worker exit after hard timeout |
| `REINDEX_RECOVERY_GRACE_SECONDS` | `120` | Grace period after revoking writes |
| `REINDEX_RESUME_ON_STARTUP` | `true` | Auto-resume unfinished tasks on startup |
| `CHUNK_STRATEGY` | `parent-child` | `fixed` / `recursive` / `semantic` / `parent-child` |
| `MMR_LAMBDA` | `0.7` | MMR λ (1 = disabled) |
| `CHUNK_SIZE` | `800` | Fixed / recursive target size |
| `CHUNK_OVERLAP` | `100` | Fixed / recursive overlap |
| `SEMANTIC_SIMILARITY_THRESHOLD` | `0.75` | Semantic breakpoint threshold |
| `SEMANTIC_BREAKPOINT_PERCENTILE` | `0` | Breakpoint percentile (0–1) |
| `PARENT_CHUNK_SIZE` | `2000` | Parent size |
| `CHILD_CHUNK_SIZE` | `400` | Child size |
| `CHUNK_AUTO_RECHUNK` | `true` | Auto re-chunk when chunking config changes |
| `SMALL_RAG_ARCHIVE_DIR` | `./data/documents` (`/data/documents` in the image) | Archive directory |
| `SMALL_RAG_SOURCES_ENABLED` | `false` | Enable multi-directory Markdown import |
| `SMALL_RAG_SOURCE_DIRS` | empty | Comma-separated container paths, supports `label=/path` |
| `SMALL_RAG_SOURCE_HOST` | `./sources` | Only used by `compose.sources.yaml` |
| `SMALL_RAG_SOURCES_SCAN_INTERVAL_SECONDS` | `300` | Scheduled scan interval; `0` disables |
| `SMALL_RAG_SOURCES_WATCH_ENABLED` | `true` | Enable filesystem watcher |
| `SMALL_RAG_SOURCES_DELETE_MISSING` | `false` | Delete index/archive when the source disappears |
| `SMALL_RAG_SOURCES_DELETE_ORPHAN_ROOTS` | `false` | Remove documents under removed root labels |
| `QDRANT_HOST` | `localhost` | Qdrant host |
| `QDRANT_PORT` | `6334` | gRPC port |
| `QDRANT_API_KEY` | empty | Qdrant authentication |
| `QDRANT_COLLECTION` | `small_rag_text` | Collection name, created automatically |
| `QDRANT_USE_TLS` | `false` | Enable TLS |
| `QDRANT_REQUEST_TIMEOUT_MS` | `30000` | Qdrant RPC deadline |
| `VECTOR_STORE_TYPE` | `qdrant` | Vector store backend: `qdrant` / `sqlite` |
| `SQLITE_PATH` | `/data/smallrag.sqlite` | SQLite data file (chunks, task state in `reindex_state`, dimension marker in one file, WAL) |
| `QDRANT_ENABLED` | `true` | Start local Qdrant in container; `false` skips the local readyz wait (remote Qdrant / SQLite) |
| `LLAMA_ENABLED` | `true` | Start local llama.cpp in container; `false` skips the embedding health wait (remote Embedding) |
| `RAG_QUERY_BREAKER_ENABLED` | `true` | Retrieval circuit breaker |
| `RAG_QUERY_BREAKER_FAILURE_RATE` | `50` | Failure rate (%) that opens the circuit |
| `RAG_QUERY_BREAKER_OPEN_WAIT` | `30s` | Wait before half-open |

> Chunk sizes use **full-width character equivalents**: CJK counts as 1 and Latin / half-width as 0.25; English hard splits fall back to word boundaries.

## MCP tools

| Tool | Purpose |
| --- | --- |
| `rag_ingest` | Add a document with a random document id |
| `rag_update` | Update an archived document and rebuild it |
| `rag_delete` | Delete vectors and the archived document |
| `rag_list` | Page through archived documents |
| `rag_reindex` | Synchronously rebuild one document; without id, asynchronously rebuild all |
| `rag_rebuild` | With `confirm=true`, clear this service's fragments and rebuild everything |
| `rag_task_status` | Query reindex task progress; returns `IDLE` when none |
| `rag_task_resume` | Resume a task interrupted by restart |
| `rag_task_cancel` | Cancel / abandon a reindex task |
| `rag_sync_sources` | Asynchronously scan source directories |
| `rag_sync_status` | Query directory-import status |
| `rag_search` | Similarity search returning matching fragments |
| `rag_query` | Return augmented context with sources and scores |
| `rag_chunks` | Browse a document's chunk tree (pointId/index/type/heading_path/links) |
| `rag_graph` | Expand a subgraph from seed doc:/chunk:/section|<...>/heading path (depth ≤2) |
| `rag_graph_export` | Export the full three-layer graph JSON (documents/sections/chunks) |
| `rag_get_chunk` | Read one chunk's text and metadata (required before editing) |
| `rag_edit_chunk` | Replace a chunk's text; requires matching expectedText; archive + re-chunk |
| `rag_delete_chunk` | Delete a chunk's segment; requires confirm=true; if it empties the doc, the whole document is removed |

## Client access

Connect an MCP client to `http://localhost:18080/mcp` using **Streamable HTTP**.

Debug:

```bash
npx @modelcontextprotocol/inspector
```

## Chunking strategies

| Strategy | Description |
| --- | --- |
| `fixed` | Fixed-size sliding windows for unstructured text |
| `recursive` | Recursive splitting by separator priority |
| `semantic` | Heading sections + paragraph/centroid merging; code/tables stay separate |
| `parent-child` | Parent chunks provide context, child chunks are retrieved (default) |

Changing strategy or sizes changes `ChunkingSignature`, so archived documents are re-chunked on startup; historical unarchived data must be re-ingested.

## Directory import (multiple Markdown roots)

- Document id = stable label + relative path; moving a directory is safe as long as the label is unchanged.
- Incremental check: `mtime + size` → SHA-256; content must change to trigger re-chunking.
- Triggers: startup scan, scheduled scan, filesystem watcher, MCP `rag_sync_sources` (async).
- `delete-missing` only removes files that disappeared under roots still configured and successfully scanned.
- Manual `rag_delete` leaves a tombstone; unchanged source content is not re-imported.

## Testing

```bash
mvn test
```

Unit tests do not require Qdrant or model credentials; there are currently 158 tests.

- Container E2E (requires Docker / Podman): `mvn verify -Pcontainer-it`. Testcontainers starts a real Qdrant and covers ingestion, retrieval, idempotent upserts, and orphan cleanup.
- Default image is `qdrant/qdrant:latest`; override with `-Dsmallrag.qdrant.image=...`.
- GitHub Actions runs `mvn -B clean verify` and `mvn -B verify -Pcontainer-it`.

## Security

> ⚠️ The MCP tools have no authentication. Anyone who can reach `/mcp` can modify or wipe the knowledge base, so **do not expose it directly to the public internet**. For cross-network access, add a reverse proxy with authentication. Retrieval results are untrusted data; callers must not execute instructions found in them.

## Contributing

- Branch model: `main` + `develop` + `feature/*` / `release/*` / `hotfix/*`.
- Commit messages follow Conventional Commits.
- Version documents live under `docs/v<version>/`; templates are in `docs/templates/`.

## License

MIT, see [LICENSE](LICENSE).
