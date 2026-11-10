---
title: 单机版已知限制与风险
status: implementing
version: 0.0.4
updated: 2026-11-10
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.4/plan.md, docs/v0.0.4/adr/0001-single-node-scope.md]
---

# 单机版已知限制与风险

## 1. 背景与范围

- 目标：列出 v0.0.4 单机版的限制与风险，供部署与容量评估参考。
- 范围：只覆盖 standalone 单容器；多副本版本见 `cloud-rag`。

## 2. 约束

- 不支持多副本、HA、滚动升级与跨副本任务续接。
- 不使用 Redis / Consul / Config Server / Gateway。
- 索引写入活动锁只在单 JVM 内有效。

## 3. 限制清单

### 3.1 索引写入

- **单实例互斥**：`IndexActivityGuard` 只在当前 JVM 内串行化重建与目录导入；多个容器指向同一 Qdrant 时无法互斥。
- **写入窗口**：单篇重建能枚举旧 point id 时先写新片段再删旧片段；写入窗口内可能短暂返回新旧重复片段。
- **旧数据清理**：`rag_rebuild` 只清理 `chunk_type=chunk/child/parent` 的片段；更早版本无 `chunk_type` 的片段不会被清理，需要换新集合。
- **幂等性**：chunk id 由 `chunk:<docId>:<signature>:<index>` 确定性生成，同配置重复 upsert 不产生重复；签名变化时仍按“写新片段 → 删旧片段”替换。

### 3.2 归档与任务状态

- **归档索引**：`.archive/index.json` 是摘要索引；丢失、版本不符或 id 集合不一致时会全量重建一次。
- **重启续接**：未完成任务在重启后标为 `interrupted`；`REINDEX_RESUME_ON_STARTUP=true` 自动续接，否则等待 `rag_task_resume` 或 `rag_task_cancel`。
- **锁丢失**：进程崩溃时进程内活动锁随 JVM 消失；重启后不会残留锁，但正在进行的写入会中断并由任务状态恢复。
- **启动顺序**：`ChunkingReindexRunner` 在 `ApplicationReadyEvent` 之前同步执行；存在待续接任务时跳过自动重切。

### 3.3 目录导入

- watcher 依赖平台 inotify / 事件队列，超限或注册失败时会退化为启动/定时扫描。
- root label 重复会拒绝扫描；label 变化会生成新文档 id，旧 label 需 `DELETE_ORPHAN_ROOTS=true` 才清理。
- 墓碑只在源文件内容不变时生效；内容变化后墓碑失效并重新导入。
- `lastRun` 只存在内存，重启后 `rag_sync_status` 为空。

### 3.4 运行形态

- **单容器**：应用、Qdrant、llama.cpp 同容器；容器停止即全部停止，没有高可用。
- **资源**：Embedding 模型约 640 MB，加载后需要额外内存；建议 2–4 GB RAM、2 vCPU、5 GB 磁盘。
- **备份**：需要同时备份 `/data/documents`（归档）与 `/data/qdrant/storage`（向量）；只备份其一无法完整恢复。
- **升级**：升级会替换容器；升级前需要备份数据卷。

### 3.5 切分

- **切分签名**：签名版本与项目版本同步（当前 `0.0.4`）；只改切分算法而不升版本时旧索引不会自动重切。
- **MarkdownChunker 取舍**：段落后 `---` 按 Setext H2；未闭合围栏吞到文末；超大表格 / 代码按行切分并补表头 / 围栏。

## 4. 影响与缓解

- **影响**：多副本能力缺失；单点故障；进程内锁不跨 JVM；升级需要停机。
- **缓解**：单机部署使用独立数据卷并定期备份；不要将多个 small-rag 实例指向同一 Qdrant；需要 HA 时使用 `cloud-rag`。

## 5. 演进

- 多副本、Gateway、Config Server、Redisson 等能力保留在 `~/Projects/cloud-rag`，本仓库不再引入。
