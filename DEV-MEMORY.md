---
title: DEV-MEMORY
status: implementing
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
related: [AGENTS.md, ROADMAP.md, docs/v0.0.3/plan.md]
---

# DEV-MEMORY — 当前开发状态

> 只记录**当前有效**的状态；过时内容直接覆盖 / 删除，历史看 `git log`。
> 编写规范见 [`AGENTS.md`](AGENTS.md)。

## 1. 当前阶段

- 版本：`0.0.3-SNAPSHOT`，开发分支 `develop`。
- 上一版：`v0.0.2`（2026-11-07，`main` tag，双远程已推送）。
- 主题：**单机与集群**。
- 规划：[`docs/v0.0.3/plan.md`](docs/v0.0.3/plan.md)。
- 里程碑：M1（WP1 + WP2）→ M2（WP3 + WP5）→ M3（WP4 + WP6）。
- 下一步：`feature/cluster-safety`（WP1 集群会话 + WP2 fencing）。
- 文档：`README.md`（简体中文）/ `README.en.md`（English）双语；版本文档统一放在 `docs/v0.0.3/`；模板与写作规范在 `docs/templates/`。

**已确认决策（2026-11-09）**

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 范围 | M1 + M2 + M3 全做 |
| D2 | 集群传输 | distributed 默认 `STATELESS`；standalone 保持 `STREAMABLE` |
| D3 | 幂等改造 | 本期做：chunk id 确定性化，接受一次全量重切 |
| D4 | 容器化测试 | 纳入 CI：Testcontainers E2E 在 CI 跑 |

## 2. 构建与验证

- JDK 25（`maven.compiler.release=25`）；本机 `mvn -v` 已指向 JDK 25，若默认不是 25 用 `JAVA_HOME` 指定。
- 构建：`mvn clean package`；单测：`mvn test`（默认不依赖外部服务，**154** 个测试全绿，`*RedisIT` 跳过）。
- Redis 集成测试：`redis-server --port 6390 --save '' --appendonly no --daemonize yes`，再 `mvn test -Dtest='*RedisIT' -Dsmallrag.redis.it=true`。
- 切分断点校准：`mvn test -Dtest=BreakpointPercentileCalibration -Dcalibrate=true`（需本机 OpenAI 兼容 Embedding，可用 `EMBEDDING_*` 覆盖）。
- 镜像：`podman compose -f compose.yaml build small-rag` 冷构建 **126s**，产出 `small-rag:0.0.3`（649 MB）。
- 冒烟：**app-only**（`--network host`、`SMALL_RAG_ENSURE_EMBEDDING_MODEL=false`）health UP + MCP `initialize` + 13 工具；**all-in-one** qdrant `/readyz` ready + app health UP。

## 3. 运行形态

- `SMALL_RAG_RUNTIME=all-in-one`（默认）：supervisord 三进程（qdrant / llama-server / app），只暴露 **18080**；Qdrant 6333/6334 与 Embedding 11434 绑定 `127.0.0.1`。
- `SMALL_RAG_RUNTIME=app-only`：只跑应用（`docker/supervisord-app-only.conf`），依赖外部提供；distributed 必须用这个。
- `docker/entrypoint.sh` 按 `SMALL_RAG_RUNTIME` 选配置；`start-app.sh` 在 app-only 下跳过本地 Qdrant 等待。
- 镜像：多阶段构建，以 `qdrant/qdrant` 为基底从源码编译 llama-server（`LLAMA_CPP_TAG=v0.9.0`），再拷入 Temurin JRE。不用 Ollama 镜像。
- 推理运行时 = **llama.cpp**：`start-llama.sh` 从 ModelScope 下载 GGUF（断点续传），以 `--embeddings --pooling last` 启动；Qwen3-Embedding 必须 last pooling。模型落 `/data/models/`。
- 源目录只读挂载到 `/data/sources/...`，需容器内 UID `10001` 可读；`compose.sources.yaml` + `.env` 的 `SMALL_RAG_SOURCE_HOST` 提供单根挂载。

## 4. 架构要点

### 4.1 切分

- 策略 4 种：`fixed` / `recursive` / `semantic` / `parent-child`（默认）。`markdown` 不是策略，`MarkdownChunker` 是 semantic 与 parent-child 共用的内部引擎。
- semantic = 标题分节 + 节内文本块与累积质心比较合并；代码 / 表格 / 数学块独立成块；超长文本块回落 `SemanticTextChunker` 句子级切分。
- 长度度量 = 全宽字符当量（`TextWeights`：CJK=1、拉丁=0.25）；英文硬切回退单词边界。
- `SEMANTIC_BREAKPOINT_PERCENTILE`：技术文档语料 0.1–0.4 输出一致，比绝对阈值 0.75 少约 5% 碎块；推荐 `0.2`，默认 0（绝对阈值）。
- Parent-Child：父块独立存储（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)`），子块只带 `parent_id`；检索按 id 取回父块，缺失时回退子块原文。
- MMR：`rag.mmr-lambda` 默认 0.7（=1 关闭）；候选 topK×4 → MMR → topK；Parent-Child 复用父块向量，零额外模型调用。
- MarkdownChunker 取舍：围栏 / 缩进代码内的 `#` 不误判标题；未闭合围栏吞到文末；段落后 `---` 按 Setext H2；超长表格重复表头、超长代码补围栏、数学块补 `$$`。

### 4.2 检索与存储

- `rag_search` 可选 `expand`（默认 true；false 返回原始子块且不做 MMR）；`maxChunkChars=1200` / `maxContextChars=12000` 按全宽字符当量。
- payload 正文键 `RagService.CONTENT_FIELD="content"`（`QdrantConfig` 同步引用）。
- `Document` id 必须是 UUID，业务 id 放 metadata 的 `doc_id`；`doSimilaritySearch` 先调 Embedding 再查库，空库用 `KnowledgeBaseProbe`（count）短路。
- 归档：`DocumentArchive` 按 `doc_id` 存原文到 `rag.archive-dir`，另有 `.archive/index.json` 摘要索引；索引缺失 / 版本不符 / id 集合不一致时重建。目录不可写时降级关闭自动重切。
- 包结构：`rag/`（RagService、IndexActivityGuard、QueryCircuitBreaker、KnowledgeBaseProbe）+ `rag/reindex/` + `rag/chunking/` + `rag/archive/` + `rag/sources/` + `distributed/`。

### 4.3 重建任务

- `rag_rebuild` 与 `rag_reindex`（不传 documentId）为异步任务，立即返回 `taskId`；单篇 `rag_reindex(documentId)` 同步。
- `rag_task_status` 返回 `status`（queued / clearing / reindexing / interrupted / completed / aborted / cancelled / failed）、进度、`stalled`、`cancelRequested`、`revoked`、`lockReleaseAt` 等。
- `ReindexTaskService`：每任务独立 daemon 线程 + 单任务互斥 + 保留最近 20 个快照。
- 状态持久化：`ReindexStateStore` + `QdrantReindexStateBackend` 写入独立集合 `<collection>_reindex_state`（1 维 Dot 哑向量 + JSON payload）；含 `cleared` 与 `completedDocumentIds` 检查点。状态跟随 Qdrant，不依赖容器文件系统。
- 重启：未完成且未撤销 / 未取消的任务标 `interrupted` 并继续持锁；`REINDEX_RESUME_ON_STARTUP=true` 时自动续接，`false` 时等 `rag_task_resume` / `rag_task_cancel`。
- 看门狗：每 30s 检查；超过 1800s 无进度只标 `stalled` 并保留锁；超过 3600s 撤销写权限 + 取消在途 Embedding + 中断线程，10s 内退出则释放锁，否则 120s 宽限期后强制释放并置 `failed`。
- `RagService.reindexArchived(clearFirst, listener, skipDocumentIds)` 逐篇回调检查点；清空前校验归档非空，清空后重读归档列表。

### 4.4 目录导入

- `rag.sources.*`：`enabled`、`directories`（`label=/path`）、`extensions`、`exclude`、`skip-hidden`、`follow-symlinks`、`scan-on-startup`、`scan-interval-seconds`（默认 300，0 关闭）、`watch-enabled`、`watch-debounce-seconds`、`max-file-bytes`、`max-files-per-scan`、`delete-missing`、`delete-orphan-roots`、`state-file`、`metadata`。
- 文档 id = `UUID.nameUUIDFromBytes("small-rag-source:" + label + "::" + 相对路径)`；label 稳定则目录移动不换 id，label 重复拒绝扫描。
- 变更检测：先比 `mtime + size`，再比 SHA-256；内容变化才 `RagService.upsert`。
- 触发：启动扫描、定时扫描、`WatchService`（递归注册、防抖、OVERFLOW 全量、失败退化定时）、MCP `rag_sync_sources(force)` 异步。
- 状态：`SourceStateStore` 原子写 `<archive-dir>/.sources/state.json`（指纹 + 墓碑）；丢失 / 损坏时从归档 `source_*` 元数据重建。
- 删除：`delete-missing` 只删除“当前仍配置且本轮成功扫描”的根目录下已消失文件；`delete-orphan-roots` 按 label 清理已移除根目录（默认关闭）。`rag_delete` 写墓碑，内容不变不再导入。
- 互斥：`IndexActivityGuard`（`reindex:<taskId>` / `sources`）串行化重建与导入。

### 4.5 分布式与锁

- 开关：`smallrag.distributed.enabled`（默认 false）。**不是 Spring profile**，由 `SMALL_RAG_DISTRIBUTED_ENABLED` + `compose.distributed.yaml` 开启。
- 锁配置：`lock.lease`（30s）、`lock.renew-interval`（10s，下限 10ms）、`lock.key-prefix`；Redis 走 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。
- `DistributedLock`：`tryAcquire` / `renew` / `release` / `isHeld` / `currentOwner`；`LockHandle` 带 owner + token + 本地到期时间，按 value 匹配，旧持有者不会误删。
- `IndexActivityGuard`：持锁期间后台续租；续租失败即 `lockLost=true` 并停止续租；`isHeldBy` 每次向锁存储远程校验 owner + token。重建、导入、启动重切在每次写入前校验。`@PreDestroy` 只停止续租，不主动释放。
- `SourcesLeaderElection`：竞争 `sources-leader` 租约；启动 / 定时 / watcher 自动扫描仅 leader 执行，手动 `rag_sync_sources` 任意副本可发起。
- `compose.distributed.yaml`：外部 Qdrant / Redis / Consul / Embedding + HAProxy gateway（`docker/haproxy.cfg`）；应用端口用 `!reset []` 去掉；`small_rag_data` 为共享归档卷；需 Compose v2.24+。
- Consul：`CONSUL_ENABLED`（默认 false）同时控制 discovery / config；注册 `small-rag`，健康检查 `/actuator/health`；`instance-id` 默认 `${spring.application.name}-${HOSTNAME}-${server.port}`。KV 默认 `KEY_VALUE`（每项一个 key），启动加 `SPRING_CONFIG_IMPORT=optional:consul:`；config watch 默认关闭。
- Spring Cloud 范围：只保留 Consul discovery / config（可选）；`consul-discovery` 已排除未使用的 `spring-cloud-starter-loadbalancer`。不引入 Gateway / Config Server / Bus / Vault / K8s / Feign / CircuitBreaker 包装，理由见 [`docs/v0.0.3/adr/0001-spring-cloud-scope.md`](docs/v0.0.3/adr/0001-spring-cloud-scope.md)。
- 已知边界（v0.0.3 待改）：任务快照锁仍是单实例读后写、无 fencing token；`rag_task_status` / `rag_task_resume` 只能看到本实例内存中的任务；HAProxy `roundrobin` 与 `STREAMABLE` 会话不兼容。

### 4.6 健康与熔断

- `QueryCircuitBreaker`（`rag.query-breaker.*`）只包 `rag_search` / `rag_query`；默认窗口 20、最小调用 10、失败率 50%、打开 30s、半开 3 次；打开时抛 `QueryUnavailableException`，入库 / 重建不受影响。
- `/actuator/health` 只暴露 health；`IndexLockHealthIndicator` 输出 `instanceId`、`distributed`、`profiles`、`activeLock`、`sourcesLeader`、`queryCircuit`、`queryFailureRate`。Redis 健康检查由 data-redis 自动提供（仅 distributed 开启）。
- Spring Boot 4 包名：`org.springframework.boot.health.contributor.Health/HealthIndicator`（不是 `org.springframework.boot.actuate.health`）。

## 5. 关键约束与坑

- **Spring 多构造器 bean** 必须给 Spring 用的构造器加 `@Autowired`（`IndexActivityGuard`、`QueryCircuitBreaker`、`ReindexStateStore`），否则 Spring 7.1 RC 报 “No default constructor found”；`mvn test` 不覆盖上下文启动，只有 jar 冒烟能发现。
- **Jackson 3**：import 一律 `tools.jackson.*`；`com.fasterxml.jackson.*` 编译失败。
- **Lombok**：JDK 23+ 需 `<maven.compiler.proc>full</maven.compiler.proc>`。
- **依赖源**：`pom.xml` 首位 `aliyun-public`，`spring-milestones` 回退；容器构建用 `docker/maven-settings.xml`（`mirrorOf=*,!spring-milestones`）。本机 `~/.m2/settings.xml` 若有 `mirrorOf=*` 会覆盖 pom。
- **Qdrant**：gRPC 端口 6334（REST 6333）；`Document` id 必须 UUID；集合初始化 fail-soft，维度不一致 fail-fast；gRPC deadline 用 `rag.qdrant.request-timeout-ms`（默认 30s）。
- **状态集合隔离**：重建状态放 `<collection>_reindex_state`，不要并入主集合，否则污染空库探活、检索与 `rag_rebuild` 清理。
- **MCP**：`McpSyncServerCustomizer` 是单例且已被 WebMVC 自动配置占用，不要自定义；instructions 等走 `spring.ai.mcp.server.*`。
- **Dockerfile** 无 `# syntax=` 指令（国内拉不到 docker.io frontend 镜像）；`docker/*.sh` 必须 LF（`.gitattributes` 已强制）。
- **`.env`** 直接入库，只放模板值；真实密钥禁止写入。
- **切分签名**：`ChunkingSignature` 版本与项目版本同步；只改切分算法而不升版本，旧索引不会自动重切。
- **Embedding 超时**：用 OpenAI SDK / OkHttp（`EMBEDDING_CONNECT_TIMEOUT_MS=10000`、`EMBEDDING_READ_TIMEOUT_MS=120000`）；`spring.http.client.*` 不生效。`CancellationScope` 通过 `ThreadLocal` 登记在途 `Call`，撤销时立即 cancel。
- **归档摘要索引**：内容陈旧但 id 集合一致时可能保留旧签名。
- **目录导入**：watcher 受 inotify 限制，失败退化定时扫描；label 变化生成新 id，旧 label 需 `delete-orphan-roots=true` 清理；`lastRun` 不持久化。
- **已知纰漏**：`rag_ingest` 失败只做尽力清理；单篇 reindex 写入窗口内可能短暂重复；`rag_rebuild` 只清 `chunk_type=chunk/child/parent`，无 `chunk_type` 的旧片段不清。

## 6. 待办

- v0.0.3：按规划完成 WP1–WP6（见 plan），优先 M1。
- P3：Spring Cloud Gateway 仍可选，不阻塞本版本。
- 真实语料端到端验证 MMR 效果（需 Qdrant + 实际知识库人工评估）。
- 混合主题语料校准 `SEMANTIC_BREAKPOINT_PERCENTILE`（当前校准语料同质，分布扁平）。

## 7. 文档索引

| 文档 | 用途 |
| --- | --- |
| [`README.md`](README.md) | 安装、运行、配置、MCP 工具、迁移（简体中文） |
| [`README.en.md`](README.en.md) | 英文版使用说明 |
| [`ROADMAP.md`](ROADMAP.md) | 版本目标、里程碑、验收 |
| [`docs/v0.0.3/plan.md`](docs/v0.0.3/plan.md) | v0.0.3 规划 |
| [`docs/v0.0.3/design/architecture.md`](docs/v0.0.3/design/architecture.md) | 架构、包结构、关键数据流 |
| [`docs/v0.0.3/design/limitations.md`](docs/v0.0.3/design/limitations.md) | 已知限制与风险 |
| [`docs/v0.0.3/adr/0001-spring-cloud-scope.md`](docs/v0.0.3/adr/0001-spring-cloud-scope.md) | Spring Cloud 组件范围决策 |
| [`docs/templates/plan.md`](docs/templates/plan.md) | 版本规划模板（含写作规范） |
| [`docs/templates/design.md`](docs/templates/design.md) | 设计 / 架构 / 限制模板（含写作规范） |
| [`docs/templates/adr.md`](docs/templates/adr.md) | 架构决策模板（含写作规范） |
| [`docs/templates/runbook.md`](docs/templates/runbook.md) | 运维手册模板（含写作规范） |
| [`AGENTS.md`](AGENTS.md) | 版本基线、构建命令、代码约束 |
