---
title: 已知限制与风险
status: approved
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
related: [README.md, docs/v0.0.3/plan.md]
---

# 已知限制与风险

> 使用与部署注意事项见 [`README.md`](../../../README.md)。v0.0.3 正在修复的集群问题见 [`docs/v0.0.3/plan.md`](../plan.md)。

## 索引写入

- **索引失败窗口**：单篇重建能枚举旧 point id 时先写新片段再删旧片段，Embedding / Qdrant 失败不会让文档不可检索；只有无法枚举 id 时才回退为先删后写。写入窗口内可能短暂返回新旧重复片段，删除旧片段失败会保留重复并由下次重切清理。
- **旧数据清理**：`rag_rebuild` 只按 `chunk_type=chunk/child/parent` 清理；更早版本写入、没有 `chunk_type` 的片段不会被清理，需换新集合。
- **幂等性**：当前 chunk point id 含随机 `generation`，重复写入依赖“写后删旧”；v0.0.3 将改为确定性 id（见规划 WP4）。

## 归档与状态

- **归档索引**：`rag_list` / 启动重切使用 `.archive/index.json` 摘要索引，不再加载全部原文；索引丢失、版本不符或文档 id 集合不一致时会全量重建一次。索引内容陈旧但 id 集合一致时可能保留旧签名，异常后建议手动重建索引 / 重切。
- **重启续接边界**：Qdrant 状态集合中的锁是单实例读后写，不是分布式 CAS；多副本部署不互斥。锁 point 丢失但任务快照仍在时，按单实例假设由最近一个未完成 / 未撤销任务重新持锁；多副本下可能误抢。v0.0.3 WP2 将改为 Redis 租约 + fencing token。
- **启动顺序**：`ChunkingReindexRunner` 在 `ApplicationReadyEvent` 之前同步执行；若检测到有重启时中断、待续接的重建任务，则跳过自动重切并交给续接任务处理，避免重复重建。
- **导入状态**：最近一次扫描统计只在内存中，重启后 `rag_sync_status` 的 `lastRun` 为空；日志仍保留历史。

## 目录导入

- watcher 依赖平台 inotify / 事件队列，超限或注册失败时会退化为启动 / 定时扫描；`rag_sync_sources` 为异步触发，用 `rag_sync_status` 轮询。
- root label 重复会拒绝扫描；label 变化会生成新文档 id，旧 label 需 `DELETE_ORPHAN_ROOTS=true` 才会清理。
- 墓碑只在源文件内容不变时生效；内容变化后墓碑失效并重新导入。

## 集群

- **MCP 会话**：`STREAMABLE` 会话保存在单实例内存；当前 HAProxy 为 `roundrobin`，跨副本调用会丢会话。v0.0.3 WP1 将在 distributed 模式改用 `STATELESS`。
- **任务可见性**：`rag_task_status` / `rag_task_resume` 目前只读本实例内存中的任务，跨副本不可见。v0.0.3 WP3 修复。
- **Redis 故障**：索引活动锁与 leader 选举失败，重建 / 目录导入停止并返回可读错误；检索不受影响。恢复后自动重新竞选。
- **归档卷**：distributed 模式必须共享且可写；多主机需 RWX 存储（NFS / CephFS），否则无法自动重切 / 续接。

## 切分

- **切分签名**：签名版本与项目版本同步（当前 `0.0.3`）；只改切分算法而不升版本时，旧索引不会被自动重切，需要手动 `rag_rebuild`。
- **MarkdownChunker 取舍**：段落后 `---` 按 Setext H2；未闭合围栏吞到文末；超大表格 / 代码按行切分并补表头 / 围栏。
