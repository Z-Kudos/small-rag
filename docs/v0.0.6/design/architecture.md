---
title: v0.0.6 架构与设计要点（CI/CD、标签、有效期、内存）
status: draft
version: 0.0.6
updated: 2026-11-17
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.6/plan.md, docs/v0.0.6/adr/0001-ci-tagging-and-retention.md]
---

# v0.0.6 架构与设计要点

> 说明 v0.0.6 新增的 CI/CD、标签、TTL 与内存优化如何嵌入既有单机 RAG 架构。

## 1. 背景与范围

- 背景：v0.0.5 已发布并冻结；本版本在不改切分口径的前提下补齐工程化与数据治理能力。
- 范围：双平台 CI/CD、文档分类 / 标签与过滤、向量数据有效期、运行时内存治理、版本切换与文档治理。
- 不做：改切分算法、调用 Chat 模型、引入 Spring Cloud、镜像体积优化。

## 2. 约束

- 版本 / 依赖约束：JDK 25、Spring Boot 4.2.0-RC1、Spring AI 2.0.2；`maven.compiler.proc=full`；Jackson 3（`tools.jackson.*`）。
- 运行环境约束：单容器三进程（app + Qdrant + llama.cpp）共享内存；Gitee Go `build@maven` 最高 JDK 17，需在容器内构建。
- 兼容性约束：MCP 工具从 19 增至 23，旧工具签名保持向后兼容（新增参数为可选）；TTL 默认关闭。

## 3. 设计要点

### 3.1 CI/CD 分类打标签

- GitHub Actions：`ci.yml` 先输出 version / environment / image_tags；`publish-image.yml` 推 GHCR；`release.yml` 校验 tag 与 pom 一致后建 Release；`auto-label.yml` 给 PR/Issue 打标签。
- Gitee Go：流水线位于 `.workflow/`（非 `.gitee/workflows/`）；JDK 25 通过 `maven:3.9-eclipse-temurin-25` 容器构建；PR 打标签调 Gitee Open API，Issue 由 `scripts/gitee-webhook-labeler.py` 处理。
- 镜像分类：`develop` → `<version>-SNAPSHOT` / `dev-<sha>`；`release/*` → `<version>-rc.<build>`；`main` → `<version>`；tag `vX.Y.Z` → `<version>` + `latest`。

### 3.2 文档标签与过滤

- 元数据：`DocumentTags.normalize` 在 `RagService.baseMetadata` 把业务 `tags`、`category` 与 `frontmatter_tags` 统一为 `tags`（`List<String>`）与 `category`（单值字符串）。
- 过滤：`RagService.searchInternal` 把 `tags IN [...]` 与 `category EQ`、`chunk_type=child` 以 `AND` 组合；放大候选倍数保证过滤后召回量。
- 后端：Qdrant 的 `IN` 映射为 `Match.Any`；SQLite 的 `SqliteFilters` 扩展为支持数组字段的 IN/EQ。

### 3.3 向量数据有效期

- 配置：`rag.retention.*`（env `RAG_RETENTION_*`），默认关闭，TTL 90 天。
- 判定：归档摘要 `updatedAt`（与写入向量的 `ingested_at` 同步刷新）与 `now - ttl` 比较。
- 清理：`RetentionCleanupService` 的 daemon 定时线程与 MCP 手动入口共用逻辑；按 `doc_id` 删向量、可选删归档、清导入墓碑；`IndexActivityGuard.RETENTION` 保证与重建 / 目录导入互斥。

### 3.4 内存治理

- 容器：`mem_limit=4g`（remote-only 可降到 1536m）。
- JVM：显式 `-Xmx1024m` + `MaxMetaspaceSize` / `MaxDirectMemorySize` / `-Xss512k` + `ExitOnOutOfMemoryError`，替代 `MaxRAMPercentage=75`。
- llama.cpp：ctx 8192 → 4096，KV Cache q8_0，线程默认 2。
- Qdrant：常驻内存 60% 上限、搜索 / 优化 / indexing / service workers 线程上限。
- SQLite：`cache_size=-4000`、`mmap_size=0`。
- 代码：`ChunkCatalog.findByPointId` 定点查询替代全库扫描；snippet 先截断再归一化，避免长文本整段复制。

## 4. 包结构

- `rag/metadata/`：`DocumentTags`（规范化）、`TagService`（设置 / 统计）。
- `rag/retention/`：`RetentionCleanupService`（定时 + 手动清理）。
- `mcp/`：`RagMcpTools`（19）+ `TagMcpTools`（2）+ `RetentionMcpTools`（2），统一由 `McpServerConfig` 显式注册。
- `.github/workflows/`、`.workflow/`、`scripts/gitee-webhook-labeler.py`：CI/CD 与自动打标签配置 / 脚本。

## 5. 关键数据流

1. 入库：`rag_ingest(text, {tags, category})` → `baseMetadata` 归一化 → 向量 payload 写入 `tags` 数组 / `category` 单值 → 归档保存业务元数据。
2. 过滤检索：`rag_search(query, tags, category)` → `searchInternal` 构造 `AND(chunk_type=child, tags IN, category EQ)` → 向量库过滤 → 命中结果。
3. TTL：定时触发 / MCP `rag_cleanup_expired` → 取归档摘要 → 过滤 `updatedAt < cutoff` → 获取活动锁 → 按 `doc_id` 删向量 + 可选归档 → 释放锁 → 返回统计。

## 6. 影响面与限制

| 维度 | 影响 |
| --- | --- |
| 接口 | MCP 工具 19 → 23；`rag_search` / `rag_query` 新增可选 `tags` / `category` 参数 |
| 数据 | chunk payload 新增 `tags` 数组 / `category`；归档元数据新增 / 更新对应字段 |
| 兼容性 | 新参数为可选，既有调用不受影响；TTL 默认关闭；签名升 `v0.0.6` 触发一次全量重切 |
| 运维 | 需配置 Registry / PAT / Webhook；内存参数可用同名 env 覆盖；容器限额需按宿主规格调整 |

## 7. 回滚与演进

- 回滚方式：TTL 设 `RAG_RETENTION_ENABLED=false` 即回到无清理行为；内存 env 回退到旧值（`MaxRAMPercentage=75`、ctx 8192）；CI/CD 配置回滚到上一版本文件。
- 后续演进：Qdrant `tags` / `category` payload 索引（规模增大后补）；TTL 物理清理改用后端条件删除以减少逐文档调用；图查询改为按需遍历而非全库构建。
