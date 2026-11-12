---
title: v0.0.5 架构与设计要点（图谱 + 编辑）
status: done
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.5/plan.md, docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md]
---

# v0.0.5 架构与设计要点

## 1. 背景与范围

- 目标：在 v0.0.4 单机架构（向量库可选 qdrant/sqlite）之上，为 Agent 暴露可理解、可编辑的知识库图。
- 范围：仅 MCP 面（无前端）；多形态部署开关不变；红线仍为不调用 Chat/生成模型。

## 2. 约束

- MCP（Streamable HTTP）为唯一入口；13 个既有工具不变语义。
- 向量库：Qdrant（默认）与 SQLite（`VECTOR_STORE_TYPE=sqlite`）两种后端都支持图查询。
- 切分不变；`links` 只进 metadata，不参与切分判定。
- 任务互斥沿用 `IndexActivityGuard`；单个 chunk 编辑走既有 `RagService.reindex` 路径，不额外引入活动锁。

## 3. 设计要点

- **图谱 = metadata 现算**：节点 = `chunk`（含 `doc_id`/`chunk_type`/`parent_id`/`heading_path`/`chunk_index`/`links`）；边 = `contains`(doc→chunk)、`section`(heading→chunk)、`parent_of`(parent→child)、`links_to`(chunk→其他文档 chunk，由 `links` 归一化后命中 `META_SOURCE_PATH`)。邻接只在进程内构建，不落库。
- **Agent 编辑闭环**：`rag_get_chunk` 返回当前内容 → Agent 组新文本 → `rag_edit_chunk(expectedText=get 返回的 text)` → 服务端重切比对/定位 → 写回归档 → 重嵌 → 返回 receipt（signature、chunkCount、editedChunkIndex）→ Agent 可 `rag_search` 验证。
- **编辑 = 回写 + 重切（C1）**：不维护覆盖层；失败即回滚（归档签名置空 → 下次启动自动重切）。
- **编辑定位**：按现行签名重切定位 chunk_index 对应原文区间；用 `expectedText` 精确匹配保护并发覆盖。
- **`links` 提取**：`MarkdownChunker` 解析 `[x](path.md|path.markdown)` 与 `[[wikilink]]`；与切分决策无关。
- **`links_to` 冲突**：`META_SOURCE_PATH` 归一化后精确匹配，多候选标 `ambiguous` 并写日志；零命中不产出边。

## 4. 包结构（v0.0.5 新增）

```text
rag/graph/           ChunkGraph（节点/边/邻接）、LinkResolver（links_to 归一化 + ambiguous 标记）、
                     GraphExport（JSON 约定：nodes/edges/含 documents/sections/chunks 三层）
mcp/RagMcpTools.java rag_chunks / rag_graph / rag_search expandGraph /
                     rag_get_chunk / rag_edit_chunk / rag_delete_chunk / rag_graph_export
mcp/McpServerConfig.java（注册 6 个新工具）
```

## 5. 关键数据流

**图查询**：`rag_graph` → 读全部 chunk metadata（sqlite: SQL；qdrant: scroll）→ 构建 adjacency → 深度 ≤2 BFS → 输出 nodes/edges JSON（不含正文，正文走 `rag_get_chunk`）。

**编辑**：`rag_edit_chunk` → 归档原文 → 按签名重切 → `expectedText` 校验 → 原文定位（顺序游标第 k 个匹配）→ tmp+ATOMIC_MOVE 落盘 → `RagService.reindex` 重嵌 → 返回 receipt。

**导出**：`rag_graph_export` 同图查询但产物为独立文档；`limit` 防止爆炸。

## 6. 影响面与限制

- **切分不变**：v0.0.5 签名必须升 `v0.0.5`（版本同步），升级触发一次全量重切（归档自动兜底）。
- **Agent 双读约束**：编辑前应先 `rag_get_chunk`；`rag_chunks` 提供 `limit`，大库 Agent 自己分页。
- **删除不可逆**：`rag_delete_chunk` 要求 `confirm=true` 且仅在原文删除段落后生效；删除失败原文不落盘。
- **图导出体积**：`limit` 上限；全量导出体积随语料线性增长。

## 7. 回滚与演进

- 回滚：回退到上一镜像即可；归档原文与索引都可用 `rag_rebuild` 从现行配置重建。
- 演进（v0.0.6 候选）：覆盖层编辑语义（C2）、实体/关系图谱（需解禁 Chat，违反红线须先抬禁）、前端可视化、增量重嵌（非整篇）。
