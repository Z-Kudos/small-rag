---
title: ADR-0001 CI/CD 分类打标签与向量数据有效期
status: approved
version: 0.0.6
updated: 2026-11-17
owners: [zjk]
template: docs/templates/adr.md
related: [docs/v0.0.6/plan.md, docs/v0.0.6/design/architecture.md]
---

# ADR-0001：CI/CD 分类打标签与向量数据有效期

## 1. 背景

- 问题：CI/CD 缺少 Gitee Go 与镜像 / git 版本分类标签、PR/Issue 自动打标签；知识库缺少分类 / 标签元数据与按标签过滤；向量数据无有效期管理，无法自动清理过期内容。
- 触发原因：v0.0.5 已发布并冻结，需要在不升切分口径的前提下补齐工程化与数据治理能力；单容器内存占用需要治理。
- 相关约束：JDK 25 / Spring Boot 4 / Spring AI 2.0.2；单机单容器；Gitee Go 的 Maven 插件最高 JDK 17；MCP 工具需向后兼容。

## 2. 决策

- 决定：
  1. 双平台 CI/CD：GitHub Actions 四个工作流（CI / 镜像发布 / Release / 自动打标签），Gitee Go 两个流水线；镜像按 `develop` / `release/*` / `main` / tag `vX.Y.Z` 分类打 tag；PR/Issue 自动打标签。
  2. 文档分类 / 标签：统一为 `tags`（数组）+ `category`（单值），`rag_search` / `rag_query` 支持按标签过滤；新增 `rag_set_tags` / `rag_list_tags`。
  3. 向量有效期：`rag.retention.*` 定时清理 + MCP `rag_cleanup_expired`，默认关闭，手动清理默认 dryRun 且需 `confirm=true`；与重建 / 目录导入共用进程内活动锁互斥。
  4. 内存：容器设 `mem_limit`，JVM / llama.cpp / Qdrant / SQLite 分别设内存上限与较低默认参数；编辑与图工具改用定向查询与有界 snippet。
- 生效范围：全项目（代码、配置、CI/CD、文档）。
- 生效版本：v0.0.6。

## 3. 备选方案

| 方案 | 做法 | 放弃原因 |
| --- | --- | --- |
| A 只做 GitHub Actions | 忽略 Gitee Go | 项目双远程（Gitee origin + GitHub），国内团队需要 Gitee 流水线 |
| B 用 Gitee `build@maven` 直接构建 | 设 jdkVersion 25 | 插件最高支持 JDK 17，构建会失败；改用 JDK 25 容器 |
| C 标签用逗号字符串等值过滤 | 直接对 `frontmatter_tags` 做 EQ | 多标签语义错误；改为数组 + IN（Qdrant Match.Any / SQLite 数组 IN） |
| D TTL 只做检索时软过滤 | 查到过期片段再丢弃 | 向量与归档持续占用空间；改为物理清理 + 手动预演双入口 |
| E TTL 用分布式锁 | 多副本协调 | 项目是单机单容器，进程内锁已足够，引入分布式组件违反约束 |
| F 只设 `-Xmx` | JVM 堆内限制 | metaspace / direct / native 内存不受 `-Xmx` 约束，三进程共享容器仍会失控 |

## 4. 后果

**正面**

- 双平台都有可复现的构建 / 测试 / 发布流程，镜像 tag 与环境一一对应，便于回滚。
- 标签过滤与 TTL 让知识库具备分类治理与生命周期管理能力，且默认关闭保证存量兼容。
- 内存参数可预期、可用 env 覆盖，降低单容器 OOM 风险。

**负面**

- 新增 4 个 MCP 工具，客户端需更新工具清单（旧调用仍兼容）。
- TTL 判定依赖归档 `updatedAt` 的刷新语义，不是文档创建时间。
- `ChunkingSignature` 升 `v0.0.6` 触发用户升级时一次全量重切。
- Gitee Go 的内置变量与 registry 配置需在实际平台校验。

**缓解**

- README 双语同步升级说明与工具表；TTL 先 dryRun 再 confirm。
- 需要创建时间语义时另加字段并升归档索引版本。
- 全量重切安排在升级后首次启动，由归档自动兜底，并在 README 标注耗时。
- Gitee 流水线全部在单个 shell step 内完成，避免跨阶段变量传递；凭据用仓库环境变量注入。
