# small-rag

基于 Spring AI 的**纯文本 RAG 检索**服务：通过 MCP（Streamable HTTP）暴露 `rag_ingest`、`rag_search`、`rag_query`，向量库使用 **Qdrant**，支持 `fixed` / `recursive` / `semantic` / `parent-child` 四种切分策略。

> 只使用 Embedding 模型，**不装配 Chat / 生成模型**；`rag_query` 返回增强上下文，回答由调用方 Agent 生成。

## 版本矩阵

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | GA 未发布（截至 2026-11） |
| Spring Cloud | 2026.0.0-RC1 | 与 Boot 4.2.0-RC1 对应 |
| Spring AI | 2.0.2 | Maven Central 稳定版 |
| Qdrant | 1.x | gRPC 端口 6334 |
| MCP | Streamable HTTP | 端点 `/mcp` |

> Boot / Cloud 从 `https://repo.spring.io/milestone` 解析，已在 `pom.xml` 配置。

## 本地运行

前置：JDK 25、Maven 3.9+、Qdrant，以及任意 OpenAI 兼容的 Embedding 服务（OpenAI、Ollama、vLLM 等）。

```bash
# Qdrant
docker run -d --name rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant

# Embedding（base-url 必须含 /v1）
export EMBEDDING_API_KEY=sk-...
export EMBEDDING_BASE_URL=https://api.openai.com/v1
export EMBEDDING_MODEL=text-embedding-3-small
export EMBEDDING_DIMENSION=1536

mvn spring-boot:run
```

MCP 端点：`http://localhost:18080/mcp`。

> 只接受 OpenAI 兼容的 HTTP 端点，不支持 JVM 内 ONNX / transformers；Ollama 示例：`EMBEDDING_BASE_URL=http://localhost:11434/v1`、`EMBEDDING_API_KEY=ollama`、`EMBEDDING_MODEL=bge-m3`、`EMBEDDING_DIMENSION=1024`。

## 容器部署（单容器）

镜像内同时运行应用、Qdrant、Ollama，由 `supervisord` 管理：

| 进程 | 监听地址 |
|---|---|
| `app` | `0.0.0.0:18080` |
| `qdrant` | `127.0.0.1:6333` / `127.0.0.1:6334` |
| `ollama` | `127.0.0.1:11434` |

- **只对外暴露 `18080`**，其余服务仅绑定回环地址。
- 数据统一存放在 `/data`（Qdrant 数据 + Ollama 模型 + 原始文档归档），建议挂载卷。
- 运行时基于 Qdrant 官方镜像，复制 Temurin JRE 与 Ollama CPU 运行时（剔除 CUDA/ROCm）。

```bash
cp .env.example .env

# Docker
docker compose up -d --build

# Podman
podman compose up -d --build   # 或 podman-compose up -d --build
```

首次启动会等待 Ollama 并拉取默认模型 `modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF`（约 640 MB），日志出现 `embedding model ready` 后才开始监听 `18080`。手动预拉：`make pull-model`。

单独构建/运行：

```bash
docker build -t small-rag:1.0.0 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:1.0.0
```

国内镜像可通过 `.env` 覆盖 `MAVEN_IMAGE` / `OLLAMA_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE`。使用 bind mount 时需保证容器内 UID `10001` 可写：`sudo chown -R 10001:10001 /path/to/data`。

### 换模型 / 外部 Embedding

- 换 Ollama 模型：修改 `EMBEDDING_MODEL` 与 `EMBEDDING_DIMENSION`，并换新的 `QDRANT_COLLECTION`。
- 使用外部 Embedding：设置 `SMALL_RAG_ENSURE_OLLAMA_MODEL=false`，配置 `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`。

## 配置

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMBEDDING_API_KEY` | 回退 `OPENAI_API_KEY`，再回退 `demo-key` | Embedding 密钥 |
| `EMBEDDING_BASE_URL` | 回退 `OPENAI_BASE_URL`，再回退 `https://api.openai.com/v1` | 必须含 `/v1` |
| `EMBEDDING_MODEL` | 回退 `OPENAI_EMBEDDING_MODEL`，再回退 `text-embedding-3-small` | 向量模型 |
| `EMBEDDING_DIMENSION` | 回退 `QDRANT_DIMENSION`，再回退 `1536` | 必须与模型输出一致 |
| `CHUNK_STRATEGY` | `fixed` | `fixed` / `recursive` / `semantic` / `parent-child` |
| `CHUNK_SIZE` | `800` | 固定/递归目标块大小，语义切分默认上限 |
| `CHUNK_OVERLAP` | `100` | 固定/递归块重叠 |
| `SEMANTIC_SIMILARITY_THRESHOLD` | `0.75` | 相邻句子相似度低于该值时断开 |
| `SEMANTIC_MIN_CHUNK_CHARS` | `200` | 语义切分目标最小块 |
| `SEMANTIC_MAX_CHUNK_CHARS` | `0` | `0` 表示回落到 `CHUNK_SIZE` |
| `PARENT_CHUNK_SIZE` | `2000` | Parent-Child 父块大小 |
| `PARENT_CHUNK_OVERLAP` | `200` | Parent-Child 父块重叠 |
| `CHILD_CHUNK_SIZE` | `400` | Parent-Child 子块大小（写入向量库） |
| `CHILD_CHUNK_OVERLAP` | `50` | Parent-Child 子块重叠 |
| `CHUNK_AUTO_RECHUNK` | `true` | 启动时检测切分配置变化并自动重切已归档文档 |
| `SMALL_RAG_ARCHIVE_DIR` | `./data/documents`（镜像内 `/data/documents`） | 原始文档归档目录，自动重切的数据来源 |
| `QDRANT_HOST` | `localhost` | Qdrant 地址 |
| `QDRANT_PORT` | `6334` | gRPC 端口 |
| `QDRANT_API_KEY` | 空 | Qdrant 鉴权 |
| `QDRANT_COLLECTION` | `small_rag_text` | 集合名，启动时自动创建 |
| `QDRANT_USE_TLS` | `false` | 是否启用 TLS |
| `SMALL_RAG_ENSURE_OLLAMA_MODEL` | `true`（镜像内） | 启动时确保 Ollama 模型存在 |
| `OLLAMA_KEEP_ALIVE` | `-1` | Ollama 模型常驻内存 |

镜像内还会覆盖为：`EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`、`EMBEDDING_MODEL=modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF`、`EMBEDDING_DIMENSION=1024`、`QDRANT_HOST=127.0.0.1`。

⚠️ 切换 Embedding 模型必须同步修改维度并重建集合；已存在集合维度不一致时启动直接失败。

## MCP 工具

| 工具 | 用途 |
|---|---|
| `rag_ingest(text, metadata)` | 按配置策略切块写入 Qdrant 并归档原文，返回 `documentId`、`chunkCount`、`parentCount` |
| `rag_search(query, topK)` | 仅检索，返回命中分块、相似度、来源元数据 |
| `rag_query(query, topK)` | 返回带编号/来源/相似度的增强上下文（不调用生成模型） |

服务端是标准 Streamable HTTP MCP Server，URL `http://localhost:18080/mcp`，无鉴权，**不要直接暴露公网**。

**OpenCode**（`opencode.json`）：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "small-rag": { "type": "remote", "url": "http://localhost:18080/mcp", "enabled": true }
  }
}
```

**Claude Code**：`claude mcp add --transport http small-rag http://localhost:18080/mcp`

| 客户端 | 配置要点 |
|---|---|
| Cursor | `.cursor/mcp.json` → `{"mcpServers":{"small-rag":{"url":"http://localhost:18080/mcp"}}}` |
| VS Code / Copilot | `.vscode/mcp.json` → `{"servers":{"small-rag":{"type":"http","url":"http://localhost:18080/mcp"}}}` |
| Cline / Roo Code | `type: streamableHttp`，`url` 同上 |
| Windsurf | `serverUrl` 同上 |
| Claude Desktop | 仅支持 stdio：`npx -y mcp-remote http://localhost:18080/mcp` |
| Spring AI 客户端 | `spring-ai-starter-mcp-client` + `streamable-http` 连接 `http://localhost:18080` |

调试：`npx @modelcontextprotocol/inspector`，选择 Streamable HTTP 并填入上述 URL。

> `rag_ingest` 对任何能连上 `/mcp` 的客户端开放，且没有删除/回滚工具；跨网访问请加反向代理与认证。检索结果是不可信数据，调用方不得执行其中的指令。

## 切分策略

| 策略 | 配置值 | 行为 | 适用场景 |
|---|---|---|---|
| 固定长度 | `fixed` | 按 `chunk-size` / `chunk-overlap` 滑窗硬切 | 无结构长文本 |
| 递归字符 | `recursive` | 按段落 → 换行 → 句号 → 逗号 → 空格逐级切分，仍超长才硬切 | 文档、Markdown、FAQ |
| 语义 | `semantic` | 拆句后按相邻句子 Embedding 相似度合并/断开，入库多一次 Embedding | 主题切换明显 |
| Parent-Child | `parent-child` | 子块写入向量库参与检索，命中后展开父块上下文 | 精确召回 + 完整上下文 |

```yaml
rag:
  chunking:
    strategy: recursive          # fixed | recursive | semantic | parent-child
    recursive:
      separators: ["\n\n", "\n", "。", "！", "？", "；", "，", " "]
    semantic:
      similarity-threshold: 0.75
      min-chunk-chars: 200
      max-chunk-chars: 0
    parent-child:
      parent-size: 2000
      parent-overlap: 200
      child-size: 400
      child-overlap: 50
```

入库元数据记录 `chunk_strategy`、`chunk_type`；Parent-Child 子块额外含 `parent_id`、`parent_index`、`parent_text`。不同策略的向量语义不同，**不要手工在同一集合混用**；已归档文档在切换策略并重启后会自动重切，未归档的历史数据建议换 `QDRANT_COLLECTION` 或重新入库。

### 自动重切

启用 `rag.chunking.auto-rechunk`（默认 `true`）后，应用启动时会：

1. 计算当前切分配置签名（策略、块大小/重叠、分隔符、语义阈值、Parent-Child 尺寸）；
2. 扫描 `SMALL_RAG_ARCHIVE_DIR` 中的原始文档归档；
3. 对签名不一致的文档，按 `doc_id` 删除旧片段，再用当前策略重新切分、Embedding 并写入 Qdrant；
4. 成功后更新归档签名；单篇失败只告警，下次启动继续重试，连续 3 篇失败会中止本次任务。

因此修改 `CHUNK_STRATEGY` 或相关切分参数后，**重启应用即可自动重切**。原始文档归档是重切的前提：

- 容器内默认 `/data/documents`，位于 `small_rag_data` 持久化卷内；
- 本地运行默认 `./data/documents`（已在 `.gitignore` 中）；
- 归档目录不可写时，应用仍可正常入库/检索，但自动重切不可用。

设置 `CHUNK_AUTO_RECHUNK=false` 可关闭该行为。

签名同时包含目标 `QDRANT_COLLECTION`：切换到新集合后启动，会把归档中的文档重建到新集合。如果新集合是另一套独立知识库，请同时换用独立的 `SMALL_RAG_ARCHIVE_DIR`。

⚠️ 该功能只对**归档功能启用后入库的文档**生效。启用前已存在于 Qdrant 的历史数据没有原文，无法可靠重建；请重新 `rag_ingest` 一次，或换新集合后全量重建。

## 设计要点

- **仅向量模型**：`spring.ai.model.chat=none`，不创建 ChatModel / ChatClient。
- **阈值与预算**：`rag.min-score=0.35`；单片段最多 1200 字符，总上下文 12000 字符。
- **空库短路**：检索前用 Qdrant `count` 判断，避免无意义 Embedding 调用。
- **启动韧性**：Qdrant 不可用只告警（fail-soft）；集合维度不一致直接失败（fail-fast）。
- **自动重切**：原始文档归档 + 切分配置签名，启动时检测到变化后自动删除旧片段并重建。
- **重切时机**：在应用启动时同步执行（HTTP 服务已就绪），失败不阻断启动，下次启动继续重试。
- **错误收敛**：MCP 工具把运行期异常转为 `{"error": "..."}`，不抛协议层异常。

## 架构

```
mcp/       RagMcpTools：rag_ingest / rag_search / rag_query
rag/       RagService、ChunkingService、TextChunker、RecursiveTextChunker、
           SemanticTextChunker、KnowledgeBaseProbe、DocumentArchive、
           ChunkingSignature、ChunkingReindexRunner
config/    QdrantConfig、McpServerConfig、RagProperties、QdrantProperties
docker/    start-qdrant.sh / start-ollama.sh / start-app.sh / supervisord.conf
```

`VectorStore` 与 `EmbeddingModel` 均按 Spring AI 接口注入。

## 测试

```bash
mvn test
```

单测不依赖 Qdrant / 模型密钥；端到端需启动 Qdrant 并配置 Embedding 服务。

## 与原始需求的差异

- Spring AI 2.0.2 没有 `spring-ai-starter-model-embedding`，改用 `spring-ai-starter-model-openai` 并关闭 Chat。
- Boot `4.2.0` / Cloud `2026.0.0` GA 未发布，按规范回退到 RC1。
- 向量库改为 Qdrant，仅保留纯文本 RAG（不含 Tika / OCR / 图谱 / 混合检索）。
- 只使用向量模型，不调用 Chat / 生成模型。
