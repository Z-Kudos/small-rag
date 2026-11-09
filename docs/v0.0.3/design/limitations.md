---
title: 已知限制与风险
status: implementing
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.3/plan.md]
---

# 已知限制与风险

> 使用与部署注意事项见 [`README.md`](../../../README.md)。v0.0.3 正在修复的集群问题见 [`docs/v0.0.3/plan.md`](../plan.md)。

## 1. 背景与范围

- 目标：列出 v0.0.3 当前已知限制与风险，供部署、容量评估与排障参考。
- 范围：只覆盖当前版本；架构见 [`architecture.md`](architecture.md)，计划修复项见 [`../plan.md`](../plan.md)。

## 2. 约束

- 不承诺跨版本兼容未归档的历史数据。
- 集群能力仅在 `smallrag.distributed.enabled=true` 时生效，standalone 保持进程内实现。
- 限制的修复排期以 `plan.md` 的工作包为准，本文不写排期。

## 3. 限制清单

### 3.1 索引写入

- **索引失败窗口**：单篇重建能枚举旧 point id 时先写新片段再删旧片段，Embedding / Qdrant 失败不会让文档不可检索；只有无法枚举 id 时才回退为先删后写。写入窗口内可能短暂返回新旧重复片段，删除旧片段失败会保留重复并由下次重切清理。
- **旧数据清理**：`rag_rebuild` 删除 `chunk_type=chunk/child/parent` 的片段并在完成后清理孤儿父块与缺少 `chunk_type` 的旧片段；仍建议升级前备份归档与 Qdrant 快照。
- **幂等性**：子块 point id 为 `chunk:<docId>:<切分签名>:<index>` 确定性生成，同一文档在相同切分配置下重复 upsert 覆盖同一批片段；切分配置变化时签名变化，仍按“写新片段 → 删旧片段”替换。v0.0.3 升级会因 `ChunkingSignature` 版本变化触发一次全量重切。

### 3.2 归档与状态

- **归档索引**：`rag_list` / 启动重切使用 `.archive/index.json` 摘要索引，不再加载全部原文；索引丢失、版本不符或文档 id 集合不一致时会全量重建一次。索引内容陈旧但 id 集合一致时可能保留旧签名，异常后建议手动重建索引 / 重切。
- **重启续接边界**：任务锁的权威实现在 `DistributedLock`（standalone 进程内租约、distributed Redis 租约 `reindex-task` + 单调 epoch）；Qdrant 状态集合里的锁 point 只是诊断镜像，Qdrant 没有 CAS，不参与互斥。重启后只有抢到租约的副本才接管任务；租约被其他副本持有时不改写快照。旧持有者恢复后即使本地租约尚未过期，也会因 epoch 落后被拒绝写入。distributed 下 Redis 不可用时拒绝写索引（fail-closed）。
- **启动顺序**：`ChunkingReindexRunner` 在 `ApplicationReadyEvent` 之前同步执行；若检测到有重启时中断、待续接的重建任务，则跳过自动重切并交给续接任务处理，避免重复重建。
- **导入状态**：最近一次扫描统计只在内存中，重启后 `rag_sync_status` 的 `lastRun` 为空；日志仍保留历史。

### 3.3 目录导入

- watcher 依赖平台 inotify / 事件队列，超限或注册失败时会退化为启动 / 定时扫描；`rag_sync_sources` 为异步触发，用 `rag_sync_status` 轮询。
- root label 重复会拒绝扫描；label 变化会生成新文档 id，旧 label 需 `DELETE_ORPHAN_ROOTS=true` 才会清理。
- 墓碑只在源文件内容不变时生效；内容变化后墓碑失效并重新导入。

### 3.4 集群

- **MCP 会话**：standalone 使用 `STREAMABLE`（会话保存在单实例内存）；distributed 必须设置 `SMALL_RAG_MCP_PROTOCOL=STATELESS`，会话不再依赖单副本；Spring Cloud Gateway 通过 Consul + LoadBalancer 轮询副本。漏配时启动只告警，跨副本调用仍可能 404。`STATELESS` 不支持 server-initiated notifications（当前未使用）。
- **跨副本任务操作**：任意副本都能 `rag_task_status`（本地缺失时回读 Qdrant，默认 2s 缓存）；`rag_task_resume` / `rag_task_cancel` 通过抢 `reindex-task` 租约接管，原持有者仍在续租时无法中断其线程，只返回持有者实例与 taskId。Qdrant 状态集合不可用时只能看到本实例内存中的任务。
- **Redis 故障**：索引活动锁与 leader 选举失败，重建 / 目录导入停止并返回可读错误；检索不受影响。恢复后自动重新竞选。
- **Gateway / Config Server**：Gateway 依赖 Consul 发现 `small-rag` 实例，Consul 不可用时新副本不会被路由；Config Server 的 native 仓库需与所有环境保持一致，配置变更需重启应用或触发刷新。Config Server 不可用时 `optional:` 允许应用继续用本地配置启动。
- **熔断实现**：Spring Cloud CircuitBreaker 只提供抽象，状态与失败率诊断仍读取 Resilience4j registry；更换实现时 health 细节会退化。
- **归档卷**：distributed 模式必须共享且可写；多主机需 RWX 存储（NFS / CephFS），否则无法自动重切 / 续接。

### 3.5 切分

- **切分签名**：签名版本与项目版本同步（当前 `0.0.3`）；只改切分算法而不升版本时，旧索引不会被自动重切，需要手动 `rag_rebuild`。
- **MarkdownChunker 取舍**：段落后 `---` 按 Setext H2；未闭合围栏吞到文末；超大表格 / 代码按行切分并补表头 / 围栏。

## 4. 影响与缓解

- **影响**：索引写入窗口可能导致短暂重复片段；原持有者仍在运行时无法跨副本强制中断；Qdrant 状态集合不可用时跨副本任务视图退化；升级到确定性 chunk id 会触发一次全量重切；distributed 多两个进程（Gateway / Config Server）。
- **缓解**：升级前备份归档与 Qdrant 快照；集群部署使用共享 RWX 卷、设置 `SMALL_RAG_MCP_PROTOCOL=STATELESS` 与 `SMALL_RAG_ARCHIVE_SHARED=true`；Config Server 使用 `optional:` 前缀；异常后执行 `rag_rebuild`。

## 5. 演进

- v0.0.3 计划 / 已落地：WP1 `STATELESS`（已完成）、WP2 Redis 租约 + fencing epoch（已完成）、WP3 跨副本任务状态（已完成）、WP4 确定性 chunk id 与孤儿清理（已完成）、WP5 观测与一致性诊断（已完成）、WP6 Testcontainers + CI（已完成）、WP7 Spring Cloud Gateway / Config Server / CircuitBreaker（已完成，ADR-0002）。
- v0.0.3 发布后本文只做事实性修正；Spring Cloud 数据面后继变更已记录在本文与 ADR-0002，下一版本另建 `docs/v<next>/`。
