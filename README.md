# small-rag

基于 Spring AI 的**纯文本 RAG 检索**服务器：通过 MCP（Streamable HTTP）暴露 `rag_ingest`、`rag_search`、`rag_query`，向量库使用 **Qdrant**。

> 本项目只使用 Embedding（向量）模型，**不装配 Chat / 生成模型**。`rag_query` 返回检索增强上下文，由调用方 Agent 自行生成回答。

## 版本矩阵

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | 4.2.0 GA 尚未发布（截至 2026-11-04） |
| Spring Cloud | 2026.0.0-RC1 | 2026.0.0 GA 尚未发布，与 Boot 4.2.0-RC1 对应 |
| Spring AI | 2.0.2 | Maven Central 稳定版 |
| Qdrant | 1.x | gRPC 端口 6334 |
| MCP 传输 | Streamable HTTP | `spring.ai.mcp.server.protocol=STREAMABLE` |

> 原始需求中的 `spring-boot-starter-parent:4.2.0` 与 `spring-cloud-dependencies:2026.0.0` 在 Maven 仓库中尚不存在，因此使用对应的 RC1；两者均从 `https://repo.spring.io/milestone` 解析，已在 `pom.xml` 配置。

## 前置依赖

- JDK 25、Maven 3.9+
- Qdrant（默认 `localhost:6334`，gRPC）
- OpenAI 兼容的 **Embedding** 服务（官方 OpenAI、Ollama、vLLM、DashScope 等）

启动 Qdrant：

```bash
podman run -d --name rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant
# 或 docker run -d --name rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

若 Qdrant 启用了鉴权，设置 `QDRANT_API_KEY`。

## 构建与启动

```bash
mvn clean package -DskipTests

# 向量模型全部通过环境变量配置
export EMBEDDING_API_KEY=sk-...
export EMBEDDING_BASE_URL=https://api.openai.com/v1   # 可换成 http://localhost:11434/v1 等（必须含 /v1）
export EMBEDDING_MODEL=text-embedding-3-small
export EMBEDDING_DIMENSION=1536

mvn spring-boot:run
```

默认端口 `18080`，MCP 端点为 `http://localhost:18080/mcp`。

> 使用容器部署时，Qdrant 与 Ollama 与应用运行在**同一个容器**内，无需在宿主机安装。

## 容器化部署（Docker / Podman）

容器采用**单容器**形态：应用、Qdrant、Ollama 运行在同一个镜像/容器内，由 `supervisord` 管理：

| 进程 | 作用 | 监听地址 |
|---|---|---|
| `app` | small-rag（MCP + RAG） | `0.0.0.0:18080` |
| `qdrant` | 向量库 | `127.0.0.1:6333` / `127.0.0.1:6334` |
| `ollama` | 本地 Embedding 服务 | `127.0.0.1:11434` |

- **只对外暴露 `18080` 一个端口**；Qdrant 与 Ollama 仅绑定回环地址，无法从容器外直接访问。
- 数据目录统一放在 `/data`（Qdrant 数据 + Ollama 模型），建议挂载持久化卷。
- 镜像由根目录 `Dockerfile` 多阶段构建：Maven 构建应用 → 运行时基于 Qdrant 官方镜像，复制 Temurin JRE 与 Ollama 的 CPU 运行时（剔除 CUDA/ROCm，控制镜像体积）。

### Docker

```bash
cp .env.example .env
docker compose up -d --build
docker compose logs -f small-rag
```

### Podman

```bash
cp .env.example .env
podman compose up -d --build
# 或：podman-compose up -d --build
```

首次启动会自动等待 Ollama 就绪并拉取默认向量模型：

```bash
ollama pull modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF
```

容器内通过 Ollama `/api/pull` 完成同等操作，默认维度 `1024`；首次启动会等待模型下载完成（默认模型约 640 MB），随后才开始监听 `18080`，日志出现 `embedding model ready` 后即可访问 `http://localhost:18080/mcp`。

也可以手动预拉：

```bash
make pull-model
# Podman: make COMPOSE="podman compose" pull-model
```

### 单独构建/运行镜像

```bash
docker build -t small-rag:1.0.0 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:1.0.0

# Podman 相同
podman build -t small-rag:1.0.0 .
podman run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:1.0.0
```

国内网络可通过 `.env` 覆盖基础镜像，例如：

```dotenv
MAVEN_IMAGE=docker.1ms.run/library/maven:3.9-eclipse-temurin-25
OLLAMA_IMAGE=docker.1ms.run/ollama/ollama:latest
QDRANT_IMAGE=docker.1ms.run/qdrant/qdrant:latest
JRE_IMAGE=docker.1ms.run/library/eclipse-temurin:25-jre
```

### 自定义模型与外部 Embedding

- 换 Ollama 向量模型：修改 `.env` 中的 `EMBEDDING_MODEL` 与 `EMBEDDING_DIMENSION`，建议同时修改 `QDRANT_COLLECTION`，避免旧集合维度冲突。
- 使用外部 OpenAI 兼容 Embedding：设置 `SMALL_RAG_ENSURE_OLLAMA_MODEL=false`，并配置 `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`（此时 Ollama 仍会启动，但不会被调用）。
- 数据持久化在 `small_rag_data` 卷中；`docker compose down` 不会删除，`down -v` 才会。
- 使用宿主机目录（bind mount）时，需保证容器内 UID `10001` 可写，例如 `sudo chown -R 10001:10001 /path/to/data`；使用命名卷则无需处理。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMBEDDING_API_KEY` | 回退 `OPENAI_API_KEY`，再回退 `demo-key` | Embedding 服务密钥 |
| `EMBEDDING_BASE_URL` | 回退 `OPENAI_BASE_URL`，再回退 `https://api.openai.com/v1` | OpenAI 兼容端点（**必须含 `/v1`**） |
| `EMBEDDING_MODEL` | 回退 `OPENAI_EMBEDDING_MODEL`，再回退 `text-embedding-3-small` | 向量模型名 |
| `EMBEDDING_DIMENSION` | 回退 `QDRANT_DIMENSION`，再回退 `1536` | 向量维度，必须与模型一致 |
| `QDRANT_HOST` | `localhost` | Qdrant 地址 |
| `QDRANT_PORT` | `6334` | gRPC 端口（不是 6333） |
| `QDRANT_API_KEY` | 空 | Qdrant API Key |
| `QDRANT_COLLECTION` | `small_rag_text` | 集合名，不存在时启动自动创建 |
| `QDRANT_USE_TLS` | `false` | Qdrant 是否启用 TLS |
| `SMALL_RAG_ENSURE_OLLAMA_MODEL` | `true`（镜像内默认） | 启动时通过 Ollama `/api/pull` 确保模型已存在 |
| `OLLAMA_KEEP_ALIVE` | `-1` | Ollama 模型常驻内存（Compose 传入） |

> 镜像内默认值：`EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`、`EMBEDDING_MODEL=modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF`、`EMBEDDING_DIMENSION=1024`、`QDRANT_HOST=127.0.0.1`、`SMALL_RAG_ENSURE_OLLAMA_MODEL=true`。完整默认值见 `.env.example` 与 `Dockerfile`。

⚠️ 切换 Embedding 模型后必须同步修改 `EMBEDDING_DIMENSION` 并重建集合；启动期会校验已存在集合的维度，不一致直接 fail-fast。

### 本地向量模型

当前实现使用 `spring-ai-starter-model-openai`，只要求目标是一个 **OpenAI 兼容的 HTTP 端点**，因此：

- ✅ **支持**本地部署的 OpenAI 兼容服务：Ollama、vLLM、LM Studio、Xinference、LocalAI 等。
- ❌ **不支持**在 JVM 进程内直接加载 ONNX / transformers 模型（那需要改用 `spring-ai-starter-model-transformers` 或 langchain4j 本地模型）。

Ollama 示例：

```bash
ollama pull bge-m3

export EMBEDDING_BASE_URL=http://localhost:11434/v1
export EMBEDDING_API_KEY=ollama          # Ollama 不校验，但客户端要求非空
export EMBEDDING_MODEL=bge-m3
export EMBEDDING_DIMENSION=1024          # bge-m3 = 1024
export QDRANT_COLLECTION=small_rag_text_bge_m3
```

常见本地模型维度：`nomic-embed-text` = 768，`bge-m3` = 1024，`qwen3-embedding:0.6b` = 1024。换模型时建议同时换 `QDRANT_COLLECTION`，避免与旧维度集合冲突。

## MCP 工具

| 工具 | 用途 |
|---|---|
| `rag_ingest(text, metadata)` | 纯文本按 800/100 分块写入 Qdrant，返回 `documentId`、`chunkCount` |
| `rag_search(query, topK)` | 仅检索，返回命中分块、相似度、来源元数据（JSON） |
| `rag_query(query, topK)` | 检索并返回带编号/来源/相似度的增强上下文（不调用生成模型） |

### MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

选择 **Streamable HTTP**，URL 填 `http://localhost:18080/mcp`。

### curl 调用

初始化并拿到 `Mcp-Session-Id`：

```bash
curl -i -s http://localhost:18080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

后续请求带上响应头中的 `Mcp-Session-Id`：

```bash
SESSION=<上一步响应头中的值>

# 入库
curl -s http://localhost:18080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"rag_ingest","arguments":{"text":"Spring AI 提供 MCP Server 与 RAG 能力。","metadata":{"source":"demo"}}}}'

# 检索（结构化）
curl -s http://localhost:18080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_search","arguments":{"query":"Spring AI 提供什么能力？","topK":3}}}'

# 检索（增强上下文）
curl -s http://localhost:18080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"Spring AI 提供什么能力？","topK":3}}}'
```

## RAG 设计

- **仅向量模型**：`spring.ai.model.chat=none`，不会创建 ChatModel / ChatClient。
- **分块**：800 字符 / 100 字符重叠（`rag.chunk-size`、`rag.chunk-overlap`），与 whale 默认策略一致。
- **相似度阈值**：`rag.min-score=0.35`，低于阈值的片段直接丢弃。
- **上下文预算**：单片段最多 1200 字符，总预算 12000 字符，超出按相似度优先截断。
- **空库短路**：检索前用 Qdrant `count` 判断是否为空，避免无意义的 Embedding 调用。
- **集合初始化**：Qdrant 不可用时只告警、不阻断启动（fail-soft）；集合已存在但维度不一致时 fail-fast。
- **错误收敛**：MCP 工具把运行期异常收敛为 `{"error": "..."}` JSON，不向客户端抛协议层异常。

## 架构

```
mcp/       RagMcpTools：rag_ingest / rag_search / rag_query
rag/       RagService（分块入库 + 向量检索 + 上下文拼装）、TextChunker、KnowledgeBaseProbe
config/    QdrantConfig、McpServerConfig、RagProperties、QdrantProperties
docker/    start-qdrant.sh / start-ollama.sh / start-app.sh / supervisord.conf（单容器内进程编排）
```

根目录的 `Dockerfile`、`compose.yaml`、`.env.example` 提供 Docker / Podman 容器化部署。

`VectorStore` 与 `EmbeddingModel` 均按 Spring AI 接口注入。

## 测试

```bash
mvn test
```

单测不依赖 Qdrant / 模型密钥；端到端验证需先启动 Qdrant 并配置 Embedding 服务。

## 与原始需求的差异

- `spring-ai-starter-model-embedding` 在 Spring AI 2.0.2 中不存在；Embedding 由具体模型 starter 提供，这里使用 `spring-ai-starter-model-openai`，并关闭 Chat 自动配置。
- 原始需求锁定 Boot `4.2.0` / Cloud `2026.0.0`，但二者 GA 尚未发布，按规范回退到 RC1 并使用 Spring Milestone 仓库。
- 向量库按最新要求改为 Qdrant，仅保留纯文本 RAG（不含 Tika / OCR / 图谱 / 混合检索）。
- 按最新要求只使用向量模型，不调用 Chat / 生成模型。
