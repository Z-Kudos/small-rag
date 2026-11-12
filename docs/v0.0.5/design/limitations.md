---
title: v0.0.5 已知限制与风险
status: implementing
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.5/plan.md, docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md]
---

# v0.0.5 已知限制与风险

## 1. 背景与范围

- 目标：列出 v0.0.5 切片图谱与编辑能力的限制与风险，供部署与容量评估参考。
- 范围：仅 MCP 面；不做 UI；不调用 Chat/生成模型。

## 2. 约束

- 向量库：Qdrant（`VECTOR_STORE_TYPE=qdrant`，默认）与 SQLite（`=sqlite`）均受影响。
- 签名升 `v0.0.5`：从 v0.0.4 升级会触发一次全量重切（归档兜底）。

## 3. 限制清单

### 3.1 编辑管理

- **整篇重切（C1）**：`rag_edit_chunk` / `rag_delete_chunk` 只精确定位 chunk 但仍整篇重嵌；文档很大时编辑成本高（一次 Embedding 调用上限受 chunkCount 影响）。
- **无覆盖层（C2）**：编辑即改原文；`rag_delete_chunk` 删除段落后不存在"软删除"恢复（归档有 backup 为原文，但没有操作历史）。
- **定位依赖确定性**：`expectedText` 失配时按**当前签名重切**找到的 chunk 内容说话；多份相同文本靠 `chunk_index` 消歧，语义上是"按序第 k 个匹配"，误判风险低但不为零。
- **签名不变则不会自动补齐**：编辑写回归档时把签名置空，启动时 `ChunkingReindexRunner` 会重切补齐；如果归档签名异常（版本漂移），自愈失效，文档退回到重嵌。
- **编辑失败安全**：归档落盘采用 tmp + `ATOMIC_MOVE`；中途失败不会产生半文件，但索引侧本次 edit 已可能部分写入——由"先写归档后重嵌"的顺序 + 启动自愈覆盖。
- **并发无细粒度锁**：`IndexActivityGuard` 只串行化 rebuild/导入；单文档编辑与 rebuild 互斥由既有 `validateWritable` 写入栅栏覆盖，与 `rag_update` 语义一致。

### 3.2 图查询

- **`links_to` 精确匹配**：只对目录导入文档产生 `links_to` 边（路径精确匹配）；`rag_ingest` 文档无 `path`，不参与 `links_to`。
- **`links` 提取范围**：只对 Markdown 链接（`.md`/`.markdown`）与 `[[wikilink]]` 产生；不做引用计数、不做双向链回填（backlink 查询由 Agent 自己遍历 `links_to` 解决）。
- **大库导出体积**：`rag_graph_export` 全量输出与语料线性相关；`limit` 控制上限；超过上限截断并记录日志。
- **Qdrant 全表扫**：`links`/邻接现算需要 scroll 全部 point（<5 万片段可接受；5 万 ~ 20 万之间会变慢，但仍可工作）。
- **SQLite 读全表**：纯 Java 余弦 + 邻接计算，大库上 `rag_graph` 是 O(N) 且每次重算；无查询缓存。

### 3.3 编辑语义与签名

- **v0.0.5 全量重切**：从 v0.0.4 升级要承受一次向量重建；大模型下 `EMBEDDING_READ_TIMEOUT_MS=120000` 一般够用，但超大规模语料要等重切完成才能"有把握"检索。
- **`links` 不参与切分签名**：只影响命中内容里的链接信息；`links` 加入不会触发额外重切，但 v0.0.5 签名本身触发一次。
- **删除不可软恢复**：删除的内容仅可通过归档原文重新入库恢复覆盖。

### 3.4 演进

- v0.0.6 候选：覆盖层编辑（C2）、实体图谱（需解禁 Chat，属红线跨越，需先抬禁）、前端可视化、增量重嵌、多文档原子编辑。
