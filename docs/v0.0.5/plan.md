---
title: v0.0.5 切片图谱与编辑管理规划
status: done
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
template: docs/templates/plan.md
related: [ROADMAP.md, DEV-MEMORY.md, docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md, docs/v0.0.5/design/architecture.md, docs/v0.0.5/design/limitations.md]
---

# v0.0.5 规划：切片图谱与编辑管理

> 让 AGENT 通过 MCP 操作和编辑 chunk：知识库从"黑盒写多读少"变成 Agent 可浏览、可定位、可编辑、可验证的结构化存储。

## 1. 背景与目标

**背景**

v0.0.4 完成了单机轻量化与多形态部署：AGENT 可以往知识库写入/检索，但只能做两件事——`rag_ingest/rag_update/rag_delete`（按文档级操作）和 `rag_search/rag_query`（检索）。AGENT 无法：

- 看见某个文档的结构（有哪些 chunk、父子关系、所在章节）；
- 理解 chunk 之间的关系（同父兄弟、同章节、跨文档链接）；
- 精确修改某个 chunk，只能整篇全文替换。

**目标（可验证）**

1. `rag_chunks`：列出文档的 chunk 树（index / 类型 / 父子 / heading_path / 首字）。
2. `rag_graph`：返回节点 + 边的 JSON 图（`contains` / `section` / `parent_of` / `links_to`），深度 ≤2。
3. `rag_search`：新增 `expand_graph`，命中响应附带结构化邻居。
4. `rag_get_chunk` / `rag_edit_chunk` / `rag_delete_chunk`：Agent 面编辑 API，`expectedText`（matchText）冲突校验，`rag_delete_chunk` 要求 `confirm=true`。
5. `rag_graph_export`：生成图 JSON（`documents` / `sections` / `chunks` 三层 + 四类边），可直接喂给 Obsidian / cytoscape / d3；不做任何前端。
6. 工具数 13 → 19；`mvn test` 全绿（目标 ≥180）；JVM 冒烟覆盖 ingest → graph → edit → 重检索。

**非目标**

- 不调用 Chat / 生成模型；不引入实体/关系抽取（GraphRAG）；不解禁 `spring.ai.model.chat=none`。
- 不做前端 UI，编辑与图查询只通过 MCP 工具暴露。
- 不做覆盖层编辑（C2）：编辑回写归档原文，整篇重切（C1 语义）。
- 不做覆盖原文的最小改动 Patch 算法；仍以 chunk 所在位置替换原文段。
- 不改变切分算法、检索、熔断、归档的既有行为；不创建新的向量表结构（`links` 只进 metadata）。

## 2. 现状与差距

| # | 能力 | 现状 | 目标 | 差距 / 影响 |
| --- | --- | --- | --- | --- |
| G1 | chunk 可见性 | 无 MCP 工具；只能看日志 | `rag_chunks` | Agent 必须盲猜 chunk 结构 |
| G2 | 图查询 | metadata 有 chunk_type/parent_id/heading_path，但无 API | `rag_graph` + `expand_graph` | 无法表达"相关片段" |
| G3 | 编辑 | 只有 `rag_update` 全文替换 | `rag_edit_chunk`（精确到 chunk + 冲突校验） | 大文档全文替换成本高、漂移风险大 |
| G4 | 删除 | 只有 `rag_delete` 按文档 | `rag_delete_chunk` + `confirm=true` | 无法只删一个错片段 |
| G5 | 链接边 | 不提取 Markdown 链接 | metadata 字段 `links` | 无法跨文档导航 |
| G6 | 导出 | 无 | `rag_graph_export` | 外部可视化/分析无数据源 |

关键代码位置：

- `ChunkingService.chunk(text, documentId)`：签名定位与重切入口；新增 `chunkWithSpans` 用于内部定位，不改存储。
- `RagService.ingest/update/reindex`：归档先行 + 签名置空的自愈语义，编辑 API 复用同一模式。
- `MarkdownChunker`：链接提取点（WP1）；`heading_path` 已存在。
- `RagMcpTools`：新增 6 个工具；`McpServerConfig` 注册。

## 3. 工作包

### WP0 版本切换 — 已完成（本提交）

**变更点**

- `pom.xml` 0.0.5；`application.yml` `spring.ai.mcp.server.version` 0.0.5；`ChunkingSignature` → `v0.0.5`（版本同步，升级触发一次全量重切）。
- 建立 `docs/v0.0.5/`（v0.0.4 冻结）；`ROADMAP.md`、`DEV-MEMORY.md`、`AGENTS.md`、README 双语同步。

**验收**

- `mvn clean package` 成功；`ChunkingSignature` 单测断言 `v0.0.5`；`docs/v0.0.5` 建立、`docs/v0.0.4` 冻结。

### WP1 跨文档链接边（B）

**变更点**

- `MarkdownChunker` 提取 inline `[text](target)`（.md/.markdown，剥离锚点与 URL decode）与 `[[wikilink]]`；存入 chunk metadata 新字段 `links`（JSON array）。
- `links_to` 解析：归档 `META_SOURCE_PATH` 归一化精确匹配；匹配 0 或 ≥2 个文档时产出空并记录日志 / 标 `ambiguous`。

**验收**

- 单测覆盖：链接提取的 ≥6 个 case（含锚点、URL、重复、wikilink、非 md 链接）。
- ingest 一组带链接的文档后，`rag_graph` 输出中出现 `links_to` 边（JVM 冒烟核验）。

**风险**

- 同名文件跨目录：命中重复时标 `ambiguous`，Agent 通过 metadata 看到 `links_ambiguous`。

### WP2 图谱 MCP 工具（A）

**变更点**

- `rag_chunks(documentId, limit)`：按 chunk_index 返回文档 chunk 树。
- `rag_graph(seed, depth ≤ 2, limit)`：seed ∈ {doc_id, chunkPointUuid, heading_path}；输出 `nodes + edges`；`depth` 默认 1。
- `rag_search` 新增可选 `expand_graph`：响应附带 `graph`（命中 → 父块 / 同父兄弟 / heading 链）。

**验收**

- 工具数 16；单测覆盖 chunk/graph/expand_graph 的边界（空知识库、未知 seed、depth=2）。
- 两个后端（qdrant / sqlite）都能跑（qdrant 用 scroll+内存 adjacency）。

### WP3 编辑管理（C1）

**变更点**

- `rag_get_chunk(documentId, chunkIndex)`：返回 chunk 正文 + metadata（含当前存在的 `links`）。
- `rag_edit_chunk(documentId, chunkIndex, newText, expectedText?)`：
  1. 读归档原文，按现行签名重切，取目标 chunk 的存储文本；
  2. `expectedText` 非空且与目标不符 → 拒绝并返回当前内容；
  3. 在原文中按顺序游标定位第 k 个匹配（k = chunk_index，重复文本消歧）；
  4. 替换目标段，归档 tmp 文件 + `ATOMIC_MOVE` 落盘；
  5. 整篇重切；返回 `{documentId, chunkCount, parentCount, signature, editedChunkIndex}`。
- `rag_delete_chunk(documentId, chunkIndex, confirm, expectedText?)`：定位同上，删除目标段；`confirm` 必须为 true。
- 编辑失败：归档签名置空，下轮启动自动补齐（与现有重切失败自愈一致）。

**验收**

- 单测：编辑 / 删除 / expectedText 冲突拒绝 / 重复文本消歧 / 越界 chunkIndex 的可读报错。
- JVM 冒烟：ingest（3 篇带链接）→ `rag_chunks` → `rag_edit_chunk` → `rag_search` 未命中旧值、命中新值；

### WP4 图谱导出（JSON，无前端）

**变更点**

- `rag_graph_export(seed?, limit?)`：全库图 JSON；`limit` 防爆炸；提供最小样例文档。

**验收**

- 输出可直接喂给 Obsidian / Cytoscape / d3；文档给出 JSON 字段说明。

### WP5 验证与文档

**变更点**

- `README.md`/`README.en.md` MCP 工具 13 → 19；新增工具表；`docs/v0.0.5` 全部更新。
- `AGENTS.md` 工具数、`ChunkingSignature` 备注、目标命令。
- `DEV-MEMORY.md` 建立 v0.0.5 状态；`ROADMAP.md` 新周期条目。

## 4. 里程碑与排期

| 里程碑 | 内容 | 出口标准 |
| --- | --- | --- |
| M1 版本切换 | WP0 | `mvn clean package`、签名 v0.0.5、`docs/v0.0.5` 建立 |
| M2 图谱能力 | WP1 + WP2 | links 边 + 16 工具全绿 |
| M3 编辑能力 | WP3 + WP4 | 编辑/删除/冲突校验全绿 + 导出 |
| M4 收尾 | WP5 | 验证矩阵全过、文档完成、按 v0.0.4 流程发布 |

## 5. 验证矩阵

| 场景 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 构建 | `mvn clean package` | 通过 |
| 单测 | `mvn test` | **190 全绿** |
| Qdrant E2E | `mvn verify -Pcontainer-it` | 推迟（本机内存紧张） |
| MCP | `initialize` / `tools/list` | **19 个工具可见** |
| 图导出冒烟 | JVM 冒烟（sqlite + 本机 ollama Qwen3-Embedding-0.6B，目录导入 2 篇带链接文档） | 通过：ingest → rag_chunks/rag_graph（depth=2，links_to 命中 b.md）→ rag_edit_chunk（回写归档后 rag_search 命中新值）→ rag_delete_chunk（删空后文档整篇删除） |

## 6. 决策记录

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 图谱方案 | metadata 现算邻接，不建新表；不用 Neo4j/GraphDB |
| D2 | 编辑语义 | C1：回写归档 + 整篇重切；不建覆盖层 |
| D3 | 冲突校验 | `expectedText`（matchText）；拒绝时返回当前内容 |
| D4 | 签名版本 | `ChunkingSignature` 升 `v0.0.5`（版本同步，接受一次全量重切） |
| D5 | 模型约束 | 不调用 Chat/生成模型，不引入 GraphRAG |
| D6 | `links_to` 冲突处理 | 命中重复标 `ambiguous`；零命中不产生边 |

详见 [`adr/0001-agent-editable-knowledge-base.md`](adr/0001-agent-editable-knowledge-base.md)。

## 7. 完成定义（DoD）

- [x] `mvn clean package` 通过
- [x] `mvn test` ≥180 全绿（190）
- [x] MCP `tools/list` 显示 19 个工具
- [x] README 双语与 AGENTS 同步（工具 19、目标版本 0.0.5）
- [x] JVM 冒烟：ingest → chunks → graph → edit → 重检索通过
- [x] `docs/v0.0.5` 建立并达标；`docs/v0.0.4` 保持冻结
