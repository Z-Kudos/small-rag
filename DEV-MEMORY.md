# DEV-MEMORY.md — 开发记忆

> 每次开发同步更新：只记录当前有效的状态，过时内容直接覆盖/删除，不做历史归档（历史看 git log）。

## 项目状态（2026-11-06）

- 版本：`0.0.2`（pom.xml、application.yml MCP server version、README docker tag、ChunkingSignature 签名版本四处统一；签名版本与项目版本同步，但切分算法/存储布局变更需单独提升）。
- 构建：本机默认 JDK 21 编不了 release=25，用 `JAVA_HOME='D://java//jdk-27.0.1.0.3' mvn clean package`（JDK 27）；103 测试全绿。
- Dockerfile 无 `# syntax=` 指令（国内网络拉不到 docker.io 的 frontend 镜像；所用特性内置 BuildKit 全支持）；compose/.env 镜像 tag 随项目版本走 `APP_IMAGE_TAG`。
- 配置文件：`.env` 直接入库（由 .env.example 改名，仅模板值，.gitignore 已取消忽略；真实密钥禁止写入，.dockerignore 仍排除）。
- 容器迁移端到端验证通过（2026-11-05）：镜像构建 24 分钟（含首次 Maven 依赖下载与 llama.cpp 编译）；三进程（qdrant/llama-server/app）全部就绪；容器内 llama.cpp 与宿主机 Ollama 同文本向量余弦 = 1.0000（pooling last 正确）；MCP 全链路 rag_ingest→rag_query→rag_search(expand=false)→rag_delete 冒烟通过。**教训：docker/*.sh 工作区曾出现 CRLF 导致 exec ENOENT，已加 .gitattributes 强制 LF**。
- 容器推理运行时 = **llama.cpp**（0.0.2 起替换 Ollama）：以 qdrant 镜像为基底从源码编译 llama-server（`LLAMA_CPP_TAG=v0.9.0`），`start-llama.sh` 下载 GGUF（ModelScope，断点续传）并以 `--embeddings --pooling last` 启动（Qwen3-Embedding 必须 last pooling）；`SMALL_RAG_ENSURE_EMBEDDING_MODEL=true` 时 `start-app.sh` 等 `/health`。模型落 `/data/models/`。Ollama 仍可用于本机开发（README 本地运行节）。
- 手动校准工具：`src/test/java/.../calibration/BreakpointPercentileCalibration.java`，运行 `mvn test -Dtest=BreakpointPercentileCalibration -Dcalibrate=true`（需本机 OpenAI 兼容 Embedding 服务，`EMBEDDING_BASE_URL`/`EMBEDDING_MODEL`/`EMBEDDING_API_KEY` 可覆盖默认值）。

## 切分体系（当前有效）

- 策略 4 种：`fixed` / `recursive` / `semantic` / `parent-child`（默认）。`markdown` 不是策略——`MarkdownChunker` 是内部引擎，由 semantic 与 parent-child 共用。
- semantic = Markdown 分节 + 节内文本块与累积质心比较合并（`MarkdownChunker.chunkSemantic`）；代码/表格/数学块独立成块；超长文本块回落 `SemanticTextChunker` 句子级切分；尾块回收。
- 长度度量 = 全宽字符当量（`TextWeights`：CJK=1、拉丁=0.25）；英文硬切回退单词边界；句界英文缩写白名单。
- SEMANTIC `breakpoint-percentile`：真实语料校准结论（Qwen3-Embedding，2026-11-05）——技术文档语料上 0.1~0.4 输出完全一致，比绝对阈值 0.75 少约 5% 碎块（57 vs 60 块，p90 388 vs 312），参数鲁棒；技术文档推荐 `0.2`，默认仍为 0（绝对阈值）。混合主题语料上分位数模式优势更明显。
- Parent-Child **父块独立存储**（0.0.2 起）：父块作为独立 point（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)` 确定性 UUID）与子块同集合写入；子块只带 `parent_id`，**不再有 `parent_text`**；检索按 id 取回父块（payload `content` 字段 + 向量），父块缺失/取回失败回退子块原文。`rag_rebuild` 清理 chunk/child/parent 三类。
- 检索 MMR：`rag.mmr-lambda`（默认 0.7，=1 关闭）；候选 topK×4 → MMR（λ·score−(1−λ)·maxSim）→ topK；Parent-Child 复用父块存储向量（零额外模型调用），其余策略对候选文本批量 Embedding。
- MarkdownChunker 边界行为：围栏/缩进代码内的 `#` 不会误判为标题（回归测试锁定）；未闭合围栏吞到文末（符合规范）；段落后 `---` 按 Setext H2 解析（已知取舍）。
- 超长表格切片重复表头；超长 fenced code 切片补围栏；数学块补 `$$`。

## 检索与存储

- **重建进度与看门狗**：`rag_rebuild` 与 `rag_reindex`（不传 documentId）为**异步任务**，立即返回 `taskId`；`rag_task_status(taskId?)` 查询 `status`（queued/clearing/reindexing/interrupted/completed/aborted/cancelled/failed）、`total/processed/percent/succeeded/failed/currentDocumentId/lastProgressAt/stalled/cancelRequested/startedAt/finishedAt/error/revoked/lockReleaseAt`；`rag_task_cancel(taskId?)` 对运行中任务是文档边界协作式取消，对 `interrupted` 任务立即放弃并释放锁；`rag_task_resume(taskId?)` 续接 `interrupted` 任务。`ReindexTaskService` = 每任务独立 daemon 线程 + 单任务互斥 + 保留最近 20 个快照。单篇 `rag_reindex(documentId)` 仍同步。
- **状态持久化与重启续接**：`ReindexStateStore`（门面）+ `QdrantReindexStateBackend` 把任务快照（含 `cleared` + `completedDocumentIds` 检查点，JSON 字符串）与任务锁写入 Qdrant 独立集合 `<rag.qdrant.collection-name>_reindex_state`：1 维 Dot 哑向量 + payload，集合按需创建；任务 point id 由 taskId 确定性生成，锁为固定 id 的单 point。状态跟随 Qdrant 持久化，不依赖应用容器文件系统；读失败按“无历史任务/无锁”处理，写失败由调用方告警降级。重启时加载全部快照；锁持有者未撤销/未取消则标记 `interrupted` 并**继续持有任务锁**，默认 `REINDEX_RESUME_ON_STARTUP=true` 时由 `ApplicationReadyEvent` 自动续接：`clearFirst = rebuild && !cleared`，跳过 `completedDocumentIds` 并计入成功；`false` 时停在 `interrupted` 等手动 `rag_task_resume`（或 `rag_task_cancel` 放弃）。优雅关机（`@PreDestroy`）会撤销写入、中断线程，但把状态覆盖为 `interrupted` 并保留任务锁，供重启续接；硬超时已撤销的任务不会续接。若 Qdrant 数据/集合名变化导致状态丢失：切分配置变化时 `ChunkingReindexRunner` 会按归档签名补建；但同签名 `rag_rebuild` 中断后剩余文档不会自动补建，需重新执行。
- 看门狗：每 `REINDEX_CHECK_INTERVAL_SECONDS`（30s）检查；超过 `REINDEX_STALL_TIMEOUT_SECONDS`（1800s）无进度只置 `stalled` 并告警、**保留锁**；超过 `REINDEX_HARD_TIMEOUT_SECONDS`（3600s）执行 `revoke()`：撤销写权限（`ReindexListener.isWritable()=false`，`RagService` 在每次 `delete/add` 前校验并抛 `ReindexRevokedException`）+ `CancellationScope.cancel()` 取消在途 Embedding 调用 + `interrupt`。线程在 `REINDEX_JOIN_TIMEOUT_SECONDS`（10s）内退出则立即释放锁；否则进入 `REINDEX_RECOVERY_GRACE_SECONDS`（120s，且 ≥ Qdrant deadline×2）宽限期，到期强制释放任务锁并置 `failed`。因此**卡死不再需要重启**；宽限期内提交新任务返回“已撤销，正在释放任务锁”。硬超时任务最终状态为 `failed`。
- 锁覆盖范围（已知限制）：仅 MCP 全量重建/全量 reindex 走这把锁；启动自动重切、单篇 reindex、`rag_update`/`rag_ingest` 不经过它；多副本部署时锁不跨进程（Qdrant 状态集合中的锁是读后写，不是分布式 CAS，仍按单实例设计）。
- **调用超时与主动取消**：Embedding 用 OpenAI SDK/OkHttp 超时（`EmbeddingConfig`：`EMBEDDING_CONNECT_TIMEOUT_MS=10000`、`EMBEDDING_READ_TIMEOUT_MS=120000`，read 同时作为 write 与单次请求总超时；`spring.http.client.*` 不生效）；同一 customizer 注册 `CancellationScope.interceptor()`，通过 `ThreadLocal` 把任务线程的在途 `okhttp3.Call` 登记到作用域，撤销时 `Call.cancel()` 立即打断。Qdrant gRPC 默认 deadline 改为 `QDRANT_REQUEST_TIMEOUT_MS=30000`（`init-timeout-ms` 只用于启动探活）。
- `RagService.reindexArchived(clearFirst, listener, skipDocumentIds)` 逐篇上报 `ReindexProgress`，并在 `onCleared()` / `onDocumentFinished()` 回调中落检查点；`rag_reindex`/`rag_rebuild` 同步入口保留（NOOP listener）。清空前校验归档非空，清空后**重读**归档列表以缩小漏重建窗口。
- `rag_search` 可选 `expand`（默认 true，false 返回原始子块且不做 MMR）；`buildContext` 预算超限 continue 跳过；`maxChunkChars=1200` / `maxContextChars=12000` 按全宽字符当量计量。
- 包结构：`rag/`（RagService、ReindexTaskService、ReindexStateStore、ReindexProgress/ReindexListener、CancellationScope、KnowledgeBaseProbe、ChunkingReindexRunner）+ `rag/chunking/`（8 类）+ `rag/archive/`；payload 正文键 `RagService.CONTENT_FIELD="content"`（QdrantConfig 同步引用）。

## 待办（未做）

- 真实语料端到端验证 MMR 检索效果（需启动 Qdrant + 入库实际知识库后人工评估）。
- 混合主题语料校准 `SEMANTIC_BREAKPOINT_PERCENTILE`（当前校准语料为同质技术文档，分布扁平）。
