# DEV-MEMORY.md — 开发记忆

> 每次开发同步更新：只记录当前有效的状态，过时内容直接覆盖/删除，不做历史归档（历史看 git log）。

## 分支模型（Git Flow）

- 长期分支：`main`（生产，随时可构建；发布打 `vX.Y.Z` tag）、`develop`（集成开发）。
- 短期分支：`feature/*`（从 `develop` 切出，squash 回合 `develop`）、`release/x.y.z`（从 `develop` 切出收敛发布，`--no-ff` 合 `main` 打 tag 后回灌 `develop`）、`hotfix/*`（从 `main`/生产 tag 切出，合 `main` 打补丁 tag 后回灌 `develop`）。
- 不设 `stable` 长期分支；生产版本由 `main` 上的 tag 标识。
- 禁止直接 push 到 `main`/`develop`；生产镜像 tag 与 git tag 对齐。

## v0.0.2 目标（进行中）

- 保持 standalone 单容器默认运行不变；新增可选 `distributed` 模式：外部 Qdrant/Redis/Consul/Embedding + 多副本。
- **P0 已完成**：`DistributedLock`（进程内 / Redis `SET NX PX` + Lua）、`IndexActivityGuard` 后台续租与丢失即停写、双运行模式（`SMALL_RAG_RUNTIME=all-in-one|app-only`）、`compose.distributed.yaml` + HAProxy gateway。
- **P1 已完成**：Resilience4j 检索熔断、`/actuator/health` 诊断、Consul discovery/config（默认关闭，`CONSUL_ENABLED` 打开）。
- 待做：P2 任务 owner/lease/fencing、leader-only 扫描；P3 可选 Gateway；P4 RAG 评测与 Testcontainers。
- 详细里程碑与验收见 `ROADMAP.md`。

## 项目状态（2026-11-07）

- 版本：`0.0.2-SNAPSHOT`（pom.xml、application.yml MCP server version、README docker tag、ChunkingSignature 签名版本四处统一；签名版本与项目版本同步，但切分算法/存储布局变更需单独提升）。
- 构建：本机默认 JDK 21 编不了 release=25，用 `JAVA_HOME='D://java//jdk-27.0.1.0.3' mvn clean package`（JDK 27）；141 测试全绿（`IndexActivityGuardRedisIT` 默认不参与）。
- 启动冒烟（2026-11-07）：`java -jar` 无 Qdrant/Redis/Embedding 也能启动，`/actuator/health` UP、MCP `initialize` 正常；standalone 下 Redis health indicator 关闭（`management.health.redis.enabled` 跟随 distributed 开关），distributed=true 且 Redis 在线时 health 显示 redis UP。
- Spring 多构造器 bean 必须给 Spring 用的构造器加 `@Autowired`（`IndexActivityGuard`、`QueryCircuitBreaker`、`ReindexStateStore`）；否则 Spring 7.1 RC 报 "No default constructor found"（`mvn test` 不覆盖上下文启动，只有 jar 冒烟能发现）。
- Dockerfile 无 `# syntax=` 指令（国内网络拉不到 docker.io 的 frontend 镜像；所用特性内置 BuildKit 全支持）；compose/.env 镜像 tag 随项目版本走 `APP_IMAGE_TAG`。
- 配置文件：`.env` 直接入库（由 .env.example 改名，仅模板值，.gitignore 已取消忽略；真实密钥禁止写入，.dockerignore 仍排除）。
- 容器迁移端到端验证通过（2026-11-05）：镜像构建 24 分钟（含首次 Maven 依赖下载与 llama.cpp 编译）；三进程（qdrant/llama-server/app）全部就绪；容器内 llama.cpp 与宿主机 Ollama 同文本向量余弦 = 1.0000（pooling last 正确）；MCP 全链路 rag_ingest→rag_query→rag_search(expand=false)→rag_delete 冒烟通过。**教训：docker/*.sh 工作区曾出现 CRLF 导致 exec ENOENT，已加 .gitattributes 强制 LF**。
- 容器推理运行时 = **llama.cpp**（0.0.1 起替换 Ollama）：以 qdrant 镜像为基底从源码编译 llama-server（`LLAMA_CPP_TAG=v0.9.0`），`start-llama.sh` 下载 GGUF（ModelScope，断点续传）并以 `--embeddings --pooling last` 启动（Qwen3-Embedding 必须 last pooling）；`SMALL_RAG_ENSURE_EMBEDDING_MODEL=true` 时 `start-app.sh` 等 `/health`。模型落 `/data/models/`。Ollama 仍可用于本机开发（README 本地运行节）。
- 手动校准工具：`src/test/java/.../calibration/BreakpointPercentileCalibration.java`，运行 `mvn test -Dtest=BreakpointPercentileCalibration -Dcalibrate=true`（需本机 OpenAI 兼容 Embedding 服务，`EMBEDDING_BASE_URL`/`EMBEDDING_MODEL`/`EMBEDDING_API_KEY` 可覆盖默认值）。

## 切分体系（当前有效）

- 策略 4 种：`fixed` / `recursive` / `semantic` / `parent-child`（默认）。`markdown` 不是策略——`MarkdownChunker` 是内部引擎，由 semantic 与 parent-child 共用。
- semantic = Markdown 分节 + 节内文本块与累积质心比较合并（`MarkdownChunker.chunkSemantic`）；代码/表格/数学块独立成块；超长文本块回落 `SemanticTextChunker` 句子级切分；尾块回收。
- 长度度量 = 全宽字符当量（`TextWeights`：CJK=1、拉丁=0.25）；英文硬切回退单词边界；句界英文缩写白名单。
- SEMANTIC `breakpoint-percentile`：真实语料校准结论（Qwen3-Embedding，2026-11-05）——技术文档语料上 0.1~0.4 输出完全一致，比绝对阈值 0.75 少约 5% 碎块（57 vs 60 块，p90 388 vs 312），参数鲁棒；技术文档推荐 `0.2`，默认仍为 0（绝对阈值）。混合主题语料上分位数模式优势更明显。
- Parent-Child **父块独立存储**（0.0.1 起）：父块作为独立 point（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)` 确定性 UUID）与子块同集合写入；子块只带 `parent_id`，**不再有 `parent_text`**；检索按 id 取回父块（payload `content` 字段 + 向量），父块缺失/取回失败回退子块原文。`rag_rebuild` 清理 chunk/child/parent 三类。
- 检索 MMR：`rag.mmr-lambda`（默认 0.7，=1 关闭）；候选 topK×4 → MMR（λ·score−(1−λ)·maxSim）→ topK；Parent-Child 复用父块存储向量（零额外模型调用），其余策略对候选文本批量 Embedding。
- MarkdownChunker 边界行为：围栏/缩进代码内的 `#` 不会误判为标题（回归测试锁定）；未闭合围栏吞到文末（符合规范）；段落后 `---` 按 Setext H2 解析（已知取舍）。
- 超长表格切片重复表头；超长 fenced code 切片补围栏；数学块补 `$$`。

## 检索与存储

- **重建进度与看门狗**：`rag_rebuild` 与 `rag_reindex`（不传 documentId）为**异步任务**，立即返回 `taskId`；`rag_task_status(taskId?)` 查询 `status`（queued/clearing/reindexing/interrupted/completed/aborted/cancelled/failed）、`total/processed/percent/succeeded/failed/currentDocumentId/lastProgressAt/stalled/cancelRequested/startedAt/finishedAt/error/revoked/lockReleaseAt`；`rag_task_cancel(taskId?)` 对运行中任务是文档边界协作式取消，对 `interrupted` 任务立即放弃并释放锁；`rag_task_resume(taskId?)` 续接 `interrupted` 任务。`ReindexTaskService` = 每任务独立 daemon 线程 + 单任务互斥 + 保留最近 20 个快照。单篇 `rag_reindex(documentId)` 仍同步。
- **状态持久化与重启续接**：`ReindexStateStore`（门面）+ `QdrantReindexStateBackend` 把任务快照（含 `cleared` + `completedDocumentIds` 检查点，JSON 字符串）与任务锁写入 Qdrant 独立集合 `<rag.qdrant.collection-name>_reindex_state`：1 维 Dot 哑向量 + payload，集合按需创建；任务 point id 由 taskId 确定性生成，锁为固定 id 的单 point。状态跟随 Qdrant 持久化，不依赖应用容器文件系统；读失败按“无历史任务/无锁”处理，写失败由调用方告警降级。重启时加载全部快照；锁持有者未撤销/未取消则标记 `interrupted` 并**继续持有任务锁**，默认 `REINDEX_RESUME_ON_STARTUP=true` 时由 `ApplicationReadyEvent` 自动续接：`clearFirst = rebuild && !cleared`，跳过 `completedDocumentIds` 并计入成功；`false` 时停在 `interrupted` 等手动 `rag_task_resume`（或 `rag_task_cancel` 放弃）。优雅关机（`@PreDestroy`）会撤销写入、中断线程，但把状态覆盖为 `interrupted` 并保留任务锁，供重启续接；硬超时已撤销的任务不会续接。若 Qdrant 数据/集合名变化导致状态丢失：切分配置变化时 `ChunkingReindexRunner` 会按归档签名补建；但同签名 `rag_rebuild` 中断后剩余文档不会自动补建，需重新执行。
- 看门狗：每 `REINDEX_CHECK_INTERVAL_SECONDS`（30s）检查；超过 `REINDEX_STALL_TIMEOUT_SECONDS`（1800s）无进度只置 `stalled` 并告警、**保留锁**；超过 `REINDEX_HARD_TIMEOUT_SECONDS`（3600s）执行 `revoke()`：撤销写权限（`ReindexListener.isWritable()=false`，`RagService` 在每次 `delete/add` 前校验并抛 `ReindexRevokedException`）+ `CancellationScope.cancel()` 取消在途 Embedding 调用 + `interrupt`。线程在 `REINDEX_JOIN_TIMEOUT_SECONDS`（10s）内退出则立即释放锁；否则进入 `REINDEX_RECOVERY_GRACE_SECONDS`（120s，且 ≥ Qdrant deadline×2）宽限期，到期强制释放任务锁并置 `failed`。因此**卡死不再需要重启**；宽限期内提交新任务返回“已撤销，正在释放任务锁”。硬超时任务最终状态为 `failed`。
- 锁覆盖范围（已知限制）：MCP 全量重建/全量 reindex、目录导入与启动自动重切通过 `IndexActivityGuard` 互斥；单篇 reindex、`rag_update`/`rag_ingest` 不经过它。standalone 为进程内锁；distributed 下经 `DistributedLock` 走 Redis 租约锁跨副本互斥，每次写入前校验 `isHeldBy`，续租失败立即停写。任务状态集合里的锁仍是单实例读后写，fencing token 与 owner 接管留 P2。
- **调用超时与主动取消**：Embedding 用 OpenAI SDK/OkHttp 超时（`EmbeddingConfig`：`EMBEDDING_CONNECT_TIMEOUT_MS=10000`、`EMBEDDING_READ_TIMEOUT_MS=120000`，read 同时作为 write 与单次请求总超时；`spring.http.client.*` 不生效）；同一 customizer 注册 `CancellationScope.interceptor()`，通过 `ThreadLocal` 把任务线程的在途 `okhttp3.Call` 登记到作用域，撤销时 `Call.cancel()` 立即打断。Qdrant gRPC 默认 deadline 改为 `QDRANT_REQUEST_TIMEOUT_MS=30000`（`init-timeout-ms` 只用于启动探活）。
- `RagService.reindexArchived(clearFirst, listener, skipDocumentIds)` 逐篇上报 `ReindexProgress`，并在 `onCleared()` / `onDocumentFinished()` 回调中落检查点；`rag_reindex`/`rag_rebuild` 同步入口保留（NOOP listener）。清空前校验归档非空，清空后**重读**归档列表以缩小漏重建窗口。`RagService.upsert(documentId, text, metadata)` 供目录导入使用：不存在则创建、存在则覆盖，metadata 为 null 时沿用已有元数据。
- `rag_search` 可选 `expand`（默认 true，false 返回原始子块且不做 MMR）；`buildContext` 预算超限 continue 跳过；`maxChunkChars=1200` / `maxContextChars=12000` 按全宽字符当量计量。
- 包结构：`rag/`（RagService、IndexActivityGuard、QueryCircuitBreaker、QueryUnavailableException、KnowledgeBaseProbe）+ `rag/reindex/`（ReindexTaskService、ReindexStateStore、ReindexStateBackend、QdrantReindexStateBackend、ReindexProgress、ReindexListener、ReindexRevokedException、CancellationScope、ChunkingReindexRunner）+ `rag/chunking/`（8 类）+ `rag/archive/`（DocumentArchive、ArchivedDocument、ArchivedDocumentSummary）+ `rag/sources/`（SourceSyncService、SourceStateStore）+ `distributed/`（DistributedLock、LockHandle、LocalDistributedLock、RedisDistributedLock）；payload 正文键 `RagService.CONTENT_FIELD="content"`（QdrantConfig 同步引用）。

## 目录导入（多目录 Markdown）

- `rag.sources.*`：`enabled`、逗号分隔 `directories`（支持 `label=/path`，label 默认取目录名）、`extensions`（默认 `.md,.markdown`）、`exclude` glob、`skip-hidden`、`follow-symlinks`、`scan-on-startup`、`scan-interval-seconds`（默认 300，0 关闭）、`watch-enabled`（默认 true）、`watch-debounce-seconds`（默认 5）、`max-file-bytes`（默认 2 MiB）、`max-files-per-scan`（0 不限）、`delete-missing`、`delete-orphan-roots`、`state-file`（默认 `<archive-dir>/.sources/state.json`）、`metadata`。
- `SourceSyncService`：文档 id = `UUID.nameUUIDFromBytes("small-rag-source:" + label + "::" + 相对路径)`；label 稳定则目录移动不换 id，label 重复拒绝扫描。先比 `mtime + size`，再比 SHA-256；内容变化才 `RagService.upsert`（归档 + 切分 + Embedding），未变化只更新时间戳。跳过隐藏目录/文件、非配置扩展名、排除 glob、超大文件、空白文件。
- 触发：`ApplicationReadyEvent` 后启动扫描（后台线程）；`scan-interval-seconds` 固定延迟扫描；`watch-enabled` 时用 `WatchService` 监听（递归注册、事件防抖、OVERFLOW 触发全量扫描，失败自动退化为定时扫描）；MCP `rag_sync_sources(force)` **异步**触发，`rag_sync_status` 返回 enabled/running/queued/directories/lastTrigger/lastRun/lastError。`force=true` 忽略指纹且不再受 `max-files-per-scan` 限制。
- 状态：`SourceStateStore` 原子写 JSON（`files` + `ignored` 墓碑）；丢失/损坏时从归档 `source_key`/`source_root`/`source_root_path`/`source_file`/`source_size`/`source_mtime`/`source_sha256` 元数据重建。旧状态 `root` 为绝对路径时自动迁移为 `rootPath + label`。状态文件放在 `.sources/` 子目录，避免被归档扫描当成原文。
- 删除：`delete-missing=true` 时只删除“当前仍配置且本轮成功扫描（无访问失败）”的根目录下已消失文件；根目录暂时不可读不会误删。`delete-orphan-roots=true` 时按 label 删除已从配置移除的根目录下文档（默认关闭）。`rag_delete` 会移除状态并写墓碑（记录删除时的 SHA-256），源文件内容不变时不再重新导入；内容变化后墓碑失效并重新导入。
- 互斥：`IndexActivityGuard`（`reindex:<taskId>` / `sources`）串行化重建与导入；重建运行中定时/watcher 扫描跳过、手动同步报 busy；导入运行中提交重建报“目录导入正在执行”。`interrupted` 重建任务继续持锁，也会阻止导入，直到 resume/cancel。
- 容器：源目录只读挂载到 `/data/sources/...`，需 UID 10001 可读；`compose.sources.yaml` + `.env` 的 `SMALL_RAG_SOURCE_HOST`（`make up-sources`）提供单根挂载，分散目录用多条 `docker run -v`；`SMALL_RAG_SOURCE_DIRS` 是容器内路径。

## 分布式模式（P0/P1 已实现）

- 配置：`smallrag.distributed.enabled`（默认 false）、`lock.lease`（30s）、`lock.renew-interval`（10s，下限 10ms）、`lock.key-prefix`；Redis 走 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`。`DistributedLockConfig` 按开关装配 `RedisDistributedLock` 或 `LocalDistributedLock`。
- `DistributedLock` 语义：`tryAcquire(key, owner, lease)` / `renew` / `release` / `currentOwner`；`LockHandle` 携带 owner+token+本地到期时间，续租/释放按 value 匹配，旧持有者不会误删新锁。
- `IndexActivityGuard`：持锁期间后台每 `renew-interval` 续租；续租失败或异常 → `lockLost=true`、清空 owner、停止续租；`isHeldBy(label)` 返回 false 后重建任务（`Task.isWritable()`）、目录导入、启动重切立即停止写入。`@PreDestroy` 只停止续租，不主动释放租约，避免优雅停机时过早接管。
- 容器：`SMALL_RAG_RUNTIME=all-in-one`（默认，supervisord 三进程）或 `app-only`（`docker/supervisord-app-only.conf` 只跑应用）；`docker/entrypoint.sh` 选择配置；`start-app.sh` 在 app-only 下跳过本地 Qdrant 等待。
- Overlay：`compose.distributed.yaml` 引入外部 Qdrant/Redis/Consul/Embedding + HAProxy gateway（`docker/haproxy.cfg`，`server-template` 发现副本），应用端口用 `!reset []` 去掉；需 Compose v2.24+。`small_rag_data` 为多副本共享归档卷。Consul 为单节点 server（`bootstrap-expect=1` + `consul_data` 卷）。
- Consul：`CONSUL_ENABLED`（默认 false）同时控制 discovery/config；注册服务 `small-rag`，健康检查 `http://<ip>:18080/actuator/health`（15s）。`CONSUL_CONFIG_FAIL_FAST=false`；config watch 默认关闭（`CONSUL_CONFIG_WATCH_ENABLED`），因为 `RagProperties` 未加 `@RefreshScope`。
- Consul KV 默认 `KEY_VALUE` 格式：**每项一个 key**，如 `config/small-rag/logging.level.com.smallrag.ragmcp=DEBUG`；`config/small-rag/data` 只在 `format=YAML/PROPERTIES` 时使用。启动加 `SPRING_CONFIG_IMPORT=optional:consul:`；不设置 import 时靠 `import-check.enabled=false` 避免启动检查报错。
- 已知边界：仍保留每副本的启动/定时扫描触发，由锁串行化；任务接管（fencing）与 leader-only 扫描未接入。
- 集成验证：`IndexActivityGuardRedisIT` 默认跳过；本机 `redis-server --port 6390 --save '' --daemonize yes` 后 `mvn test -Dtest=IndexActivityGuardRedisIT -Dsmallrag.redis.it=true` 验证跨副本互斥、租约续期与持有者失联后的接管。Consul 已验证注册、健康检查 passing、停止后自动注销、KV 配置注入。

## 检索熔断与健康检查

- `QueryCircuitBreaker`（Resilience4j，`rag.query-breaker.*`）：只包住 `RagService.search`，即 `rag_search`/`rag_query`；默认窗口 20、最小调用 10、失败率 50%、打开 30s、半开 3 次。打开时抛 `QueryUnavailableException`，两个 MCP 工具返回可读提示；入库/重建不经过熔断。`RAG_QUERY_BREAKER_ENABLED=false` 可关闭。
- `/actuator/health` 只暴露 health；`IndexLockHealthIndicator` 输出 `instanceId`、`distributed`、`profiles`、`activeLock`、`queryCircuit`、`queryFailureRate`。Redis 健康检查由 data-redis 自动提供。
- 注意 Spring Boot 4 的 actuator 包名：`org.springframework.boot.health.contributor.Health/HealthIndicator`（不再是 `org.springframework.boot.actuate.health`）。

## 已知纰漏 / 风险

- `rag_ingest` 失败时已补“尽力清理残留片段”，但 Qdrant 部分写入无法完全保证原子。
- 单篇 `reindex` 已改为“能枚举旧 point id 时先写新片段、再按 id 删旧片段”，失败时旧数据仍可检索；无法枚举时回退先删后写。写入窗口内可能短暂返回新旧重复片段；删除旧片段失败会保留重复，由下次重切清理。
- `rag_rebuild` 只清理 `chunk_type=chunk/child/parent`；旧版本无 `chunk_type` 的片段不会被清理。
- 归档摘要索引 `.archive/index.json` 解决 `rag_list`/启动重切全量读原文的问题；索引缺失/版本不符/id 集合不一致时全量重建一次。索引内容陈旧但 id 集合一致时可能保留旧签名。
- `ChunkingReindexRunner`（ApplicationRunner）先于 `ApplicationReadyEvent` 执行；若 `ReindexTaskService.hasRecoveredTask()` 为 true 则跳过自动重切，交给续接任务统一处理，避免同一批文档重复重建。
- 多副本：distributed 模式下索引写入已由 Redis 租约锁互斥；但任务快照锁仍是单实例读后写、无 fencing token，持有者被 kill 后的任务接管与 leader-only 扫描留 P2。
- 目录导入 watcher 受平台 inotify/事件队列限制，失败时退化为定时扫描；label 变化会生成新文档 id，旧 label 需 `delete-orphan-roots=true` 才会清理；`lastRun` 不持久化（重启后为空）。
- 切分算法变更必须同步提升 `ChunkingSignature` 版本，否则旧索引不会自动重切。
- `MarkdownChunker` 已知取舍：段落后 `---` 按 Setext H2；未闭合围栏吞到文末；超大表格/代码按行切分并补表头/围栏。

## 待办（未做）

- 真实语料端到端验证 MMR 检索效果（需启动 Qdrant + 入库实际知识库后人工评估）。
- 混合主题语料校准 `SEMANTIC_BREAKPOINT_PERCENTILE`（当前校准语料为同质技术文档，分布扁平）。
