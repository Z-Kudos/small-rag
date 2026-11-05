# AGENTS.md — small-rag

单模块 Spring Boot 服务 `small-rag`：通过 MCP（Streamable HTTP）暴露**纯文本** RAG 能力；向量库固定 **Qdrant**。

## 版本基线（严格锁定）

| 组件 | 版本 |
|---|---|
| JDK | 25（`maven.compiler.release=25`） |
| Spring Boot | 4.2.0（实际解析 `4.2.0-RC1`） |
| Spring Cloud | 2026.0.0（实际解析 `-RC1`） |
| Spring AI | 2.0.2（Maven Central 稳定版） |

- **解析现状（2026-11 验证）**：Boot/Cloud GA 未发布，实际组合 `4.2.0-RC1` + `2026.0.0-RC1`，依赖 `https://repo.spring.io/milestone`（pom 已配置）。**禁止降级到 Boot 3.x / Cloud 2025.x 来"修复"解析失败**。
- 项目版本 `0.0.2`；`ChunkingSignature` 签名版本与项目版本同步（此前演进 v2→0.0.1）。

## 依赖与构建

- parent 用 `spring-boot-starter-parent`；`spring-cloud-dependencies` 与 `spring-ai-bom` 均以 `import` 引入。
- Starter 清单：`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-model-openai`（**不存在 `spring-ai-starter-model-embedding`**）、`spring-ai-starter-vector-store-qdrant`、`spring-boot-starter-web`、`lombok`（optional）。
- **不要**同时引入 STDIO 的 `spring-ai-starter-mcp-server` 与 webmvc starter——自动配置冲突。只用 webmvc + `protocol=STREAMABLE`。
- **JDK 23+ 默认不自动发现注解处理器**：必须 `<maven.compiler.proc>full</maven.compiler.proc>`，否则 Lombok "找不到符号"。
- 本机构建（默认 JAVA_HOME 是 JDK 21，编不了 release=25）：`JAVA_HOME='D:\java\jdk-27.0.1.0.3' mvn clean package`。
- 验证命令：`mvn clean package -DskipTests`；单测 `mvn test`（不依赖外部服务）。

## 容器化

- **单容器架构**：最终镜像同时包含应用、Qdrant、Ollama，`supervisord` 作为 PID 1；不拆 compose 多服务、不做 sidecar。
- 只对外暴露 **18080**；Qdrant（6333/6334）与 Ollama（11434）绑定 `127.0.0.1`。
- 多阶段构建：`maven:3.9-eclipse-temurin-25` 构建 → 运行时基于 `qdrant/qdrant` + Temurin JRE + Ollama CPU 运行时（剔除 CUDA/ROCm）；基础镜像可用 `MAVEN_IMAGE` / `OLLAMA_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE` 替换。
- 默认向量模型 `modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF`（1024 维）；`SMALL_RAG_ENSURE_OLLAMA_MODEL=true` 时 `start-app.sh` 等待 Ollama 并拉模型。
- 启动脚本：`docker/start-qdrant.sh`、`start-ollama.sh`、`start-app.sh`，由 `docker/supervisord.conf` 调度；数据统一 `/data`。
- 端口与默认值在 `Dockerfile`、`compose.yaml`、`.env.example`、`README.md` 间保持一致；`.env`/密钥不入库。
- Podman：`podman compose up -d --build` 或 `podman-compose up -d --build`，同一份 `compose.yaml`。

## 架构分层

- `mcp`：`RagMcpTools` 暴露 `rag_ingest`/`rag_update`/`rag_delete`/`rag_list`/`rag_reindex`/`rag_rebuild`/`rag_search`/`rag_query`。**不是** `@Component`，由 `McpServerConfig` 显式注册。工具方法必须写 Javadoc——注解扫描会把它作为工具描述发给客户端。
- `rag`：`RagService` 负责分块入库、向量检索、上下文拼装、更新/删除/手动重建，**不调用 Chat/生成模型**；`KnowledgeBaseProbe` 空库短路；`ChunkingReindexRunner` 启动自动重切。
- `rag/chunking`（切分引擎）：
  - `ChunkingService` 按 `rag.chunking.strategy` 分发 `fixed` / `recursive` / `semantic` / `parent-child`（默认 `parent-child`）。
  - `MarkdownChunker` 是内部结构引擎（标题分节 + 受保护块），**不是独立策略**，由 `semantic` 与 `parent-child` 共用；`chunkSemantic` 提供语义合并入口（节内文本块与累积质心比较，代码/表格独立成块）。
  - `SemanticTextChunker` 只做超长文本块的句子级语义回落；`TextChunker` 定长滑窗；`RecursiveTextChunker` 分隔符递归；`ChunkingSignature` 切分配置签名；`TextChunk` 片段模型。
  - Parent-Child：父块/子块都由 `MarkdownChunker` 切分；父块作为**独立 point**（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)` 确定性生成）与子块同集合写入，子块只带 `parent_id`；检索按 `chunk_type=child` 过滤、按 `parent_id` 去重（候选 topK×4）后经 `QdrantClient.retrieveAsync` 取回父块正文与向量；父块缺失/取回失败时回退为子块原文。`rag_rebuild` 清理 `chunk`/`child`/`parent` 三种类型。
  - **长度度量**：所有切分尺寸的单位是全宽字符当量（`TextWeights`：CJK 计 1、拉丁/半角计 0.25）；英文硬切回退单词边界，句界带缩写白名单；SEMANTIC 支持 `breakpoint-percentile` 分位数断点（>0 时忽略绝对阈值）。
  - **MMR**：`rag.mmr-lambda`（默认 0.7，=1 关闭）控制候选收敛时相关性与多样性的折中；Parent-Child 复用父块存储向量做相似度（零额外模型调用），其余策略对候选文本做一次批量 Embedding（`SemanticTextChunker.embedAll` 已 public）。
- `rag/archive`：`DocumentArchive` 按 `doc_id` 归档原文到 `rag.archive-dir`；`ChunkingSignature` 记录影响切分结果的配置，启动时 `ChunkingReindexRunner` 对签名不一致的文档删旧片段重建。归档目录不可写时降级关闭自动重切，不阻断入库。未归档的历史数据不会自动重切。
- `config`：`QdrantConfig`（QdrantClient / QdrantVectorStore / 集合初始化 / 探活）、`McpServerConfig`、`RagProperties`、`QdrantProperties`。
- 手工管理：`rag_update` 更新归档并重建单篇；`rag_delete` 删除向量与归档；`rag_list` 分页查看；`rag_reindex` 重建单篇/全部；`rag_rebuild(confirm=true)` 清理本服务写入的片段后全量重建。
- **只使用向量模型**：`spring.ai.model.chat=none`；不新增 `ChatClient`/`ChatModel`。`rag_query` 只返回增强上下文，生成由调用方 Agent 完成。
- **Embedding 配置入口**：`EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`（回退 `OPENAI_*`）；base-url 必须含 `/v1`。只支持 OpenAI 兼容 HTTP 服务，**不支持 JVM 内 ONNX/transformers**。
- **Qdrant 关键约束**：
  - gRPC 端口 `6334`（REST 才是 6333）。
  - `Document` id 必须是 **UUID**；业务 id 放 metadata 的 `doc_id`。
  - `doSimilaritySearch` **先调 Embedding 再查库**；空库必须用 `KnowledgeBaseProbe`（Qdrant `count`）短路。
  - 集合初始化 fail-soft（不可用只告警）；维度与 `rag.qdrant.dimension` 不一致 fail-fast；维度必须与 Embedding 输出一致。
- `McpSyncServerCustomizer` 是单例扩展点且已被 WebMVC 自动配置占用；**不要自定义 customizer**。instructions 等走 `spring.ai.mcp.server.*`。
- `application.yml` 必须：`protocol=STREAMABLE`、`name=small-rag`、`version=0.0.2`。MCP 端点 `/mcp`。payload 正文字段名共用 `RagService.CONTENT_FIELD`（"content"），改字段名必须同步。

## 代码约束

- Lombok：DTO 用 `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor`，Service 用 `@RequiredArgsConstructor` 构造注入（禁 `@Autowired` 字段注入），日志 `@Slf4j`。
- **Spring Boot 4 已迁移 Jackson 3**：import 一律 `tools.jackson.*`，`com.fasterxml.jackson.*` 编译失败。
- 暂不开启 AOT/Native Image。

## Git 与文档

- 主分支 `main`；`.gitignore` 覆盖 `target/`、`.idea/`、`*.iml`、`.vscode/`、`*.log`、`.env`、`data/`。
- 提交信息用 Conventional Commits。首次提交：`chore: bootstrap small-rag with JDK 25 + Spring Cloud 2026.0.0 + Spring AI 2.0.2`。
- `README.md` 必须含：版本矩阵、启动命令、`/mcp` 端点说明、入库示例、RC/milestone 说明。
- **开发记忆（强制）**：每次开发都必须同步更新项目根目录的 `DEV-MEMORY.md`（不存在则创建）——简要记录本次开发所做的更改。要求：尽可能精简且实时，只保留当前有效的状态（架构、接口、行为、关键决策），过时的记忆直接覆盖或删除，不做历史归档（历史归档交给 git log）。

## 验收标准

1. `mvn clean package -DskipTests` 成功；`mvn test` 无外部依赖可跑。
2. 启动后 `/mcp` 可用，MCP Inspector 能列出全部 8 个工具。
3. 空知识库下 `rag_query` 返回友好提示而非异常堆栈（依赖 count 短路）。
4. 端到端（入库→检索）需要 Qdrant + OpenAI 兼容 Embedding；不需要 Chat 模型。
5. README 步骤可复现，`git log` 至少一条 Conventional Commits。
