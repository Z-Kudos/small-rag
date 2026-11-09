---
title: 架构与设计要点
status: approved
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.3/plan.md]
---

# 架构与设计要点

> 面向开发者。使用说明见 [`README.md`](../../../README.md)。

## 1. 背景与范围

- 目标：说明 v0.0.3 的架构分层、包结构与关键数据流，供开发、评审与排障使用。
- 范围：只覆盖当前版本；使用说明见 `README.md`，已知限制与风险见 [`limitations.md`](limitations.md)。

## 2. 约束

- JDK 25、Spring Boot `4.2.0-RC1`、Spring Cloud `2026.0.0-RC1`、Spring AI `2.0.2`。
- 只使用 Embedding 模型，不创建 ChatModel / ChatClient。
- 向量库固定 Qdrant；standalone 模式零新增外部依赖。

## 3. 设计要点

- **仅向量模型**：`spring.ai.model.chat=none`，不创建 ChatModel / ChatClient。
- **阈值与预算**：`rag.min-score=0.35`；单片段超长时以命中子块（`matched_text`）为中心做窗口截断，总上下文 12000 全宽字符当量，预算不足时跳过当前片段继续尝试装载后续更短片段。
- **父块独立存储**：父块作为独立 point 与子块同集合写入，子块不再复制父块正文（消除 payload N 倍放大）；检索命中后按 id 取回父块正文与向量，父块缺失时回退为子块原文。
- **MMR 多样性**：候选超出 topK 时按 `λ·score − (1−λ)·与已选结果的最大相似度` 迭代选取（`MMR_LAMBDA`，默认 0.7）；Parent-Child 复用父块存储向量，零额外 Embedding，其余策略对候选文本做一次批量 Embedding。
- **空库短路**：检索前用 Qdrant `count` 判断，避免无意义 Embedding 调用。
- **启动韧性**：Qdrant 不可用只告警（fail-soft）；集合维度不一致直接失败（fail-fast）。
- **自动重切**：原文归档 + 切分配置签名（签名版本与项目版本同步，当前 `0.0.3`），启动时检测到变化自动删旧片段重建。
- **Markdown 结构**：标题分节，代码 / 表格 / callout 受保护，frontmatter、heading_path、wikilink 写入元数据；超长表格 / 代码切片重复表头或围栏。
- **中英对齐**：切分长度按全宽字符当量计量（CJK=1、拉丁=0.25），英文硬切回退单词边界，句界识别带缩写白名单；语义断点支持分位数模式。
- **重切时机**：启动时同步执行，失败不阻断启动，下次启动继续重试。
- **目录导入**：确定性文档 id（根 label + 相对路径）+ `mtime/size/SHA-256` 增量；状态文件与归档同卷，丢失时从归档元数据重建；启动 / 定时 / watcher / MCP 异步触发，`max-files-per-scan` 限流。
- **归档摘要索引**：`.archive/index.json` 保存 metadata / 签名 / 长度，`rag_list` 与启动重切只读摘要，原文按需 `find`。
- **重切写入顺序**：子块 id 由 `chunk:<docId>:<切分签名>:<index>` 确定性生成，相同配置重复写入覆盖同一批 point；签名变化时新旧 id 不同，仍先写新片段再删旧片段，失败时旧数据可检索；无法枚举旧 id 时回退先删后写。
- **孤儿清理**：`rag_rebuild` 完成后扫描集合，删除无对应子块的父块与缺少 `chunk_type` 的旧版本片段。
- **错误收敛**：MCP 工具把运行期异常转为 `{"error": "..."}`，不抛协议层异常。
- **集群会话**：standalone 使用 `STREAMABLE`；distributed 多副本使用 `STATELESS`（`SMALL_RAG_MCP_PROTOCOL`），会话不再保存在单副本内存，gateway 可继续 `roundrobin`。`McpTransportGuard` 在 distributed 且非 `STATELESS` 时启动告警。
- **任务所有权**：重建任务持有 `<lock-prefix>reindex-task` 租约 + 单调 fence epoch（standalone 进程内、distributed Redis）；每次 Qdrant 变更前远程校验持有者、token 与 epoch 仍最新，旧持有者恢复后不能继续写入。distributed 下 Redis 不可用时 fail-closed。Qdrant `_reindex_state` 的任务锁 point 只作诊断镜像，不参与互斥。
- **跨副本任务视图**：`rag_task_status` 本地缺失时回读 Qdrant 状态集合（`task-status-cache-ms` 读穿缓存）；`rag_task_resume` / `rag_task_cancel` 先抢 `reindex-task` 租约，抢到即接管（epoch 递增），抢不到返回锁镜像中的持有者实例。启动时校验镜像与权威锁一致，以权威锁为准清理过期镜像。

## 4. 包结构

```text
mcp/            RagMcpTools：rag_* 共 13 个工具（入库/检索/重建/任务查询/目录导入）
rag/            RagService（入库/检索/上下文/更新删除重建）、IndexActivityGuard（重建与目录导入互斥）、
                KnowledgeBaseProbe（空库短路）、QueryCircuitBreaker（检索熔断）
rag/reindex/    ReindexTaskService（异步重建/看门狗/撤销与宽限期）、ReindexStateStore（任务快照与锁门面）、
                ReindexStateBackend、QdrantReindexStateBackend（Qdrant 状态集合）、
                ReindexProgress、ReindexListener、ReindexRevokedException、CancellationScope（OkHttp 主动取消）、
                ChunkingReindexRunner（启动自动重切）
rag/sources/    SourceSyncService（多目录 Markdown 扫描/增量/定时/手动触发/watcher）、
                SourceStateStore（文件指纹与手动删除墓碑）、SourcesLeaderElection（扫描 leader）
rag/chunking/   ChunkingService、ChunkingStrategy、ChunkingSignature、TextChunk、
                TextChunker、RecursiveTextChunker、MarkdownChunker、SemanticTextChunker
rag/archive/    DocumentArchive、ArchivedDocument、ArchivedDocumentSummary（原文归档、摘要索引与签名记录）
distributed/    DistributedLock、LockHandle、FencedLease、LocalDistributedLock、RedisDistributedLock
config/         QdrantConfig、McpServerConfig、McpTransportGuard（协议与运行形态校验）、
                ClusterStartupGuard（共享归档告警）、ArchiveProperties、EmbeddingConfig（HTTP 超时）、
                DistributedLockConfig、DistributedProperties、RagProperties、QdrantProperties、
                IndexLockHealthIndicator
docker/         start-qdrant.sh / start-llama.sh / start-app.sh / supervisord*.conf
```

`VectorStore` 与 `EmbeddingModel` 均按 Spring AI 接口注入。

## 5. 关键数据流

**入库**：`rag_ingest` / `rag_update` / 目录导入 → `RagService` 归档原文 → `ChunkingService` 切分 → `EmbeddingModel` 批量向量化 → Qdrant upsert（子块 + 父块）。

**检索**：`rag_search` / `rag_query` → 熔断器 → `KnowledgeBaseProbe` 空库短路 → Embedding → Qdrant 相似度检索 → Parent-Child 按 `parent_id` 去重回取父块 → MMR → 上下文拼装。

**重建**：MCP 提交任务 → `IndexActivityGuard` 获取索引活动锁（与目录导入互斥）→ `FencedLease` 获取 `reindex-task` 租约并递增 epoch → `ReindexTaskService` 异步线程 → 逐篇 `RagService.reindexArchived`（每次写入校验租约 + epoch）→ 检查点与锁诊断镜像写入 `_reindex_state` → `rag_rebuild` 完成后清理孤儿片段 → 完成释放租约与活动锁。

**跨副本任务操作**：任意副本 `rag_task_status` → 本地快照优先，缺失时回读 `_reindex_state`（2s 缓存）；`rag_task_resume` / `rag_task_cancel` → 回读任务 → 抢 `reindex-task` 租约（epoch 递增）→ 续接 / 放弃；抢不到则返回锁镜像中的持有者实例。

## 6. 影响面与限制

- **接口**：MCP 端点 `/mcp` 与 13 个工具签名保持稳定；`rag_query` 仍只返回增强上下文。
- **数据**：主集合存子块与父块，独立集合 `<collection>_reindex_state` 存任务快照与任务锁诊断镜像；归档目录存原文与摘要索引。
- **兼容性**：`ChunkingSignature` 版本与项目版本同步，升级可能触发一次全量重切；子块 point id 已改为确定性（`chunk:<docId>:<signature>:<index>`），同一签名下重复写入幂等；`rag_task_status` 新增 `ownerInstance` / `epoch` / `heartbeatAt`，旧快照缺字段按空值恢复。
- **运维**：`/actuator/health` 暴露锁、leader、重建任务（owner/epoch/heartbeat/stateStore）、归档卷（enabled/writable/dir/shared）与熔断状态；distributed 模式依赖 Redis 与共享归档卷。
- **限制与风险**：见 [`limitations.md`](limitations.md)。

## 7. 回滚与演进

- **回滚**：配置回退到上一版本；归档保留原文，可用 `rag_rebuild` 按当前配置重建；`ChunkingSignature` 变更后首次启动会自动重切。
- **演进**：v0.0.3 的集群与幂等改造见 [`../plan.md`](../plan.md)；跨版本决策见 [`../adr/`](../adr/)。
