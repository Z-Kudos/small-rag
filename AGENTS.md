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
- Starter 清单：`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-model-openai`（**不存在 `spring-ai-starter-model-embedding`**）、`spring-ai-starter-vector-store-qdrant`、`spring-boot-starter-web`、`spring-boot-starter-data-redis`（distributed 模式锁；standalone 不会连接）、`spring-cloud-starter-consul-discovery` / `spring-cloud-starter-consul-config`（默认关闭；discovery 已显式排除未使用的 `spring-cloud-starter-loadbalancer`）、`spring-boot-starter-actuator`（仅 `/actuator/health`）、`resilience4j-circuitbreaker`（检索熔断）、`lombok`（optional）。**Spring Cloud 只保留 Consul 两件套**，不引入 Gateway / Config Server / Bus / Vault / Kubernetes / OpenFeign / CircuitBreaker 包装，见 [`docs/v0.0.3/adr/0001-spring-cloud-scope.md`](docs/v0.0.3/adr/0001-spring-cloud-scope.md)。
- **不要**同时引入 STDIO 的 `spring-ai-starter-mcp-server` 与 webmvc starter——自动配置冲突。只用 webmvc；standalone 用 `protocol=STREAMABLE`，distributed 多副本必须 `STATELESS`（`SMALL_RAG_MCP_PROTOCOL`）。
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
- `rag`：`RagService` 负责分块入库、向量检索、上下文拼装、更新/删除/手动重建，**不调用 Chat/生成模型**；子块 point id 为 `chunk:<docId>:<切分签名>:<index>` 确定性生成（相同配置重复 upsert 覆盖同一批 point，不产生重复），单篇 `reindex` 能枚举旧 point id 时先写新片段再按 id 删旧片段，失败时旧数据仍可检索（无法枚举时回退先删后写）；`rag_rebuild` 完成后清理无子块父块与缺 `chunk_type` 的旧片段；`IndexActivityGuard` 串行化“重建任务”与“目录导入”两类索引写入（standalone 进程内锁；distributed 经 `DistributedLock` 走 Redis 租约锁，后台续租，每次写入前远程校验 owner+token，丢失即 `isHeldBy=false` 停止写入）；`QueryCircuitBreaker` 只包住 `rag_search`/`rag_query`（`rag.query-breaker.*`），连续失败快速失败，入库/重建不受影响；`KnowledgeBaseProbe` 空库短路。
- `rag/reindex`：`ReindexTaskService` 提供异步重建任务、进度快照与看门狗（每任务独立线程 + 单任务互斥 + 保留最近 20 个；超过 `rag.reindex.stall-timeout-seconds` 标 `stalled` 但保留锁；超过 `hard-timeout-seconds` 撤销写权限、取消在途调用并中断线程，确认退出或宽限期到期后释放锁；`rag_task_cancel` 为文档边界协作式取消）。任务所有权以 `FencedLease` 为准：`<prefix>reindex-task` 租约 + 单调 epoch（standalone 进程内、distributed Redis；Redis 不可用 fail-closed），`isWritable()` 每次写入前远程校验 owner+token+epoch。`ReindexStateStore` 将任务快照 + 检查点（`cleared` / 已完成文档 id）+ 任务锁**诊断镜像**写入 Qdrant 的独立集合 `<rag.qdrant.collection-name>_reindex_state`（Qdrant 无 CAS，镜像不参与互斥）。任意副本的 `rag_task_status` 在本地缺失时回读状态集合（`smallrag.distributed.task-status-cache-ms`，默认 2s），`rag_task_resume` / `rag_task_cancel` 先抢任务租约再接管（epoch 递增；抢不到返回持有者实例）。重启后抢到租约才恢复为 `interrupted` 并由 `rag_task_resume` 或 `resume-on-startup` 续接（跳过已完成文档、不重复清空），启动时校验并清理与权威锁不一致的镜像；`CancellationScope` 负责任务级 OkHttp 调用取消；`ChunkingReindexRunner` 启动自动重切（存在待续接重建任务时跳过，交给续接任务处理）。
- `rag/sources`：`SourceSyncService` 扫描 `rag.sources.directories`（逗号分隔，支持 `label=/path`）下的 Markdown；文档 id 由“稳定 label + 相对路径”确定性生成（目录移动时 label 不变即可续用 id，label 重复拒绝扫描）。先比 `mtime + size`，再比 SHA-256，只有内容变化才重新切分。`SourcesLeaderElection` 用 `sources-leader` 租约选举目录扫描 leader，自动触发（启动/定时/watcher）只在 leader 执行，手动 `rag_sync_sources` 不受限。`SourceStateStore` 把文件指纹与手动删除墓碑写到 `<archive-dir>/.sources/state.json`（原子替换），丢失时从归档 `source_*` 元数据重建。触发：启动扫描、定时扫描（`scan-interval-seconds`，0 关闭）、文件 watcher（`watch-enabled`，可配防抖）、MCP `rag_sync_sources(force)`（异步，`rag_sync_status` 轮询）；`max-file-bytes` / `max-files-per-scan` 限流；`delete-missing` 只删除“当前仍配置且本轮成功扫描”的根目录下已消失文件，`delete-orphan-roots` 可清理从配置移除的 label 下文档（默认关闭）。手动 `rag_delete` 会留下墓碑，源文件内容不变时不再自动导入。导入通过 `IndexActivityGuard.SOURCES` 与重建任务互斥，写入走 `RagService.upsert`（归档 + 切分 + Embedding），不使用 `rag_ingest` 的随机 id。
- `rag/chunking`（切分引擎）：
  - `ChunkingService` 按 `rag.chunking.strategy` 分发 `fixed` / `recursive` / `semantic` / `parent-child`（默认 `parent-child`）。
  - `MarkdownChunker` 是内部结构引擎（标题分节 + 受保护块），**不是独立策略**，由 `semantic` 与 `parent-child` 共用；`chunkSemantic` 提供语义合并入口（节内文本块与累积质心比较，代码/表格独立成块）。
  - `SemanticTextChunker` 只做超长文本块的句子级语义回落；`TextChunker` 定长滑窗；`RecursiveTextChunker` 分隔符递归；`ChunkingSignature` 切分配置签名；`TextChunk` 片段模型。
  - Parent-Child：父块/子块都由 `MarkdownChunker` 切分；父块作为**独立 point**（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)` 确定性生成）与子块同集合写入，子块只带 `parent_id`；检索按 `chunk_type=child` 过滤、按 `parent_id` 去重（候选 topK×4）后经 `QdrantClient.retrieveAsync` 取回父块正文与向量；父块缺失/取回失败时回退为子块原文。`rag_rebuild` 清理 `chunk`/`child`/`parent` 三种类型。
  - **长度度量**：所有切分尺寸的单位是全宽字符当量（`TextWeights`：CJK 计 1、拉丁/半角计 0.25）；英文硬切回退单词边界，句界带缩写白名单；SEMANTIC 支持 `breakpoint-percentile` 分位数断点（>0 时忽略绝对阈值）。
  - **MMR**：`rag.mmr-lambda`（默认 0.7，=1 关闭）控制候选收敛时相关性与多样性的折中；Parent-Child 复用父块存储向量做相似度（零额外模型调用），其余策略对候选文本做一次批量 Embedding（`SemanticTextChunker.embedAll` 已 public）。
- `rag/archive`：`DocumentArchive` 按 `doc_id` 归档原文到 `rag.archive-dir`；另维护 `<archive-dir>/.archive/index.json` 摘要索引（metadata/签名/长度），`rag_list`、启动重切、全文重建只读摘要并按需 `find` 原文，避免一次性载入全部 JSON；索引缺失/版本不符/id 集合不一致时重建。`ChunkingSignature` 记录影响切分结果的配置，启动时 `ChunkingReindexRunner` 对签名不一致的文档删旧片段重建。归档目录不可写时降级关闭自动重切，不阻断入库。未归档的历史数据不会自动重切。
- `distributed`：`DistributedLock`（跨副本互斥抽象）、`LockHandle`（owner + token + 租约）、`FencedLease`（租约 + 单调 epoch 栅栏）、`LocalDistributedLock`（standalone 进程内）、`RedisDistributedLock`（`SET NX PX` + Lua 续租/释放 + `INCR <key>:epoch`）。`IndexActivityGuard` 在两种实现之间由 `DistributedLockConfig` 按 `smallrag.distributed.enabled` 选择。
- `config`：`QdrantConfig`（QdrantClient / QdrantVectorStore / 集合初始化 / 探活）、`McpServerConfig`、`McpTransportGuard`（distributed 非 STATELESS 告警）、`ClusterStartupGuard`（distributed 未声明共享归档告警）、`EmbeddingConfig`（OpenAI SDK/OkHttp 超时）、`DistributedLockConfig`、`DistributedProperties`、`ArchiveProperties`、`IndexLockHealthIndicator`、`RagProperties`、`QdrantProperties`。
- **分布式配置**：`smallrag.distributed.enabled`（默认 `false`）、`lock.lease`（30s）、`lock.renew-interval`（10s）、`lock.key-prefix`、`task-status-cache-ms`（2000）；`smallrag.archive.shared`（默认 `false`，distributed 应设 `true`，仅用于启动告警与 health）；Redis 连接走 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。distributed 模式必须配 `SMALL_RAG_RUNTIME=app-only` 且 MCP 传输为 `STATELESS`（`SMALL_RAG_MCP_PROTOCOL=STATELESS`，否则多副本轮询丢会话），多副本共享 `SMALL_RAG_ARCHIVE_DIR`；Consul 由 `CONSUL_ENABLED`（默认 `false`）开关，注册服务 `small-rag`、健康检查 `/actuator/health`、实例 id 默认 `${spring.application.name}-${HOSTNAME}-${server.port}`（多副本唯一），配置从 Consul KV 读取需 `SPRING_CONFIG_IMPORT=optional:consul:`（默认 `KEY_VALUE` 格式，每项一个 key）；Consul config watch 默认关闭（属性未加 `@RefreshScope`）。Gateway 属于 P3。
- **检索熔断**：`rag.query-breaker.*`（`RAG_QUERY_BREAKER_ENABLED` 等），只保护 `rag_search`/`rag_query`；默认窗口 20、最小调用 10、失败率 50%、打开 30s。`/actuator/health` 暴露 `instanceId`、`activeLock`、`sourcesLeader`、`archive`（enabled/writable/dir/shared）、`reindexTask`（owner/epoch/heartbeat/stateStore/lockMirror）、`queryCircuit`、profile，便于多副本排障。
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
- `application.yml` 必须：`protocol=${SMALL_RAG_MCP_PROTOCOL:STREAMABLE}`、`name=small-rag`、`version=0.0.3-SNAPSHOT`。MCP 端点 `/mcp`。payload 正文字段名共用 `RagService.CONTENT_FIELD`（"content"），改字段名必须同步。

## 代码约束

- 测试：默认 `mvn test` 不依赖外部服务；容器化 E2E 用 `mvn verify -Pcontainer-it`（Testcontainers + 真实 Qdrant/Redis，需 Docker/Podman，默认镜像 `qdrant/qdrant:latest` / `redis:7.4-alpine`，可 `-Dsmallrag.qdrant.image=...` / `-Dsmallrag.redis.image=...` 覆盖）；`*RedisIT` 需 `-Dsmallrag.redis.it=true` 且本机 Redis 6390；离线 RAG 评测在 `src/test/java/.../rag/eval/`（`EvalMetrics` + `RagPipelineEvaluationTest`）；CI 见 `.github/workflows/ci.yml`（unit + container-it 两个 job）。

- Lombok：DTO 用 `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor`，Service 用 `@RequiredArgsConstructor` 构造注入（禁 `@Autowired` 字段注入），日志 `@Slf4j`。
- **Spring Boot 4 已迁移 Jackson 3**：import 一律 `tools.jackson.*`，`com.fasterxml.jackson.*` 编译失败。
- 暂不开启 AOT/Native Image。

## Git 与文档

- **分支模型（Git Flow）**：长期分支 `main`（生产，随时可构建发布）与 `develop`（集成，功能合并目标）；短期分支 `feature/*`（从 `develop` 切出，squash 合回 `develop`）、`release/x.y.z`（从 `develop` 切出收敛发布，只修 bug，`--no-ff` 合入 `main` 并打 `vX.Y.Z` tag，再回灌 `develop`）、`hotfix/*`（从 `main` 或生产 tag 切出，修复合入 `main` 打补丁 tag 后回灌 `develop`）。禁止直接向 `main`/`develop` push；不设 `stable` 长期分支，生产版本由 `main` 上的 tag 标识。
- `.gitignore` 覆盖 `target/`、`.idea/`、`*.iml`、`.vscode/`、`*.log`、`data/`。
- 提交信息用 Conventional Commits。首次提交：`chore: bootstrap small-rag with JDK 25 + Spring Cloud 2026.0.0 + Spring AI 2.0.2`。
- **开发记忆（强制）**：每次开发都必须同步更新项目根目录的 `DEV-MEMORY.md`（不存在则创建）——简要记录本次开发所做的更改。要求：尽可能精简且实时，只保留当前有效的状态（架构、接口、行为、关键决策），过时的记忆直接覆盖或删除，不做历史归档（历史归档交给 git log）。

## 文档规范（强制）

### 1. 文档分工（单一事实来源）

| 文档 | 读者 | 写什么 | 不写什么 |
| --- | --- | --- | --- |
| `README.md` / `README.en.md` | 使用者 | 项目介绍、安装、运行、配置、MCP 工具、客户端接入、迁移 | 开发过程、内部实现细节 |
| `ROADMAP.md` | 维护者 | 版本目标、里程碑、验收、非目标 | 类名、方法、参数细节 |
| `docs/v<version>/` | 开发者 / 运维 | **当前开发版本**的规划、设计、ADR、运维手册；版本发布后冻结 | 跨版本内容、使用教程 |
| `DEV-MEMORY.md` | 开发者 / AI | 当前有效状态、坑、待办 | 历史流水、教程 |
| `AGENTS.md` | AI / 贡献者 | 硬约束、构建命令、代码规范、文档治理 | 长篇设计、各类型写作规范 |

**判定规则**：同一事实若已在某处定义，其他文档只写一句摘要并链接过去，不复制细节。`README.md` 的「文档」表登记用户可见的版本文档；模板与写作规范入口见本文件。新增用户文档必须同步登记到中英文两份表。

**版本绑定（强制）**：除 `docs/templates/` 外，`docs/` 下的每一份文档都必须位于 `docs/v<version>/` 目录中，且 front matter 的 `version` 与目录一致。**不要**在 `docs/` 根下新建 `plans/`、`design/`、`adr/`、`runbooks/`；不要为尚未开始的版本提前写文档。

### 2. README 双语规则

- `README.md` = **简体中文**（默认，GitHub 仓库首页展示）；`README.en.md` = **English**。
- 两个文件第一行必须是语言切换，且互相指向：
  - `README.md`：`**简体中文** | [English](README.en.md)`
  - `README.en.md`：`[简体中文](README.md) | **English**`
- 两份内容必须**对等**：章节顺序、表格行、命令、版本号、默认值、端口、链接目标一致，只允许语言不同。改任一份必须同步另一份。
- 两份都必须含：一句话项目介绍、徽章、目录（TOC）、版本矩阵、文档索引、快速开始、容器部署、分布式模式、配置表、MCP 工具、客户端接入、切分策略、目录导入、测试、安全、贡献、许可证。
- README 只写**使用说明**；架构、包结构、数据流、已知限制放当前版本的 `docs/v<version>/design/`，README 用链接引用。
- 相对链接以仓库根为基准；英文版链接同样指向中文文档，并在文档表下方注明“设计/规划文档为中文”。

### 3. 目录与命名

```text
docs/
├── templates/                     # 跨版本共享：只放模板与写作规范
│   ├── plan.md
│   ├── design.md
│   ├── adr.md
│   └── runbook.md
└── v0.0.3/                        # 只对应 v0.0.3 开发版本
    ├── plan.md                    # 版本规划（固定入口）
    ├── design/
    │   ├── architecture.md
    │   └── limitations.md
    ├── adr/
    │   └── 0001-spring-cloud-scope.md
    └── runbooks/                  # 运维手册（按需创建）
```

- 版本目录：`docs/v<MAJOR>.<MINOR>.<PATCH>/`，一个版本一个目录；目录内所有文档的 `version` 必须与该版本一致。
- 文件名统一 **小写 kebab-case 英文**，不含空格和中文；ADR 四位编号从 `0001` 起，只增不改。
- `docs/templates/` 是唯一跨版本共享的目录，只放模板，不放实际文档。
- **不要**再新增 `docs/README.md` 之类的“文档规范”文件。

**文档类型与模板**

| 文档类型 | 模板（含写作规范） | 存放位置 |
| --- | --- | --- |
| 版本规划 | [`docs/templates/plan.md`](docs/templates/plan.md) | `docs/v<version>/plan.md` |
| 设计 / 架构 / 限制 | [`docs/templates/design.md`](docs/templates/design.md) | `docs/v<version>/design/<topic>.md` |
| 架构决策 | [`docs/templates/adr.md`](docs/templates/adr.md) | `docs/v<version>/adr/NNNN-<topic>.md` |
| 运维手册 | [`docs/templates/runbook.md`](docs/templates/runbook.md) | `docs/v<version>/runbooks/<topic>.md` |

**写作规范只放在模板文件内**：复制模板后按文件头部的注释执行，删除注释再提交。`AGENTS.md` 只定义目录、版本绑定、front matter、生命周期和提交前检查，不再重复各类型的章节要求。

### 4. 文档头（必填）

所有 `docs/` 下的文档、`ROADMAP.md`、`DEV-MEMORY.md` 使用 YAML front matter：

```yaml
---
title: 一句话标题
status: draft | review | approved | implementing | done | superseded
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
template: docs/templates/<type>.md
related: [ROADMAP.md, DEV-MEMORY.md]
---
```

- `status` 只能取上述值；`updated` 必须是最后修改日期。
- `version` 必须与所在目录 `docs/v<version>/` 一致；`related` 用相对仓库根目录的路径。
- `docs/` 下的文档必须含 `template`，指向实际使用的模板；`ROADMAP.md` / `DEV-MEMORY.md` 不需要。
- 发布后不再更新的文档标 `done`；被新文档取代的标 `superseded` 并在正文首行写明替代链接。

### 5. 生命周期

```text
draft → review → approved → implementing → done
                                   ↘ superseded
```

- 新版本开始：创建 `docs/vX.Y.Z/`，从模板生成 `plan.md`，状态 `draft`；不要提前为后续版本建目录。
- 开发期间：该版本的规划、设计、ADR、运维手册只在该目录维护；每次修改同步更新 `updated`。
- 方案确认：改为 `approved`，并把关键决策同步到 `ROADMAP.md`。
- 开始编码：改为 `implementing`。
- 版本发布：改为 `done`，要点合并进 `ROADMAP.md`；该版本目录**冻结**，后续只允许事实性修正。
- 下一版本开始：新建 `docs/vX.Y.Z+1/`，不修改已冻结的上一版本目录；历史由版本目录 + `git log` 共同保留。
- 被替代的文档标 `superseded` 并在正文首行写明替代链接；不要直接删除已发布版本的文档。

### 6. 提交前检查

- [ ] front matter 完整，`updated` 是今天，`version` 与所在 `docs/v<version>/` 目录一致
- [ ] `docs/` 下的文档含 `template`，且内容符合该模板头部的写作规范
- [ ] 文档位于当前开发版本的目录，没有在 `docs/` 根下新建 `plans/` / `design/` / `adr/` / `runbooks/`
- [ ] 数字、端口、命令与代码一致
- [ ] 没有复制其他文档已有的细节
- [ ] 相对链接可打开
- [ ] 中英文 README 内容对等（章节、表格、命令、版本号）

## 验收标准

1. `mvn clean package -DskipTests` 成功；`mvn test` 无外部依赖可跑。
2. 启动后 `/mcp` 可用，MCP Inspector 能列出全部 13 个工具。
3. 空知识库下 `rag_query` 返回友好提示而非异常堆栈（依赖 count 短路）。
4. 端到端（入库→检索）需要 Qdrant + OpenAI 兼容 Embedding；不需要 Chat 模型。
5. README 步骤可复现，`git log` 至少一条 Conventional Commits。
