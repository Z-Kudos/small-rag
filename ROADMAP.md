---
title: ROADMAP
status: done
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
related: [docs/v0.0.5/plan.md, docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md, DEV-MEMORY.md]
---

# ROADMAP — v0.0.5（切片图谱与编辑管理）

> 当前版本 `0.0.5`（已发布，`main` tag `v0.0.5`）；上一版 `v0.0.4` 已发布（2026-11-11；含 WP4 多形态部署：SQLite 可选向量库 + 四形态开关）。下一版本待确认。
> 工作包与验收细节见 [`docs/v0.0.5/plan.md`](docs/v0.0.5/plan.md)。

## 1. 版本目标

- v0.0.4 主题：**单机轻量化**——保留完整 RAG，移除分布式基础设施（已发布）。
- v0.0.5 主题：**切片图谱与编辑管理**——让 AGENT 通过 MCP 操作和编辑 chunk，支持 AGENT 编辑管理知识库。

- 单模块、单容器、单端口。
- 无 Spring Cloud / Gateway / Config Server / Consul / Redis / Redisson。
- 保留 MCP、Qdrant、4 种切分、Parent-Child、目录导入、异步重建/看门狗、归档。

## 1.1 v0.0.5 版本目标与验收

- 主题：**切片图谱与编辑管理**——AGENT 可浏览 chunk 树、查看/编辑/删除 chunk、导出图 JSON；编辑回写归档并整篇重切（C1 语义）。
- 目标（全部可验证）：
  1. `rag_chunks` / `rag_graph` / `rag_search` 的 `expandGraph` 参数：图查询 MCP 工具可用。
  2. `rag_get_chunk` / `rag_edit_chunk` / `rag_delete_chunk`：Agent 面编辑 API 可用（`expectedText` 校验 + `confirm=true`）。
  3. `rag_graph_export`：全库 JSON 可直接喂给可视化工具，不做前端。
  4. MCP 工具 13 → 19，`mvn test` 全绿（目标 ≥180）。
- 签名升 `v0.0.5`（版本同步）：升级触发一次全量重切，归档自动兜底。
- 红线保持：不调用 Chat/生成模型；不做 GraphRAG；不做 UI。
- 发布流程与 v0.0.4 一致：feature → develop → release/0.0.5 → main + tag → 回灌 develop。

详见 [`docs/v0.0.5/plan.md`](docs/v0.0.5/plan.md)。

## 2. 运行形态（v0.0.4–v0.0.5 无变化）

| 维度 | v0.0.4 |
| --- | --- |
| 部署 | 单容器 `all-in-one` |
| 进程 | app + Qdrant + llama-server（supervisord） |
| MCP 传输 | `STREAMABLE` |
| 索引互斥 | 进程内 `IndexActivityGuard` |
| 任务状态 | Qdrant `<collection>_reindex_state` 快照 + 检查点 |
| 配置 | 环境变量 / `.env` |
| 对外端口 | `18080` |

## 3. 工作包（v0.0.4 已发布）

- ✅ **WP1 移除分布式**：删除 Gateway、Config Server、Consul、Redis/Redisson、分布式锁与任务视图。
- ✅ **WP2 单实例锁**：`IndexActivityGuard` 改为进程内 owner 标记；`ReindexTaskService` 只保留单机任务恢复。
- ✅ **WP3 测试与文档**：142 单测 + 3 个 Qdrant 容器 E2E；`docs/v0.0.4` 建立。

## 4. 验收标准（v0.0.4，已通过）

1. `mvn clean package` 通过，单模块产出 `small-rag-0.0.4.jar`。
2. `mvn test` 全绿（142）。
3. `mvn verify -Pcontainer-it` 通过（Qdrant E2E 3 个）。
4. `docker compose up -d --build` 单容器可用，`/mcp` 13 个工具可见。
5. POM 不含 Spring Cloud / Redisson / Lettuce。
6. 单实例下重建与目录导入互斥；重启后任务可续接。

## 5. 非目标

- 多副本 / HA / 滚动升级。
- Gateway / Config Server / Consul / Redis / Redisson。
- 跨副本任务查询、续接、取消。
- 配置中心与服务发现。

## 6. 后续

- 需要多副本时使用封存项目 `~/Projects/cloud-rag`。
- 本仓库后续版本只围绕单机 RAG 质量、切分、目录导入与多形态部署（SQLite 可选、同一镜像）演进。
