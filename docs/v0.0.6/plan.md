---
title: v0.0.6 CI/CD、分类标签、有效期与内存优化规划
status: implementing
version: 0.0.6
updated: 2026-11-17
owners: [zjk]
template: docs/templates/plan.md
related: [ROADMAP.md, DEV-MEMORY.md, docs/v0.0.6/adr/0001-ci-tagging-and-retention.md, docs/v0.0.6/design/architecture.md, docs/v0.0.6/design/limitations.md]
---

# v0.0.6 规划：CI/CD 与分类打标签、向量有效期、内存优化

> 补齐双平台流水线与镜像分类标签，给知识库加上文档分类 / 标签与过滤检索、向量数据有效期（TTL），并治理单容器运行时内存占用。

## 1. 背景与目标

- 背景：v0.0.5 已发布（19 个 MCP 工具），但 CI/CD 只有 GitHub 单测 + 容器 E2E，缺 Gitee Go、缺镜像 / git 版本分类标签与 PR/Issue 自动打标签；知识库元数据只有 `frontmatter_tags` 逗号字符串，无分类字段与按标签过滤；无 TTL；容器无内存上限且 JVM 用 `MaxRAMPercentage=75`，多进程共享内存时易失控。
- 目标（可验证）：
  1. GitHub Actions 四个工作流（CI / 镜像发布 / Release / 自动打标签）、Gitee Go 两个流水线存在且语法有效；镜像按 `develop` / `release/*` / `main` / tag `vX.Y.Z` 分类打 tag。
  2. 文档支持 `tags`（数组）/ `category`（单值）；`rag_search` / `rag_query` 可按 `tags`（任一）/ `category` 过滤；新增 `rag_set_tags` / `rag_list_tags`，MCP 19 → 23。
  3. `rag.retention.*`（env `RAG_RETENTION_*`，默认关闭）定时清理 + MCP `rag_cleanup_expired` / `rag_retention_status`；清理与重建 / 目录导入互斥。
  4. 内存：容器 `MEM_LIMIT=4g`、JVM 显式内存池、llama.cpp ctx 4096 + q8 KV Cache + 2 线程、Qdrant 配额与线程上限、SQLite 页缓存上限、`ChunkCatalog.findByPointId` 定向查询。
  5. `mvn clean package` 成功；`mvn test` 206 全绿；README 双语 / DEV-MEMORY / AGENTS / ROADMAP 同步。
- 非目标：不引入 Spring Cloud；不调用 Chat / 生成模型；不改切分算法口径（仅版本同步触发一次全量重切）；不做镜像体积优化（jlink / 删静态资源等仍禁止）。

## 2. 现状与差距

| # | 能力 | 现状 | 目标 | 差距 / 影响 |
| --- | --- | --- | --- | --- |
| G1 | CI/CD | 仅 `.github/workflows/ci.yml`（单测 + E2E） | 双平台、多工作流、镜像分类标签、自动打标签 | 缺 Gitee Go、缺发布 / 镜像 / 标签流水线 |
| G2 | 文档标签 | `frontmatter_tags` 逗号字符串，无分类 | `tags` 数组 + `category` 单值，可过滤检索 | 需统一元数据表示并在两端支持过滤 |
| G3 | TTL | 无 | 定时 + MCP 手动清理 | 需配置、判定、互斥与清理入口 |
| G4 | 内存 | 无容器限额，`MaxRAMPercentage=75`，llama ctx 8192 | 显式限额与较低默认占用 | 三进程共享容器内存，需分别设限 |

关键代码位置：

- `RagService.baseMetadata` / `searchInternal`：标签元数据归一化与过滤注入点。
- `SqliteFilters`、`QdrantChunkCatalog`：数组字段过滤与定向查询。
- `IndexActivityGuard`：新增 `RETENTION` owner，串行化清理。
- `Dockerfile`、`compose.yaml`、`docker/start-llama.sh`、`docker/start-qdrant.sh`：内存参数。
- `.github/workflows/`、`.workflow/`、`scripts/gitee-webhook-labeler.py`：双平台流水线。

## 3. 工作包

### WP1 双平台 CI/CD 与分类打标签

- **目标**：GitHub Actions 与 Gitee Go 覆盖构建 / 测试 / 镜像发布 / 标签分类 / PR-Issue 自动打标签。
- **变更点**：新增 `.github/workflows/publish-image.yml`、`release.yml`、`auto-label.yml`、`.github/labeler.yml`；重写 `ci.yml` 增加版本分类 job；新增 `.workflow/CIPipeline.yml`、`LabelPipeline.yml`、`scripts/gitee-webhook-labeler.py`。
- **验收**：所有 YAML `safe_load` 通过；镜像 tag 分类规则清晰；Gitee JDK 25 在容器内构建绕过插件版本限制。
- **风险**：Gitee Go 内置变量语义与镜像 registry 需平台实测；PAT / Registry 凭据需在仓库配置。

### WP2 文档分类 / 标签与过滤检索

- **目标**：`tags` / `category` 统一元数据并按标签过滤检索。
- **变更点**：新增 `DocumentTags`、`TagService`、`TagMcpTools`；`RagService` 过滤注入；`SqliteFilters` 数组 IN；Javadoc 与 MCP instructions。
- **验收**：`DocumentTagsTest` / `TagServiceTest` / `SqliteFiltersTest` / `RagServiceTest` 新增用例通过；MCP 23 工具可见。
- **风险**：Qdrant 数组字段过滤性能（当前无 payload 索引，规模大时后续补索引）。

### WP3 向量数据有效期

- **目标**：TTL 定时清理 + MCP 手动清理，且与重建 / 目录导入互斥。
- **变更点**：`RagProperties.Retention`、`RetentionCleanupService`、`RetentionMcpTools`、`IndexActivityGuard.RETENTION`。
- **验收**：`RetentionCleanupServiceTest` 覆盖 dryRun / 真删 / TTL 覆盖 / 锁互斥；默认关闭不影响存量行为。
- **风险**：归档 `updatedAt` 因 `markIndexed` 刷新，语义是“最后入库 / 重切时间”；`TAG` 清理误删需通过 dryRun + confirm 双保险。

### WP4 内存优化

- **目标**：容器与三个进程的内存占用可预期、可配置。
- **变更点**：`Dockerfile` JVM 参数 / llama ctx、`compose.yaml` `mem_limit` 与 env、`.env`、`docker/start-llama.sh`、`docker/start-qdrant.sh`、`SqliteSupport` PRAGMA、`ChunkCatalog.findByPointId`、snippet 先截断。
- **验收**：Llama / Qdrant 脚本 `sh -n` 通过；定向查询与全量扫描行为一致（`ChunkEditServiceTest` 通过）。
- **风险**：KV Cache q8 / ctx 4096 对超长 chunk 可能需调回；通过 env 覆盖即可回退。

### WP5 版本切换与文档

- **目标**：v0.0.5 冻结、v0.0.6 开发文档建立，版本基线全量同步。
- **变更点**：`pom.xml` / `ChunkingSignature` / `application.yml` / `.env` / `compose.yaml` / README 双语 / DEV-MEMORY / AGENTS / ROADMAP 升 0.0.6；冻结 `docs/v0.0.5/`；新建本目录。
- **验收**：全仓无遗留 0.0.5 的当前版本引用（历史说明除外）；`mvn clean package` 通过。
- **风险**：`ChunkingSignature` 版本同步触发用户升级时一次全量重切，需在 README 迁移说明写明。

## 4. 里程碑与排期

> 本版本不排期，按工作包顺序推进；所有工作包均在本地完成。

| 里程碑 | 覆盖 | 出口标准 | 预估 |
| --- | --- | --- | --- |
| M1 | WP1–WP4 | 206 单测绿、YAML / shell 校验通过 | 已完成 |
| M2 | WP5 | 版本切换完成、文档同步、包构建通过 | 已完成 |

## 5. 验证矩阵

| 场景 | 单容器（qdrant + llama） | 远程 Qdrant / Embedding | SQLite |
| --- | --- | --- | --- |
| 构建 | `mvn clean package` 通过 | 同左 | 同左 |
| 单测 | 206 全绿，无外部依赖 | 同左 | 同左 |
| 标签过滤 | 待容器 E2E 回归 | 待验证 | 单测覆盖 SQLite 数组过滤 |
| TTL 清理 | 默认关闭，待开启后人工验证 | 待验证 | 待验证 |
| 内存 | `MEM_LIMIT` / JVM / llama / Qdrant 参数生效 | 可下调至 1536m | 同默认 |

## 6. 决策记录

| # | 决策 | 结论 | 日期 |
| --- | --- | --- | --- |
| D1 | 双平台 CI/CD 与标签分类 | 见 `adr/0001-ci-tagging-and-retention.md` | 2026-11-17 |
| D2 | TTL 定时 + 手动双入口 | 默认关闭；手动默认 dryRun + confirm；见 ADR | 2026-11-17 |
| D3 | 版本切换 0.0.5 → 0.0.6 | 人工确认后切换，签名同步升 v0.0.6 | 2026-11-17 |

不可逆或影响面大的决策另建 `adr/0001-ci-tagging-and-retention.md`。

## 7. 完成定义（DoD）

- [x] 构建与测试通过（`mvn clean package`、`mvn test` 206 全绿）
- [x] CI/CD 双平台工作流与分类打标签落地，语法校验通过
- [x] 标签 / TTL / 内存优化代码与单测落地
- [x] 文档同步（README 双语 / ROADMAP / DEV-MEMORY / AGENTS）
- [x] 版本切换 0.0.6、docs/v0.0.6 建立、docs/v0.0.5 冻结
- [x] 代码与版本切换已提交并推送双远程 `develop`（提交 `c012524`；未打 tag / Release）
- [ ] 容器 / 远程 / SQLite 三形态容器 E2E 回归（待部署环境）
- [ ] CI/CD 平台侧凭据配置后验证流水线实际运行（Registry / Token / Webhook）
