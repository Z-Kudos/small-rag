# rag-mcp-server

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

默认端口 `8080`，MCP 端点为 `http://localhost:8080/mcp`。

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
| `QDRANT_COLLECTION` | `rag_mcp_text` | 集合名，不存在时启动自动创建 |
| `QDRANT_USE_TLS` | `false` | Qdrant 是否启用 TLS |

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
export QDRANT_COLLECTION=rag_mcp_text_bge_m3
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

选择 **Streamable HTTP**，URL 填 `http://localhost:8080/mcp`。

### curl 调用

初始化并拿到 `Mcp-Session-Id`：

```bash
curl -i -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

后续请求带上响应头中的 `Mcp-Session-Id`：

```bash
SESSION=<上一步响应头中的值>

# 入库
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"rag_ingest","arguments":{"text":"Spring AI 提供 MCP Server 与 RAG 能力。","metadata":{"source":"demo"}}}}'

# 检索（结构化）
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_search","arguments":{"query":"Spring AI 提供什么能力？","topK":3}}}'

# 检索（增强上下文）
curl -s http://localhost:8080/mcp \
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
```

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
