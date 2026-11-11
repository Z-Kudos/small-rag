---
title: v0.0.4 单机轻量化规划
status: done
version: 0.0.4
updated: 2026-11-11
owners: [zjk]
template: docs/templates/plan.md
related: [ROADMAP.md, DEV-MEMORY.md, docs/v0.0.4/design/architecture.md, docs/v0.0.4/design/limitations.md, docs/v0.0.4/adr/0001-single-node-scope.md]
---

# v0.0.4 规划：单机轻量化

> 把 small-rag 收敛为只支持单机单容器的轻量版本；原分布式版本已封存为 `cloud-rag`。
>
> 本版本的“轻量化”指**移除分布式组件**，不是压缩镜像体积，也不是替换技术栈。

## 1. 背景与目标

**背景**

- v0.0.3 引入了 Spring Cloud Gateway、Config Server、Consul、Redis/Redisson、分布式任务锁与多副本任务视图。
- 这些能力只对多副本部署有价值，对单机用户增加了依赖、内存与运维成本。
- 原分布式版本已另存为 `~/Projects/cloud-rag`，本仓库只保留单机能力。

**目标**

1. small-rag 只支持单机单容器部署，`docker compose up -d --build` 即可使用。
2. 移除 Spring Cloud、Consul、Redis、Redisson、Gateway、Config Server。
3. 保留完整单机 RAG：MCP、Qdrant、4 种切分、Parent-Child、目录导入、异步重建/看门狗、归档。
4. 单实例下用进程内活动锁串行化重建任务与目录导入。
5. 单元测试与 Qdrant 容器 E2E 全部通过。

**非目标**

- 不支持多副本、HA、跨副本任务查询/续接。
- 不保留 `app-only`、`compose.distributed.yaml`、Gateway/Config Server 镜像。
- 不做配置中心、服务发现、Redis 锁。
- 不做镜像体积优化（`jlink` 定制运行时、删除 Qdrant 静态资源、排除 QUIC native 等），也不替换技术栈（嵌入式向量库、去 Spring Boot 等）。

## 2. 现状与差距

- 仓库是多模块：`app` / `gateway` / `config-server`。
- `app` 依赖 Spring Cloud Consul、Config Client、CircuitBreaker、Redisson。
- 重建任务使用 `FencedLease` + 跨副本状态读穿缓存 + Qdrant 锁镜像。
- 目录扫描需要 `SourcesLeaderElection`。

## 3. 工作包

### WP1 移除分布式基础设施 — 已完成

**变更点**

- 删除 `gateway`、`config-server`、`config-repo`、`compose.distributed.yaml`。
- 根 POM 恢复单模块；移除 Spring Cloud BOM、Consul、Config Client、CircuitBreaker 包装、Redisson。
- 检索熔断改回直接使用 Resilience4j。
- 删除 `distributed/`、`DistributedLockConfig`、`DistributedProperties`、`ArchiveProperties`、`ClusterStartupGuard`、`McpTransportGuard`、`RedissonHealthIndicator`、`IndexLockHealthIndicator`、`SourcesLeaderElection`。

**验收**

- `mvn clean package` 只产出 `small-rag-0.0.4.jar`。
- `mvn dependency:tree` 无 Spring Cloud / Redisson / Lettuce。

### WP2 单实例锁与任务恢复 — 已完成

**变更点**

- `IndexActivityGuard` 改为进程内 owner 标记，`tryAcquire` 使用 CAS，释放按 label 内容比较。
- `ReindexTaskService` 移除任务租约、fencing token、跨副本状态读取；恢复 `interrupted` 任务时直接重建、续接或取消。
- `ReindexStateStore` / `QdrantReindexStateBackend` 只保存任务快照与检查点，不再保存锁镜像。
- `SourceSyncService` 去掉 leader 选举，启动/定时/watcher 扫描直接执行。

**验收**

- 同一时刻只能有一个重建任务或目录导入。
- 重启后未完成任务为 `interrupted`，`resume-on-startup` 或 `rag_task_resume` 可续接。
- `ReindexTaskServiceTest` 覆盖并发拒绝、硬超时、宽限期、恢复、取消、重启挂起。

### WP3 文档与验证 — 已完成

- 新建 `docs/v0.0.4`；`docs/v0.0.3` 保持冻结，描述已封存的分布式版本。
- 更新 README 中英文、AGENTS、ROADMAP、DEV-MEMORY。
- 单测与 Qdrant Testcontainers E2E 通过。

## 4. 里程碑与排期

| 里程碑 | 内容 | 出口标准 |
| --- | --- | --- |
| M1 依赖收敛 | WP1 | POM 无 Spring Cloud/Redis/Redisson，单模块构建通过 |
| M2 单机行为 | WP2 | 进程内互斥、任务恢复、目录导入全部通过测试 |
| M3 发布准备 | WP3 | 142 单测 + 3 容器 E2E 通过，文档完成 |

## 5. 验证矩阵

| 场景 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 构建 | `mvn clean package` | 通过 |
| 单测 | `mvn test` | 142 全绿 |
| Qdrant E2E | `mvn verify -Pcontainer-it` | 3 全绿 |
| 启动 | `java -jar target/small-rag-0.0.4.jar` | `/actuator/health` UP |
| MCP | `initialize` / `tools/list` | 13 个工具可见 |

## 6. 决策记录

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 版本范围 | v0.0.4 只做单机，不保留分布式开关 |
| D2 | 分布式版本 | 另存为 `~/Projects/cloud-rag`，保留历史并去掉 remote |
| D3 | Spring Cloud | 完全移除；熔断直接使用 Resilience4j |
| D4 | 锁 | 进程内 `IndexActivityGuard`，不使用 Redis/Redisson |
| D5 | 文档 | 新建 `docs/v0.0.4`，冻结 `docs/v0.0.3` |
| D6 | “轻量化”口径 | 只指移除分布式组件；不压镜像体积，不替换技术栈 |

## 7. 完成定义（DoD）

- [x] `mvn clean package` 通过
- [x] `mvn test` 142 全绿
- [x] `mvn verify -Pcontainer-it` 3 全绿
- [x] POM 无 Spring Cloud / Redis / Redisson
- [x] README / AGENTS / ROADMAP / DEV-MEMORY 同步
- [x] `docs/v0.0.4` 建立，`docs/v0.0.3` 保持冻结
- [ ] 真实部署环境冒烟（单容器 + 模型下载）由发布流程执行
