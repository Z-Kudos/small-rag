---
title: ADR-0001 Agent 可编辑知识库的图谱与编辑语义
status: done
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
template: docs/templates/adr.md
related: [docs/v0.0.5/plan.md, docs/v0.0.5/design/architecture.md, docs/v0.0.5/design/limitations.md]
---

# ADR-0001：Agent 可编辑知识库的图谱与编辑语义

## 1. 背景

- **问题**：Agent 通过 MCP 调 `rag_*` 工具只能把知识库当作黑盒检索器；无法看见结构、无法定位问题 chunk、无法安全编辑/删除。
- **触发原因**：v0.0.4 发布后用户明确要求"让 AGENT 通过 MCP 操作和编辑 Chunk，也就是支持 AGENT 编辑管理知识库"。
- **相关约束**：不调用 Chat/生成模型（红线）；纯文本 RAG；单机单容器；Qdrant 或可切换 SQLite；MCP-only，无 UI。

## 2. 决策

### 2.1 图谱 = metadata 现算，不建新表

- 不引入 Neo4j/JanusGraph/GraphBLAS 等外部图存储；不为图查询拷贝或反规范化向量。
- Agent 查询图时，App 从 chunk metadata 直接构建邻接（<5 万片段足够，Qdrant 用 scroll+内存、SQLite 用 SQL+内存）。
- 图查询只支持 `contains` / `section` / `parent_of` / `links_to` 四种边；更复杂的多跳查询由 Agent 分次调用、每次 depth ≤2。

### 2.2 编辑 = 回写归档 + 整篇重切（C1）

- Agent 改 chunk 就是"按现行签名重切定位 → 改原文段 → tmp+ATOMIC_MOVE 落盘 → 整篇重嵌"。
- 不引入覆盖层（C2）：覆盖层需要在检索/更新/删除路径合并两份语义，风险与代码量都与"解决 Agent 可编辑"的初衷不符。
- `expectedText`（matchText）冲突校验：编辑前非空时必须与当前 chunk 内容一致，否则拒绝并返回当前内容。
- `rag_delete_chunk` 必须显式 `confirm=true`（与 `rag_rebuild` 的确认照样式一致）。

### 2.3 签名升 v0.0.5

- `ChunkingSignature` 按 AGENTS 硬规则与项目版本同步，v0.0.4 → v0.0.5；接受升级触发一次全量重切（归档自动兜底）。
- `links` 不参与切分签名计算（链接不影响切分结果），只进 metadata。

### 2.4 不引入实体/关系抽取（GraphRAG），不解禁 Chat

- 纯文本 RAG 的硬约束不因本版本变化；`spring.ai.model.chat=none` 保留。
- 实体/关系图谱作为 v0.0.6 候选讨论，必须先抬红线，不走静默路径。

### 2.5 编辑后的验证责任落在 Agent

- 编辑 API 只保证"回写原文 + 重新入库"原子一致；`rag_search` 的命中反映与否由 Agent 决定是否二次查询验证。应用不把调用方的业务逻辑读回并加锁。

## 3. 备选方案（放弃的原因）

| 备选 | 放弃原因 |
| --- | --- |
| 图数据落到专用图数据库 | 新存储 → 新备份/恢复/运维/并发模型，超范围 |
| 覆盖层编辑（C2）：`rag_edit_chunk` 只覆盖检索侧 | 更新/删除/检索三路必须一起改，成本与风险远高于 C1，收益有限 |
| 解禁 Chat 做实体抽取（GraphRAG） | 违红线；收益不确定；超出"编辑管理"初衷 |
| 前端 UI 可视化图谱 | 数据源是同一组 API；先做导出（`rag_graph_export`），UI 留给外部工具 |
| 指纹方式（chunk 哈希）而非 `expectedText` | 对 Agent 不直观（需先理解哈希语义），`matchText` 精确匹配且可读 |
| 覆盖层 + C1 都保留 | 只保留一种语义；两种并存让鉴权与测试矩阵成倍膨胀 |

## 4. 后果

**正面**

- Agent 可以精确看见结构、定位错片、修它并验证；知识库从"黑盒"变成"可操作"。
- 零新存储依赖；实现全部复用 archive + reindex 路径。
- 编辑失败有清晰的自愈（签名置空 → 启动重切）。

**负面**

- 整篇重嵌的编辑成本随文档体积线性增长（C1 的本质）。
- `links_to` 对 `rag_ingest` 入库的文档不成立（无 `path`），需用户知悉。
- 签名升版带来一次性批量重切成本。

**缓解**

- `rag_edit_chunk` 返回 receipt，Agent 可低成本 `rag_search` 验证；编辑的延迟不影响检索读路径。
- `links_to` 只承载精确匹配，`ambiguous` 标记帮助 Agent 二次判断；不要求全量覆盖。
- 批量重切在版本发布说明里明确提示（`README` 迁移说明 + 本版 plan.md）。
