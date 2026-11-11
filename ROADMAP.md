---
title: ROADMAP
status: done
version: 0.0.4
updated: 2026-11-11
owners: [zjk]
related: [docs/v0.0.4/plan.md, docs/v0.0.4/adr/0001-single-node-scope.md, DEV-MEMORY.md]
---

# ROADMAP — v0.0.4（单机轻量化）

> 当前开发版本 `0.0.4`（`develop`）；上一版 `v0.0.3` 已发布，分布式版本已封存为 `cloud-rag`。
> 工作包与验收细节见 [`docs/v0.0.4/plan.md`](docs/v0.0.4/plan.md)。

## 1. 版本目标

v0.0.4 主题：**单机轻量化**——保留完整 RAG，移除分布式基础设施。

- 单模块、单容器、单端口。
- 无 Spring Cloud / Gateway / Config Server / Consul / Redis / Redisson。
- 保留 MCP、Qdrant、4 种切分、Parent-Child、目录导入、异步重建/看门狗、归档。

## 2. 运行形态

| 维度 | v0.0.4 |
| --- | --- |
| 部署 | 单容器 `all-in-one` |
| 进程 | app + Qdrant + llama-server（supervisord） |
| MCP 传输 | `STREAMABLE` |
| 索引互斥 | 进程内 `IndexActivityGuard` |
| 任务状态 | Qdrant `<collection>_reindex_state` 快照 + 检查点 |
| 配置 | 环境变量 / `.env` |
| 对外端口 | `18080` |

## 3. 工作包

- ✅ **WP1 移除分布式**：删除 Gateway、Config Server、Consul、Redis/Redisson、分布式锁与任务视图。
- ✅ **WP2 单实例锁**：`IndexActivityGuard` 改为进程内 owner 标记；`ReindexTaskService` 只保留单机任务恢复。
- ✅ **WP3 测试与文档**：142 单测 + 3 个 Qdrant 容器 E2E；`docs/v0.0.4` 建立。

## 4. 验收标准

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
