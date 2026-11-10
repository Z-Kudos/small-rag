---
title: 单机架构与设计要点
status: implementing
version: 0.0.4
updated: 2026-11-10
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.4/plan.md, docs/v0.0.4/adr/0001-single-node-scope.md]
---

# 单机架构与设计要点

## 1. 背景与范围

- 目标：说明 v0.0.4 的单机架构、包结构与关键数据流。
- 范围：只覆盖 standalone 单容器；多副本版本已封存为 `cloud-rag`，不在本仓库维护。

## 2. 约束

- JDK 25、Spring Boot `4.2.0-RC1`、Spring AI `2.0.2`。
- 只使用 Embedding 模型，不创建 ChatModel / ChatClient。
- 向量库固定 Qdrant；不依赖 Redis、Consul、Config Server、Gateway。
- 单容器 `all-in-one`：应用 + Qdrant + llama.cpp 由 supervisord 管理。

## 3. 设计要点

- **单模块**：`src/main/java/com/smallrag/ragmcp/`，无 gateway / config-server 模块。
- **MCP**：Spring AI MCP Server WebMVC，`protocol=STREAMABLE`，端点 `/mcp`，13 个工具。
- **纯文本 RAG**：入库、切分、向量检索、上下文拼装，不调用 Chat / 生成模型。
- **切分**：`fixed` / `recursive` / `semantic` / `parent-child`（默认），统一按全宽字符当量计量。
- **Parent-Child**：子块召回、父块提供上下文；父块独立 point，检索时按 `parent_id` 取回。
- **MMR**：`rag.mmr-lambda` 控制相关性与多样性折中。
- **确定性 chunk id**：`chunk:<docId>:<signature>:<index>`；同配置重复 upsert 覆盖同一批 point。
- **检索熔断**：直接使用 Resilience4j，只包 `rag_search` / `rag_query`。
- **单实例互斥**：`IndexActivityGuard` 用 `AtomicReference<String>` 串行化重建任务与目录导入；无租约、无续租。
- **异步重建**：每任务独立 daemon 线程；进度/检查点存 Qdrant 独立集合 `<collection>_reindex_state`；看门狗处理停滞、硬超时与宽限期。
- **目录导入**：确定性文档 id + mtime/size/SHA-256 增量；启动、定时、watcher、MCP 触发。
- **归档**：原文与摘要索引存本地卷，启动时按切分签名自动重切。

## 4. 包结构

```text
ragmcp/
├── SmallRagApplication.java
├── mcp/            RagMcpTools（13 个 MCP 工具）
├── config/         QdrantConfig、McpServerConfig、EmbeddingConfig、RagProperties、QdrantProperties
├── rag/            RagService、IndexActivityGuard、QueryCircuitBreaker、KnowledgeBaseProbe
├── rag/archive/    DocumentArchive、ArchivedDocument、ArchivedDocumentSummary
├── rag/chunking/   ChunkingService、MarkdownChunker、TextChunker、SemanticTextChunker、
│                   RecursiveTextChunker、ChunkingSignature、TextChunk、TextWeights
├── rag/reindex/    ReindexTaskService、ReindexStateStore、QdrantReindexStateBackend、
│                   ReindexListener、ReindexProgress、CancellationScope、ChunkingReindexRunner
└── rag/sources/    SourceSyncService、SourceStateStore
```

## 5. 关键数据流

**入库**：`rag_ingest` / `rag_update` / 目录导入 → `RagService` 归档原文 → `ChunkingService` 切分 → `EmbeddingModel` 批量向量化 → Qdrant upsert（子块 + 父块）。

**检索**：`rag_search` / `rag_query` → 熔断 → `KnowledgeBaseProbe` 空库短路 → Embedding → Qdrant 相似度检索 → Parent-Child 父块回填 → MMR → 上下文拼装。

**重建**：MCP 提交任务 → `IndexActivityGuard` 取进程内活动锁 → `ReindexTaskService` 异步线程 → 逐篇 `RagService.reindexArchived` → 检查点写 `_reindex_state` → 完成释放活动锁。

## 6. 影响面与限制

- **接口**：MCP 端点 `/mcp` 与 13 个工具签名保持稳定；`ReindexTaskView` 移除跨副本字段（`ownerInstance` / `epoch` / `heartbeatAt`）。
- **数据**：主集合存子块/父块；`<collection>_reindex_state` 只存任务快照与检查点。
- **兼容性**：`ChunkingSignature` 版本升级会触发一次全量重切。
- **运维**：单容器、单端口 18080；无 Redis/Consul/Gateway/Config Server。
- **限制**：不支持多副本；详见 [`limitations.md`](limitations.md)。

## 7. 回滚与演进

- **回滚**：回退镜像与配置；归档保留原文，可用 `rag_rebuild` 按当前配置重建。
- **演进**：多副本能力已移至 `cloud-rag`，本仓库不再引入分布式组件。
