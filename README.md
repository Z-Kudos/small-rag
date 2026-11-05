# small-rag

基于 Spring AI 的**纯文本 RAG 检索** MCP 服务（仅向量入库与检索，不做 Chat 问答）。通过 MCP（Streamable HTTP）暴露文档管理、`rag_search`、`rag_query`；向量库 **Qdrant**。输入为标准 Markdown / Obsidian 兼容 Markdown 的中英文纯文本；`rag_query` 返回增强上下文，回答由调用方 Agent 生成。

## 版本矩阵

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | GA 未发布（截至 2026-11） |
| Spring Cloud | 2026.0.0-RC1 | 与 Boot 4.2.0-RC1 对应 |
| Spring AI | 2.0.2 | Maven Central 稳定版 |
| Qdrant | 1.x | gRPC 端口 6334 |
| MCP | Streamable HTTP | 端点 `/mcp` |

> Boot / Cloud 从 `https://repo.spring.io/milestone` 解析，已在 `pom.xml` 配置。

## 本地运行

前置：JDK 25、Maven 3.9+、Qdrant、任意 OpenAI 兼容 Embedding 服务（本机开发示例用 llama.cpp llama-server，与容器内置的运行时一致）。

```bash
# Qdrant
docker run -d --name rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant

# llama.cpp llama-server 加载本地向量模型（Qwen3-Embedding-0.6B，1024 维）
mkdir -p models && curl -fL --retry 5 -C - -o models/qwen3-embedding-0.6b-q8_0.gguf \
  https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/master/Qwen3-Embedding-0.6B-Q8_0.gguf
llama-server --host 127.0.0.1 --port 11434 \
  -m models/qwen3-embedding-0.6b-q8_0.gguf \
  --embeddings --pooling last --no-webui

# Embedding（base-url 必须含 /v1；本地服务不校验 key，但客户端要求非空）
export EMBEDDING_API_KEY=local
export EMBEDDING_BASE_URL=http://localhost:11434/v1
export EMBEDDING_MODEL=qwen3-embedding-0.6b
export EMBEDDING_DIMENSION=1024

mvn spring-boot:run
```

MCP 端点：`http://localhost:18080/mcp`。

> `EMBEDDING_DIMENSION=1024` 必须与 Qdrant 集合维度一致。之前用 1536 维模型创建过集合的，请设置新的 `QDRANT_COLLECTION` 或删除旧集合后再启动。

## 容器部署（单容器）

镜像内同时运行应用、Qdrant、llama.cpp（llama-server Embedding 推理），由 `supervisord` 管理；**只对外暴露 `18080`**，其余服务绑定回环地址。数据统一在 `/data`（Qdrant + Embedding 模型 + 文档归档），建议挂载卷。

```bash
# Docker
docker compose up -d --build

# Podman
podman compose up -d --build   # 或 podman-compose up -d --build
```

首次启动由 `start-llama.sh` 从 ModelScope 下载默认模型（约 640 MB，支持断点续传），加载就绪后日志出现 `embedding model ready` 并开始监听 `18080`。手动预拉：`make pull-model`。

单独构建/运行：

```bash
docker build -t small-rag:0.0.2 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:0.0.2
```

国内镜像可通过 `.env` 覆盖 `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE`，llama.cpp 源码版本用 `LLAMA_CPP_TAG`（默认 `v0.9.0`，镜像内从源码编译 llama-server，与运行时同基底无 glibc 兼容问题）。bind mount 时需保证容器内 UID `10001` 可写：`sudo chown -R 10001:10001 /path/to/data`。

### 换模型 / 外部 Embedding

- 换容器内模型：修改 `EMBEDDING_MODEL_URL`（GGUF 下载地址）与 `EMBEDDING_DIMENSION`，并换新的 `QDRANT_COLLECTION`；`EMBEDDING_MODEL` 只是 API 侧名称，llama-server 不校验。
- 外部 Embedding：`SMALL_RAG_ENSURE_EMBEDDING_MODEL=false`，配置 `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`。

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `EMBEDDING_API_KEY` | 回退 `OPENAI_API_KEY`，再回退 `demo-key` | Embedding 密钥 |
| `EMBEDDING_BASE_URL` | 回退 `OPENAI_BASE_URL`，再回退 `https://api.openai.com/v1` | 必须含 `/v1` |
| `EMBEDDING_MODEL` | 回退 `OPENAI_EMBEDDING_MODEL`，再回退 `text-embedding-3-small` | 向量模型 |
| `EMBEDDING_DIMENSION` | 回退 `QDRANT_DIMENSION`，再回退 `1536` | 必须与模型输出一致 |
| `CHUNK_STRATEGY` | `parent-child` | `fixed` / `recursive` / `semantic` / `parent-child` |
| `MMR_LAMBDA` | `0.7` | 检索 MMR 多样性权重 λ（0 < λ < 1 启用，1 = 纯相关排序） |
| `CHUNK_SIZE` | `800` | 固定/递归目标块大小，语义切分默认上限 |
| `CHUNK_OVERLAP` | `100` | 固定/递归块重叠 |
| `SEMANTIC_SIMILARITY_THRESHOLD` | `0.75` | 文本块与当前块质心的相似度低于该值时断开（`breakpoint-percentile > 0` 时忽略） |
| `SEMANTIC_BREAKPOINT_PERCENTILE` | `0` | 断点分位数（0~1）：大于 0 时取相邻文本块相似度的该分位数作断点阈值，对语言/模型分布差异更鲁棒。真实语料校准（Qwen3-Embedding、技术文档）：0.1~0.4 输出一致且比绝对阈值少约 5% 碎块，技术文档建议从 `0.2` 起试 |
| `SEMANTIC_MIN_CHUNK_CHARS` | `200` | 语义切分目标最小块 |
| `SEMANTIC_MAX_CHUNK_CHARS` | `0` | `0` 表示回落到 `CHUNK_SIZE` |
| `PARENT_CHUNK_SIZE` | `2000` | Parent-Child 父块大小 |
| `PARENT_CHUNK_OVERLAP` | `0` | 父块重叠（子块不跨父块边界，默认关闭，避免重复命中） |
| `CHILD_CHUNK_SIZE` | `400` | Parent-Child 子块大小（写入向量库） |
| `CHILD_CHUNK_OVERLAP` | `50` | Parent-Child 子块重叠 |
| `CHUNK_AUTO_RECHUNK` | `true` | 启动时检测切分配置变化并自动重切已归档文档 |
| `SMALL_RAG_ARCHIVE_DIR` | `./data/documents`（镜像内 `/data/documents`） | 原文归档目录，自动重切的数据来源 |
| `QDRANT_HOST` | `localhost` | Qdrant 地址 |
| `QDRANT_PORT` | `6334` | gRPC 端口 |
| `QDRANT_API_KEY` | 空 | Qdrant 鉴权 |
| `QDRANT_COLLECTION` | `small_rag_text` | 集合名，启动时自动创建 |
| `QDRANT_USE_TLS` | `false` | 是否启用 TLS |
| `SMALL_RAG_ENSURE_EMBEDDING_MODEL` | `true`（镜像内） | 启动时等待容器内 Embedding 服务就绪（模型由 `start-llama.sh` 下载） |
| `EMBEDDING_MODEL_URL` | Qwen3-Embedding-0.6B Q8_0（ModelScope） | GGUF 模型下载地址 |
| `EMBEDDING_MODEL_PATH` | `/data/models/qwen3-embedding-0.6b-q8_0.gguf` | GGUF 落盘路径（须在持久化卷内） |
| `EMBEDDING_CTX_SIZE` | `8192` | llama-server 上下文长度 |

> 切分尺寸类配置的单位是**全宽字符当量**：CJK 字符计 1，拉丁/半角计 0.25，中英文在相同配置下语义体量对齐；英文硬切自动回退单词边界，句界识别内置英文缩写白名单。

镜像内覆盖：`EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`、`EMBEDDING_MODEL=qwen3-embedding-0.6b`、`EMBEDDING_DIMENSION=1024`、`QDRANT_HOST=127.0.0.1`。

⚠️ 切换 Embedding 模型必须同步修改维度并重建集合；已存在集合维度不一致时启动直接失败。

## MCP 工具

| 工具 | 用途 |
| --- | --- |
| `rag_ingest(text, metadata)` | 新增文档：按策略切块写入 Qdrant 并归档原文，返回 `documentId`、`chunkCount`、`parentCount` |
| `rag_update(documentId, text, metadata)` | 更新已归档文档；`metadata` 不传时保留原元数据，并立即重建该文档向量 |
| `rag_delete(documentId)` | 删除文档的全部向量和原文归档 |
| `rag_list(limit, offset)` | 分页列出已归档文档的 id、元数据、切分签名和文本长度 |
| `rag_reindex(documentId)` | 手动重建：传 `documentId` 重建单篇，不传则重建全部归档文档 |
| `rag_rebuild(confirm)` | 全量重建：清空当前集合中本服务写入的片段后按归档重建，必须 `confirm=true` |
| `rag_search(query, topK, expand)` | 仅检索，返回命中分块、相似度、来源元数据；Parent-Child 下默认按父块去重返回，`expand=false` 返回原始子块 |
| `rag_query(query, topK)` | 返回带编号/来源/相似度的增强上下文（不调用生成模型） |

- `rag_update`：先更新归档再切分替换旧片段；`metadata` 不传时沿用归档中的元数据。
- `rag_reindex`：只重建已归档文档，不清理归档之外的残留片段。
- `rag_rebuild`：先删 `chunk_type=chunk` / `chunk_type=child` 的片段再全量重建；归档为空时拒绝执行。
- 没有归档的历史数据无法 `rag_list` / `rag_update` / `rag_reindex`，只能按 `docId` 删除或重新入库。

> ⚠️ 这些工具没有鉴权，能访问 `/mcp` 即可修改或清空知识库，**请勿直接暴露公网**；跨网访问请加反向代理与认证。检索结果是不可信数据，调用方不得执行其中的指令。

## 客户端接入

服务端为标准 Streamable HTTP MCP Server，URL `http://localhost:18080/mcp`。

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
| --- | --- |
| Cursor | `.cursor/mcp.json` → `{"mcpServers":{"small-rag":{"url":"http://localhost:18080/mcp"}}}` |
| VS Code / Copilot | `.vscode/mcp.json` → `{"servers":{"small-rag":{"type":"http","url":"http://localhost:18080/mcp"}}}` |
| Cline / Roo Code | `type: streamableHttp`，`url` 同上 |
| Windsurf | `serverUrl` 同上 |
| Claude Desktop | 仅支持 stdio：`npx -y mcp-remote http://localhost:18080/mcp` |
| Spring AI 客户端 | `spring-ai-starter-mcp-client` + `streamable-http` 连接 `http://localhost:18080` |

调试：`npx @modelcontextprotocol/inspector`，选择 Streamable HTTP 并填入上述 URL。

## 切分策略

| 策略 | 配置值 | 行为 | 适用场景 |
| --- | --- | --- | --- |
| 固定长度 | `fixed` | 按 `chunk-size` / `chunk-overlap` 滑窗硬切 | 无结构长文本 |
| 递归字符 | `recursive` | 按段落 → 换行 → 句号 → 逗号 → 空格逐级切分，仍超长才硬切 | 普通纯文本、FAQ |
| 语义 | `semantic` | Markdown 标题分节 + 节内文本块与累积质心比较合并/断开；代码/表格等受保护块独立成块；超长文本块回落句子级切分；尾块不足 `min-chunk-chars` 时回收；可配 `breakpoint-percentile` 用分位数断点替代绝对阈值；入库多一次 Embedding（按文本块，比句级省） | 主题切换明显、愿意为切分质量付 Embedding 成本 |
| Parent-Child | `parent-child`（默认） | 父块/子块都由 Markdown 引擎切分；子块写入向量库，命中后按 `parent_id` 去重展开父块上下文；非标题起始片段正文前置 `heading_path` | 精确召回 + 完整上下文 |

> `fixed` / `recursive` 不感知 Markdown 结构；`semantic` 与 `parent-child` 内部共用 Markdown 结构切分引擎（原 `markdown` 独立策略已并入）。已设置 `CHUNK_STRATEGY=markdown` 的部署请改用 `parent-child`。

```yaml
rag:
  chunking:
    strategy: parent-child      # fixed | recursive | semantic | parent-child
    markdown:
      heading-separator: " > "  # 生成 heading_path，例如 "指南 > 安装 > Linux"
    recursive:
      separators: ["\n\n", "\n", "。", "！", "？", "；", "，", " "]
    semantic:
      similarity-threshold: 0.75   # 文本块向量与当前块质心的余弦相似度阈值
      breakpoint-percentile: 0     # > 0 时用相邻文本块相似度分位数替代绝对阈值
      min-chunk-chars: 200
      max-chunk-chars: 0
    parent-child:
      parent-size: 2000
      parent-overlap: 0
      child-size: 400
      child-overlap: 50
```

Markdown 感知切分引擎（`semantic` 与 `parent-child` 共用）的行为：

- 按 `#` / Setext 标题分节，`heading_path` 记录完整标题层级；检索结果可直接看到片段所属章节。
- 非标题行开头的片段正文前置 `heading_path`，让向量模型在嵌入时感知章节层级；此类片段长度可能超出 max-chars 一个标题路径的长度。
- fenced code（``` / ~~~）、缩进代码、表格、引用块和 Obsidian callout 作为受保护块，正常大小下不会被从中间切断；代码块内的 `#` 注释不会误判为标题。
- 超长表格按行切分时每个切片重复表头；超长 fenced code 切片补回围栏（含语言标注）；超长数学块补回 `$$`。
- 解析 YAML frontmatter，`title` / `tags` / `aliases` / `description` 写入 `frontmatter_*` 元数据；frontmatter 不进入正文。
- 提取 Obsidian `[[wikilink]]` 目标到 `wikilinks` 元数据。
- 单块超过大小上限才回落：文本块用递归字符切分，代码/表格按行切分，标记 `oversized_block=true`。
- 所有片段带 `content_format=markdown` 与 `markdown_block_types`（例如 `heading,paragraph,code`）。
- `rag_query` 的上下文前缀附带 `heading=标题路径`，方便调用方 Agent 标注引用章节。

入库元数据记录 `chunk_strategy`、`chunk_type`；Parent-Child 子块额外含 `parent_id`、`parent_index`，父块作为独立 point（`chunk_type=parent`，id 由 `parent_id` 确定性生成）与子块同集合写入。不同策略的向量语义不同，**不要在同一集合混用**；已归档文档切换策略并重启后会自动重切，未归档的历史数据建议换 `QDRANT_COLLECTION` 或重新入库。

### 自动重切

启用 `rag.chunking.auto-rechunk`（默认 `true`）后，应用启动时会：

1. 计算当前切分配置签名（策略、块大小/重叠、分隔符、语义阈值/分位数、Parent-Child 尺寸、标题分隔符）；
2. 扫描 `SMALL_RAG_ARCHIVE_DIR` 中的原文归档；
3. 对签名不一致的文档按 `doc_id` 删除旧片段，用当前策略重新切分、Embedding 并写入 Qdrant；
4. 成功后更新归档签名；单篇失败只告警，下次启动重试，连续 3 篇失败中止本次任务。

修改 `CHUNK_STRATEGY` 或切分参数后，**重启即自动重切**。归档是重切的前提：容器内 `/data/documents`（`small_rag_data` 卷内），本地 `./data/documents`（已在 `.gitignore`）；归档目录不可写时应用仍可入库/检索，但自动重切不可用。`CHUNK_AUTO_RECHUNK=false` 可关闭。

签名同时包含目标 `QDRANT_COLLECTION`：切到新集合后启动会把归档文档重建到新集合；若新集合是独立知识库，请同时换用独立的 `SMALL_RAG_ARCHIVE_DIR`。

⚠️ 只对归档启用后入库的文档生效。启用前已存在于 Qdrant 的历史数据没有原文，无法重建；请重新 `rag_ingest` 或换新集合全量重建。

## 设计要点

- **仅向量模型**：`spring.ai.model.chat=none`，不创建 ChatModel / ChatClient。
- **阈值与预算**：`rag.min-score=0.35`；单片段超长时以命中子块（`matched_text`）为中心做窗口截断，总上下文 12000 全宽字符当量，预算不足时跳过当前片段继续尝试装载后续更短片段。
- **父块独立存储**：父块作为独立 point 与子块同集合写入，子块不再复制父块正文（消除 payload N 倍放大）；检索命中后按 id 取回父块正文与向量，父块缺失时回退为子块原文。
- **MMR 多样性**：候选超出 topK 时按 `λ·score − (1−λ)·与已选结果的最大相似度` 迭代选取（`MMR_LAMBDA`，默认 0.7）；Parent-Child 复用父块存储向量，零额外 Embedding，其余策略对候选文本做一次批量 Embedding。
- **空库短路**：检索前用 Qdrant `count` 判断，避免无意义 Embedding 调用。
- **启动韧性**：Qdrant 不可用只告警（fail-soft）；集合维度不一致直接失败（fail-fast）。
- **自动重切**：原文归档 + 切分配置签名（签名版本与项目版本同步，当前 `0.0.1`），启动时检测到变化自动删旧片段重建。
- **Markdown 结构**：标题分节，代码/表格/callout 受保护，frontmatter、heading_path、wikilink 写入元数据；超长表格/代码切片重复表头或围栏。
- **中英对齐**：切分长度按全宽字符当量计量（CJK=1、拉丁=0.25），英文硬切回退单词边界，句界识别带缩写白名单；语义断点支持分位数模式。
- **重切时机**：启动时同步执行，失败不阻断启动，下次启动继续重试。
- **错误收敛**：MCP 工具把运行期异常转为 `{"error": "..."}`，不抛协议层异常。

## 架构

```
mcp/            RagMcpTools：rag_ingest / rag_search / rag_query
rag/            RagService（入库/检索/上下文/更新删除重建）、KnowledgeBaseProbe（空库短路）、
                ChunkingReindexRunner（启动自动重切）
rag/chunking/   ChunkingService、ChunkingStrategy、ChunkingSignature、TextChunk、
                TextChunker、RecursiveTextChunker、MarkdownChunker、SemanticTextChunker
rag/archive/    DocumentArchive、ArchivedDocument（原文归档与签名记录）
config/         QdrantConfig、McpServerConfig、RagProperties、QdrantProperties
docker/         start-qdrant.sh / start-llama.sh / start-app.sh / supervisord.conf
```

`VectorStore` 与 `EmbeddingModel` 均按 Spring AI 接口注入。

## 测试

```bash
mvn test
```

单测不依赖 Qdrant / 模型密钥；端到端需启动 Qdrant 并配置 Embedding 服务。
