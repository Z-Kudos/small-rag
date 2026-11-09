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
- 项目版本 `0.0.3-SNAPSHOT`；`ChunkingSignature` 签名版本与项目版本同步（演进 v2→…→v0.0.3）。

## 依赖与构建

- parent 用 `spring-boot-starter-parent`；`spring-cloud-dependencies` 与 `spring-ai-bom` 均以 `import` 引入。
- Starter 清单：`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-model-openai`（**不存在 `spring-ai-starter-model-embedding`**）、`spring-ai-starter-vector-store-qdrant`、`spring-boot-starter-web`、`spring-boot-starter-data-redis`（distributed 模式锁；standalone 不会连接）、`spring-cloud-starter-consul-discovery` / `spring-cloud-starter-consul-config`（默认关闭）、`spring-boot-starter-actuator`（仅 `/actuator/health`）、`resilience4j-circuitbreaker`（检索熔断）、`lombok`（optional）。
- **不要**同时引入 STDIO 的 `spring-ai-starter-mcp-server` 与 webmvc starter——自动配置冲突。只用 webmvc + `protocol=STREAMABLE`。
- **JDK 23+ 默认不自动发现注解处理器**：必须 `<maven.compiler.proc>full</maven.compiler.proc>`，否则 Lombok "找不到符号"。
- 本机构建（默认 JAVA_HOME 是 JDK 21，编不了 release=25）：`JAVA_HOME='D:\java\jdk-27.0.1.0.3' mvn clean package`。
- 验证命令：`mvn clean package -DskipTests`；单测 `mvn test`（不依赖外部服务）。
- **依赖源**：`pom.xml` 的 `<repositories>` / `<pluginRepositories>` 首位是 `aliyun-public`（`https://maven.aliyun.com/repository/public`），其后才是 `spring-milestones`；容器构建额外用 `docker/maven-settings.xml`（`mirrorOf=*,!spring-milestones`）。**不要**把阿里云仓库放到 spring-milestones 之后，也不要引入 `mirrorOf=*` 而不排除 spring-milestones，否则 RC 构件会解析失败。

## 容器化

- **双运行模式**：默认 `SMALL_RAG_RUNTIME=all-in-one`，镜像同时包含应用、Qdrant、llama.cpp，`supervisord` 作为 PID 1；`app-only` 只运行应用（`docker/supervisord-app-only.conf`），依赖由外部提供。`docker/entrypoint.sh` 按 `SMALL_RAG_RUNTIME` 选择配置。**standalone 必须保持零新增外部依赖**。
- 只对外暴露 **18080**；all-in-one 时 Qdrant（6333/6334）与 Embedding 服务（11434）绑定 `127.0.0.1`。distributed 模式由 gateway（HAProxy，P3 可替换为 Spring Cloud Gateway）统一暴露 18080。
- 多阶段构建：`maven:3.9-eclipse-temurin-25` 构建（`-s docker/maven-settings.xml` 走阿里云）→ 以 `qdrant/qdrant`（debian:13-slim）为基底**从源码编译 llama-server**（`LLAMA_CPP_TAG`，默认 `v0.9.0`；编译基底=运行基底，无 glibc 兼容问题；源码包走 gh-proxy 加速）→ 拷入 Temurin JRE；基础镜像可用 `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE` 替换。**不用 Ollama 镜像**（3.25GB，国内拉取过慢）。
- 默认向量模型 Qwen3-Embedding-0.6B Q8_0（1024 维），`start-llama.sh` 首次启动从 ModelScope 下载 GGUF（`EMBEDDING_MODEL_URL`→`EMBEDDING_MODEL_PATH`，断点续传）并以 `--embeddings --pooling last` 启动 llama-server（**Qwen3-Embedding 必须 last pooling**）；`start-app.sh` 等待其 `/health` 就绪。
- 启动脚本：`docker/start-qdrant.sh`、`start-llama.sh`、`start-app.sh`，由 `docker/supervisord.conf`（all-in-one）或 `supervisord-app-only.conf` 调度；数据统一 `/data`。
- 目录导入的源目录用只读 bind mount 挂到 `/data/sources/...`：Compose 用可选 `compose.sources.yaml`（`make up-sources`，宿主机路径由 `.env` 的 `SMALL_RAG_SOURCE_HOST` 控制），或用 `docker run -v /host/notes:/data/sources/notes:ro -e SMALL_RAG_SOURCES_ENABLED=true -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes'`；`SMALL_RAG_SOURCE_DIRS` 必须是容器内路径。源目录必须对容器内 UID `10001` 可读；增量状态默认写 `<archive-dir>/.sources/state.json`，随归档卷持久化。
- 端口与默认值在 `Dockerfile`、`compose.yaml`、`.env`、`README.md` 间保持一致；`.env` 已入库（仅模板值），**真实密钥不要写入 `.env`**（改用部署环境注入），`.dockerignore` 仍排除它以防进入镜像。
- Podman：`podman compose up -d --build` 或 `podman-compose up -d --build`，同一份 `compose.yaml`；带目录挂载用 `podman compose -f compose.yaml -f compose.sources.yaml up -d --build`。
- 分布式（可选）：`docker compose -f compose.yaml -f compose.distributed.yaml up -d --scale small-rag=2`（需 Compose v2.24+，overlay 用 `!reset []` 去掉应用端口；gateway 统一暴露 18080）。外部依赖为 Qdrant / Redis / Consul / Embedding；`small_rag_data` 作为多副本共享归档卷，多主机需换成 RWX 存储。

## 架构分层

- `mcp`：`RagMcpTools` 暴露 `rag_ingest`/`rag_update`/`rag_delete`/`rag_list`/`rag_reindex`/`rag_rebuild`/`rag_task_status`/`rag_task_resume`/`rag_task_cancel`/`rag_sync_sources`/`rag_sync_status`/`rag_search`/`rag_query`（13 个）。**不是** `@Component`，由 `McpServerConfig` 显式注册。工具方法必须写 Javadoc——注解扫描会把它作为工具描述发给客户端。
- `rag`：`RagService` 负责分块入库、向量检索、上下文拼装、更新/删除/手动重建，**不调用 Chat/生成模型**；单篇 `reindex` 能枚举旧 point id 时先写新片段再按 id 删旧片段，失败时旧数据仍可检索（无法枚举时回退先删后写）；`IndexActivityGuard` 串行化“重建任务”与“目录导入”两类索引写入（standalone 进程内锁；distributed 经 `DistributedLock` 走 Redis 租约锁，后台续租，每次写入前远程校验 owner+token，丢失即 `isHeldBy=false` 停止写入）；`QueryCircuitBreaker` 只包住 `rag_search`/`rag_query`（`rag.query-breaker.*`），连续失败快速失败，入库/重建不受影响；`KnowledgeBaseProbe` 空库短路。
- `rag/reindex`：`ReindexTaskService` 提供异步重建任务、进度快照与看门狗（每任务独立线程 + 单任务互斥 + 保留最近 20 个；超过 `rag.reindex.stall-timeout-seconds` 标 `stalled` 但保留锁；超过 `hard-timeout-seconds` 撤销写权限、取消在途调用并中断线程，确认退出或宽限期到期后释放锁；`rag_task_cancel` 为文档边界协作式取消）；`ReindexStateStore` 将任务快照 + 检查点（`cleared` / 已完成文档 id）+ 任务锁写入 Qdrant 的独立集合 `<rag.qdrant.collection-name>_reindex_state`（`QdrantReindexStateBackend`，跟随 Qdrant 持久化，不依赖容器文件系统；1 维哑向量 + payload JSON，集合按需创建），重启后恢复为 `interrupted` 并继续持锁（锁 point 丢失但任务快照仍在时，按单实例假设由最近一个未完成任务重建锁），`rag_task_resume` 或 `resume-on-startup` 续接（跳过已完成文档、不重复清空）；`CancellationScope` 负责任务级 OkHttp 调用取消；`ChunkingReindexRunner` 启动自动重切（存在待续接重建任务时跳过，交给续接任务处理）。
- `rag/sources`：`SourceSyncService` 扫描 `rag.sources.directories`（逗号分隔，支持 `label=/path`）下的 Markdown；文档 id 由“稳定 label + 相对路径”确定性生成（目录移动时 label 不变即可续用 id，label 重复拒绝扫描）。先比 `mtime + size`，再比 SHA-256，只有内容变化才重新切分。`SourcesLeaderElection` 用 `sources-leader` 租约选举目录扫描 leader，自动触发（启动/定时/watcher）只在 leader 执行，手动 `rag_sync_sources` 不受限。`SourceStateStore` 把文件指纹与手动删除墓碑写到 `<archive-dir>/.sources/state.json`（原子替换），丢失时从归档 `source_*` 元数据重建。触发：启动扫描、定时扫描（`scan-interval-seconds`，0 关闭）、文件 watcher（`watch-enabled`，可配防抖）、MCP `rag_sync_sources(force)`（异步，`rag_sync_status` 轮询）；`max-file-bytes` / `max-files-per-scan` 限流；`delete-missing` 只删除“当前仍配置且本轮成功扫描”的根目录下已消失文件，`delete-orphan-roots` 可清理从配置移除的 label 下文档（默认关闭）。手动 `rag_delete` 会留下墓碑，源文件内容不变时不再自动导入。导入通过 `IndexActivityGuard.SOURCES` 与重建任务互斥，写入走 `RagService.upsert`（归档 + 切分 + Embedding），不使用 `rag_ingest` 的随机 id。
- `rag/chunking`（切分引擎）：
  - `ChunkingService` 按 `rag.chunking.strategy` 分发 `fixed` / `recursive` / `semantic` / `parent-child`（默认 `parent-child`）。
  - `MarkdownChunker` 是内部结构引擎（标题分节 + 受保护块），**不是独立策略**，由 `semantic` 与 `parent-child` 共用；`chunkSemantic` 提供语义合并入口（节内文本块与累积质心比较，代码/表格独立成块）。
  - `SemanticTextChunker` 只做超长文本块的句子级语义回落；`TextChunker` 定长滑窗；`RecursiveTextChunker` 分隔符递归；`ChunkingSignature` 切分配置签名；`TextChunk` 片段模型。
  - Parent-Child：父块/子块都由 `MarkdownChunker` 切分；父块作为**独立 point**（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)` 确定性生成）与子块同集合写入，子块只带 `parent_id`；检索按 `chunk_type=child` 过滤、按 `parent_id` 去重（候选 topK×4）后经 `QdrantClient.retrieveAsync` 取回父块正文与向量；父块缺失/取回失败时回退为子块原文。`rag_rebuild` 清理 `chunk`/`child`/`parent` 三种类型。
  - **长度度量**：所有切分尺寸的单位是全宽字符当量（`TextWeights`：CJK 计 1、拉丁/半角计 0.25）；英文硬切回退单词边界，句界带缩写白名单；SEMANTIC 支持 `breakpoint-percentile` 分位数断点（>0 时忽略绝对阈值）。
  - **MMR**：`rag.mmr-lambda`（默认 0.7，=1 关闭）控制候选收敛时相关性与多样性的折中；Parent-Child 复用父块存储向量做相似度（零额外模型调用），其余策略对候选文本做一次批量 Embedding（`SemanticTextChunker.embedAll` 已 public）。
- `rag/archive`：`DocumentArchive` 按 `doc_id` 归档原文到 `rag.archive-dir`；另维护 `<archive-dir>/.archive/index.json` 摘要索引（metadata/签名/长度），`rag_list`、启动重切、全文重建只读摘要并按需 `find` 原文，避免一次性载入全部 JSON；索引缺失/版本不符/id 集合不一致时重建。`ChunkingSignature` 记录影响切分结果的配置，启动时 `ChunkingReindexRunner` 对签名不一致的文档删旧片段重建。归档目录不可写时降级关闭自动重切，不阻断入库。未归档的历史数据不会自动重切。
- `distributed`：`DistributedLock`（跨副本互斥抽象）、`LockHandle`（owner + token + 租约）、`LocalDistributedLock`（standalone 进程内）、`RedisDistributedLock`（`SET NX PX` + Lua 续租/释放）。`IndexActivityGuard` 在两种实现之间由 `DistributedLockConfig` 按 `smallrag.distributed.enabled` 选择。
- `config`：`QdrantConfig`（QdrantClient / QdrantVectorStore / 集合初始化 / 探活）、`McpServerConfig`、`EmbeddingConfig`（OpenAI SDK/OkHttp 超时）、`DistributedLockConfig`、`DistributedProperties`、`IndexLockHealthIndicator`、`RagProperties`、`QdrantProperties`。
- **分布式配置**：`smallrag.distributed.enabled`（默认 `false`）、`lock.lease`（30s）、`lock.renew-interval`（10s）、`lock.key-prefix`；Redis 连接走 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。distributed 模式必须配 `SMALL_RAG_RUNTIME=app-only`，多副本共享 `SMALL_RAG_ARCHIVE_DIR`；Consul 由 `CONSUL_ENABLED`（默认 `false`）开关，注册服务 `small-rag`、健康检查 `/actuator/health`、实例 id 默认 `${spring.application.name}-${HOSTNAME}-${server.port}`（多副本唯一），配置从 Consul KV 读取需 `SPRING_CONFIG_IMPORT=optional:consul:`（默认 `KEY_VALUE` 格式，每项一个 key）；Consul config watch 默认关闭（属性未加 `@RefreshScope`）。Gateway 属于 P3。
- **检索熔断**：`rag.query-breaker.*`（`RAG_QUERY_BREAKER_ENABLED` 等），只保护 `rag_search`/`rag_query`；默认窗口 20、最小调用 10、失败率 50%、打开 30s。`/actuator/health` 暴露 `instanceId`、`activeLock`、`sourcesLeader`、`queryCircuit`、profile，便于多副本排障。
- 手工管理：`rag_update` 更新归档并重建单篇；`rag_delete` 删除向量与归档；`rag_list` 分页查看；`rag_reindex` 重建单篇（同步）/全部（异步）；`rag_rebuild(confirm=true)` 清理本服务写入的片段后全量异步重建；`rag_task_status` 查进度，`rag_task_resume` 续接重启时中断的任务，`rag_task_cancel` 取消/放弃任务。
- **只使用向量模型**：`spring.ai.model.chat=none`；不新增 `ChatClient`/`ChatModel`。`rag_query` 只返回增强上下文，生成由调用方 Agent 完成。
- **Embedding 配置入口**：`EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`（回退 `OPENAI_*`）；base-url 必须含 `/v1`。只支持 OpenAI 兼容 HTTP 服务，**不支持 JVM 内 ONNX/transformers**。HTTP 超时用 `EMBEDDING_CONNECT_TIMEOUT_MS` / `EMBEDDING_READ_TIMEOUT_MS`，由 `EmbeddingConfig` 通过 OpenAI SDK 的 `OpenAiHttpClientBuilderCustomizer` 设置（`spring.http.client.*` 不生效）。
- **Qdrant 关键约束**：
  - gRPC 端口 `6334`（REST 才是 6333）。
  - `Document` id 必须是 **UUID**；业务 id 放 metadata 的 `doc_id`。
  - `doSimilaritySearch` **先调 Embedding 再查库**；空库必须用 `KnowledgeBaseProbe`（Qdrant `count`）短路。
  - 集合初始化 fail-soft（不可用只告警）；维度与 `rag.qdrant.dimension` 不一致 fail-fast；维度必须与 Embedding 输出一致。
  - gRPC 默认 deadline 用 `rag.qdrant.request-timeout-ms`（默认 30s）；`init-timeout-ms` 只用于启动期探活/初始化等待，不要复用为常规 RPC 超时。
  - 重建任务状态用**独立集合** `<collection>_reindex_state`（1 维 Dot 哑向量 + payload JSON），不要并入主集合：否则会污染 `count` 空库探活、相似度检索与 `rag_rebuild` 的 `chunk_type` 清理。
- `McpSyncServerCustomizer` 是单例扩展点且已被 WebMVC 自动配置占用；**不要自定义 customizer**。instructions 等走 `spring.ai.mcp.server.*`。
- `application.yml` 必须：`protocol=STREAMABLE`、`name=small-rag`、`version=0.0.3-SNAPSHOT`。MCP 端点 `/mcp`。payload 正文字段名共用 `RagService.CONTENT_FIELD`（"content"），改字段名必须同步。

## 代码约束

- 测试：默认 `mvn test` 不依赖外部服务；`*RedisIT` 需 `-Dsmallrag.redis.it=true` 且本机 Redis 6390；离线 RAG 评测在 `src/test/java/.../rag/eval/`（`EvalMetrics` + `RagPipelineEvaluationTest`）。

- Lombok：DTO 用 `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor`，Service 用 `@RequiredArgsConstructor` 构造注入（禁 `@Autowired` 字段注入），日志 `@Slf4j`。
- **Spring Boot 4 已迁移 Jackson 3**：import 一律 `tools.jackson.*`，`com.fasterxml.jackson.*` 编译失败。
- 暂不开启 AOT/Native Image。

## Git 与文档

- **分支模型（Git Flow）**：长期分支 `main`（生产，随时可构建发布）与 `develop`（集成，功能合并目标）；短期分支 `feature/*`（从 `develop` 切出，squash 合回 `develop`）、`release/x.y.z`（从 `develop` 切出收敛发布，只修 bug，`--no-ff` 合入 `main` 并打 `vX.Y.Z` tag，再回灌 `develop`）、`hotfix/*`（从 `main` 或生产 tag 切出，修复合入 `main` 打补丁 tag 后回灌 `develop`）。禁止直接向 `main`/`develop` push；不设 `stable` 长期分支，生产版本由 `main` 上的 tag 标识。
- `.gitignore` 覆盖 `target/`、`.idea/`、`*.iml`、`.vscode/`、`*.log`、`data/`。
- 提交信息用 Conventional Commits。首次提交：`chore: bootstrap small-rag with JDK 25 + Spring Cloud 2026.0.0 + Spring AI 2.0.2`。
- `README.md` 必须含：版本矩阵、文档索引、启动命令、`/mcp` 端点说明、入库示例、RC/milestone 说明。
- **开发记忆（强制）**：每次开发都必须同步更新项目根目录的 `DEV-MEMORY.md`（不存在则创建）——简要记录本次开发所做的更改。要求：尽可能精简且实时，只保留当前有效的状态（架构、接口、行为、关键决策），过时的记忆直接覆盖或删除，不做历史归档（历史归档交给 git log）。
- **文档规范（强制）**：完整规范见 `docs/README.md`，要点：
  - 单一事实来源：`README.md` 面向使用者，`ROADMAP.md` 只写版本级里程碑与验收，`docs/plans/` 写版本规划，`docs/design/` 写跨版本设计，`docs/runbooks/` 写运维步骤，`docs/adr/` 写不可逆决策，`DEV-MEMORY.md` 写当前开发状态。同一事实只在一处定义，其余链接引用。
  - 新建文档必须放在 `docs/` 对应子目录，文件名用小写 kebab-case 英文；版本规划命名 `plans/vX.Y.Z-<topic>.md`，从 `docs/templates/plan.md` 复制。
  - 文档必须带 YAML front matter：`title` / `status`（draft|review|approved|implementing|done|superseded）/ `version` / `updated` / `owners` / `related`。
  - 数字、端口、默认值、命令必须与代码一致；不确定写“待核实”。修改文档同步更新 `updated`。
  - 版本发布后 plan 标 `done`，要点合并进 `ROADMAP.md`；下一版本开始时删除旧 plan（历史交给 git log）。

## 验收标准

1. `mvn clean package -DskipTests` 成功；`mvn test` 无外部依赖可跑。
2. 启动后 `/mcp` 可用，MCP Inspector 能列出全部 13 个工具。
3. 空知识库下 `rag_query` 返回友好提示而非异常堆栈（依赖 count 短路）。
4. 端到端（入库→检索）需要 Qdrant + OpenAI 兼容 Embedding；不需要 Chat 模型。
5. README 步骤可复现，`git log` 至少一条 Conventional Commits。
