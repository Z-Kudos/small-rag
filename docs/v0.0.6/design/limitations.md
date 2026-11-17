---
title: v0.0.6 已知限制与风险
status: draft
version: 0.0.6
updated: 2026-11-17
owners: [zjk]
template: docs/templates/design.md
related: [README.md, docs/v0.0.6/plan.md, docs/v0.0.6/adr/0001-ci-tagging-and-retention.md]
---

# v0.0.6 已知限制与风险

> 列出 v0.0.6 新增能力的限制与风险，供部署与容量评估参考。

## 1. 背景与范围

- 目标：记录 CI/CD、标签过滤、TTL、内存优化的限制与风险。
- 范围：仅本版本新增部分；既有 v0.0.5 限制见冻结目录 `docs/v0.0.5/design/limitations.md`。

## 2. 约束

- 单机单容器单端口；TTL 依赖进程内互斥锁，不支持多副本。
- Gitee Go 凭据 / registry 依赖平台配置；GitHub 镜像发布依赖 GHCR 权限。

## 3. 限制清单

### CI/CD

| # | 限制 | 触发条件 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| C1 | Gitee Go 的内置变量 / `GITEE_PARAMS` 跨阶段传递未在本机实测 | 使用 Gitee Go 流水线 | 镜像 tag 分类可能需按实例微调 | 用单个 shell step 内完成构建 + 打标签 + 推送，避免跨阶段 |
| C2 | Gitee `build@maven` 不支持 JDK 25 | 选内置 Maven 插件 | 构建失败 | 在 `maven:3.9-eclipse-temurin-25` 容器内构建 |
| C3 | PR/Issue 自动打标签需要预建 label | label 不存在 | Gitee / GitHub API 报错 | 按工作流注释预建 label |
| C4 | Issue 打标签需常驻 Webhook 服务 | Gitee Go 无 issue 触发器 | 不部署脚本则 Issue 不会自动打标签 | 部署 `scripts/gitee-webhook-labeler.py` |

### 文档标签与过滤

| # | 限制 | 触发条件 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| T1 | Qdrant `tags` / `category` 无 payload 索引 | 数据量增大 | 过滤扫描成本上升 | 后续按需建 keyword payload index |
| T2 | `tags` 过滤是“命中任一”，无 AND 语义 | 需同时命中多标签 | 结果偏宽 | 用 `category` 做单值精确约束 |
| T3 | `rag_set_tags` 会整篇重切 | 修改标签 | 大文档有 EMBEDDING 耗时 | 低峰期操作；受重建任务看门狗保护 |
| T4 | 旧归档元数据未含 `tags` 数组 | 从 v0.0.5 升级 | 需重切后才可过滤 | 签名升 v0.0.6 触发一次全量重切 |

### 向量数据有效期

| # | 限制 | 触发条件 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| R1 | 归档 `updatedAt` 在 `markIndexed` 时刷新 | 重新索引旧文 | 语义是“最后入库时间”而非“创建时间” | 需要创建时间语义时另加字段并升索引版本 |
| R2 | 清理期间重建 / 目录导入被跳过 | 活动锁被占用 | 当轮不清理 | 下轮自动重试 |
| R3 | `delete-archive=false` 时过期文档会重复进入统计 | 只删向量留归档 | 每轮重复触发删除调用 | 生产开启 `delete-archive=true` 或调大 TTL |
| R4 | 无归档文档（归档目录不可写）无法 TTL | 归档降级关闭 | 向量不会被清理 | 保证归档目录可写 |

### 内存

| # | 限制 | 触发条件 | 影响 | 缓解 |
| --- | --- | --- | --- | --- |
| M1 | ctx 4096 + q8 KV Cache 可能截断超长 chunk | chunk 超过 token 上限 | Embedding 被截断 | 通过 `EMBEDDING_CTX_SIZE` 调回 8192 |
| M2 | Qdrant 常驻内存 60% 可能影响大集合性能 | 大规模向量 | 查询延迟上升 | 通过同名 env 调整或关闭配额 |
| M3 | `mem_limit` 过小导致进程 OOM | 宿主规格低 | 容器被 kill | 按实际规格调整 `MEM_LIMIT` / `JAVA_OPTS` |
| M4 | SQLite 页缓存 4MB 在大批量扫描时偏小 | 大批量重建 | 磁盘 IO 增多 | 调大 `cache_size`（需改代码） |

## 4. 影响与缓解

- 升级到 v0.0.6 会因签名版本变化触发一次全量重切；语料越大耗时越久，需在低峰期升级。
- TTL 默认关闭，不会主动删除存量数据；开启前建议先用 `rag_cleanup_expired()` 预演核对命中量。
- 内存相关 env 均可在部署时覆盖，回退路径清晰。

## 5. 演进

- Qdrant 标签字段建 payload 索引，降低过滤扫描成本。
- 图查询改为按需遍历，避免全库构建节点的峰值内存。
- TTL 改用后端条件删除 + 分页枚举，降低逐文档调用开销。
