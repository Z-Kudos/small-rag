# rag-mcp-server

基于 Spring AI 的 RAG 知识库 MCP 服务器：通过 MCP（Streamable HTTP）向其他 Agent 暴露 `rag_query` 与 `rag_ingest` 两个工具。

## 版本矩阵

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | 4.2.0 GA 尚未发布（截至 2026-11-04） |
| Spring Cloud | 2026.0.0-RC1 | 2026.0.0 GA 尚未发布，与 Boot 4.2.0-RC1 对应 |
| Spring AI | 2.0.2 | Maven Central 稳定版 |
| MCP 传输 | Streamable HTTP | `spring.ai.mcp.server.protocol=STREAMABLE` |

> 原始需求中的 `spring-boot-starter-parent:4.2.0` 与 `spring-cloud-dependencies:2026.0.0` 在 Maven 仓库中尚不存在，因此使用对应的 RC1；两个版本均从
> `https://repo.spring.io/milestone` 解析，已在 `pom.xml` 中配置。

## 构建与启动

```bash
# 构建（跳过测试）
mvn clean package -DskipTests

# 启动
mvn spring-boot:run
```

默认端口 `8080`，MCP 端点为 `http://localhost:8080/mcp`。

配置真实的模型密钥后，检索与生成才会真正调用模型：

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_CHAT_MODEL=gpt-4o-mini            # 可选
export OPENAI_EMBEDDING_MODEL=text-embedding-3-small  # 可选
mvn spring-boot:run
```

未配置密钥时服务仍可启动；此时对空知识库调用 `rag_query` 会返回“未检索到相关内容”，不会抛出异常。

## MCP 使用

### MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

在界面中选择 **Streamable HTTP**，URL 填 `http://localhost:8080/mcp`，连接后即可看到 `rag_query`、`rag_ingest`。

### curl 调用

先初始化并拿到 `Mcp-Session-Id`（响应头）：

```bash
curl -i -s http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

后续请求带上响应头中的 `Mcp-Session-Id`：

```bash
SESSION=<上一步响应头中的值>

# 列出工具
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 入库
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_ingest","arguments":{"text":"Spring AI 提供 MCP Server 与 RAG 能力。","metadata":{"source":"demo"}}}}'

# 查询
curl -s http://localhost:8080/mcp \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"Spring AI 提供什么能力？","topK":3}}}'
```

> 入库与检索会调用 Embedding 模型，需要有效的 `OPENAI_API_KEY`。

## 架构

```
mcp/       RagMcpTools：@McpTool 暴露 rag_query / rag_ingest
rag/       RagService：检索 -> 拼装 Prompt -> ChatClient 生成
store/     InMemoryVectorStore：默认进程内 VectorStore 实现
config/    VectorStore / ChatClient / MCP Server / RagProperties 配置
```

- `VectorStore` 与 `EmbeddingModel` 均按 Spring AI 接口注入，可替换实现。
- 默认使用进程内向量存储，保证 `mvn spring-boot:run` 无需 Redis / Milvus。

### 切换到 Redis 向量存储

1. 删除 `application.yml` 中的 `spring.autoconfigure.exclude`（当前排除了 `RedisVectorStoreAutoConfiguration`）。
2. 设置 `rag.vector-store=redis`。
3. 配置连接：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
  ai:
    vectorstore:
      redis:
        index: rag-index
        prefix: "rag:"
        initialize-schema: true
```

Redis 需启用 RediSearch 模块（Redis Stack 或 Redis 8+）。

## 测试

```bash
mvn test
```

## 与原始需求的差异

- `spring-ai-starter-model-embedding` 在 Spring AI 2.0.2 中不存在；Embedding 由具体模型 starter 提供，这里使用 `spring-ai-starter-model-openai`（同时提供 ChatModel）。
- 原始需求锁定 Boot `4.2.0` / Cloud `2026.0.0`，但二者 GA 尚未发布，按规范回退到 RC1 并使用 Spring Milestone 仓库。
