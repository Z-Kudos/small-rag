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
- 本仓库后续版本只围绕单机 RAG 质量、切分、导入与资源占用演进。

### 6.1 体积基线（v0.0.4 实测）

| 对象 | 体积 |
| --- | --- |
| 容器镜像 `small-rag:0.0.4` | 630 MB |
| `target/small-rag-0.0.4.jar` | 157 MB |
| `/opt/java/openjdk`（Temurin 25 JRE） | 200 MB |
| `/qdrant`（二进制 + static） | 112 MB |
| 首次启动下载的 GGUF 模型 | 约 640 MB（落在 `/data` 卷，不计入镜像） |

fat jar 内占比最高的依赖：`openai-java-core` 65.3 MB、netty 16.6 MB、grpc 11.5 MB、kotlin 5.6 MB。

### 6.2 候选优化项（尚未实施）

按收益/风险排序，实施前需单独立项并重新验证单测与容器 E2E。

| # | 项目 | 预计收益 | 风险 | 结论 |
| --- | --- | --- | --- | --- |
| C1 | 用 `jlink` 定制运行时替换完整 Temurin JRE | 镜像约 **-136 MB**（JRE 200 → 64 MB） | 低；已用 63.8 MB 运行时冒烟通过 `/actuator/health` | 首选 |
| C2 | 排除非 Linux-x86_64 的 `netty-codec-native-quic` | 镜像约 **-8.9 MB** | 低；仅当不使用 HTTP/3 时 | 可做 |
| C3 | 删除 `/qdrant/static`（Dashboard 资源） | 镜像约 **-17 MB** | 中；容器内失去 Web UI，Qdrant 绑定回环时实际不可用 | 可选 |
| C4 | 去掉 `spring-ai-starter-model-openai`，改直依赖 `spring-ai-openai` + `spring-ai-autoconfigure-model-openai` | fat jar 约 **-30 MB**（剔除 webclient/restclient、chat-client、chat-memory、kotlin、jtokkit） | 高；需核对 Embedding 自动装配所需 bean | 需专项评估 |
| C5 | 替换 `openai-java-core` 为自研轻量 HTTP 客户端 | 磁盘约 **-65 MB** | 高；牵动重试、超时、`CancellationScope` 取消语义 | **不做** |
| C6 | 移除 Actuator / Micrometer | 约 1–2 MB | 中；破坏 `/actuator/health` | **不做** |
| C7 | Qdrant 改为外部容器/服务 | 镜像 **-112 MB** | 高；破坏单容器承诺，编排与文档全改 | 与 v0.0.4 目标冲突，**不做** |
| C8 | 使用预编译 `llama-server` 替代源码编译 | 镜像不变，仅缩短构建时间 | 中；需固定 ABI 与校验 | 可选，非体积项 |

> 结论：v0.0.4 已发布并冻结，以上优化项不在本版本实施；下一版本若立项，优先 C1 + C2（合计约 -145 MB，风险低），C4 单独评估。
