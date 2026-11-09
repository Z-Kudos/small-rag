---
title: v0.0.3 单机与集群规划
status: implementing
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
template: docs/templates/plan.md
related: [AGENTS.md, ROADMAP.md, DEV-MEMORY.md, docs/v0.0.3/design/architecture.md, docs/v0.0.3/design/limitations.md, docs/v0.0.3/adr/0001-spring-cloud-scope.md, docs/v0.0.3/adr/0002-spring-cloud-data-plane.md]
---

# v0.0.3 规划：单机与集群

> 主题：**一套语义、两种形态**——standalone 保持零新增外部依赖，distributed 做到可横向扩展、可安全接管。

## 1. 背景与目标

**背景**：v0.0.2 已具备双运行模式与 Redis 租约锁，但多副本仍有两个硬伤——MCP 会话不粘性、重建任务所有权无 fencing；跨副本任务状态也只读本实例内存。

**目标（可验证）**

1. distributed 模式下任意副本都能处理任意 MCP 调用，不依赖粘性会话。
2. 同一时刻只有一个副本能写索引；旧持有者恢复后无法继续写。
3. `rag_task_status` / `rag_task_resume` / `rag_task_cancel` 在任意副本可用。
4. 重复写入不产生重复片段，中断残留可清理。
5. standalone 行为与 v0.0.2 一致，不新增外部依赖。

**非目标**

- 不拆分为多个微服务。
- 不强制使用 Redis / Consul。
- 不引入 Kafka / Spring Cloud Stream / Bus。
- 不改变 MCP 工具签名与 `rag_query` 纯文本语义。
- Spring Cloud Gateway / Config Server 已纳入 v0.0.3 后继变更，见 WP7。

## 2. 原则

1. **集群正确性优先于新功能**：先保证不双写、不丢会话、可观测。
2. **单机零回归**：集群能力在 `smallrag.distributed.enabled=false` 时退化到进程内实现。
3. **状态以外部存储为准**：Qdrant 存快照，Redis 存租约与 fencing；内存只做本实例加速。

## 3. 现状与差距（v0.0.3 开工基线，已核对代码）

> 以下 G1–G8 是开工时的现状；WP1–WP7 已完成对应改造，保留此表用于回溯问题来源。

| # | 能力 | 单机 | 集群 | 差距 / 影响 |
| --- | --- | --- | --- | --- |
| G1 | MCP 会话 | `STREAMABLE`，会话在内存 | HAProxy `balance roundrobin` | **严重**：`initialize` 在 A、`tools/call` 落到 B → 会话不存在 |
| G2 | 任务可见性 | 内存 `tasks` map | 仅构造时 `restore()` 读一次 Qdrant | B 看不到 A 提交的任务，`rag_task_status` 返回 IDLE |
| G3 | 任务所有权 | 进程内 | `_reindex_state` 锁 point「读后写」 | 无 CAS / epoch，分区时两个副本都可能自认持有 |
| G4 | 锁的一致性 | 一把进程内锁 | Redis 活动锁（带 token）+ Qdrant 任务锁（无 token） | 两把锁语义重叠，可能不一致 |
| G5 | 幂等写入 | 写后删旧 | 同左，但失败窗口更大 | chunk id 含随机 `generation`，失败会残留重复片段 |
| G6 | 共享存储 | 本地卷 | 共享 RWX 卷 | 无运行时校验，归档不可写只会在使用时才发现 |
| G7 | 观测 | health 有 `indexLock` | 无任务 owner / epoch / archive | 多副本排障缺关键字段 |
| G8 | 测试 | 154 单测 + jar 冒烟 | 手工双副本脚本 | 无容器化 E2E，接管 / 分区未被自动化覆盖 |

**关键代码位置**

- `rag/reindex/ReindexTaskService.java`：`tasks` 只在构造器 `restore()` 加载；`status()` 直接查内存。
- `rag/reindex/QdrantReindexStateBackend.java`：`writeOwner` 为无条件 upsert；`clearOwner` 先读后删。
- `rag/IndexActivityGuard.java`：已有 Redis 租约 + `isHeldBy` 远程校验，可作为任务所有权的唯一权威。
- `docker/haproxy.cfg`：`balance roundrobin`。
- `rag/RagService.java`：chunk point id 含随机 `generation`。

## 4. 工作包

### WP1 集群会话路由（P0）— 已完成

**状态**：2026-11-09 完成。`application.yml` 支持 `SMALL_RAG_MCP_PROTOCOL`，overlay 设 `STATELESS`；`McpTransportGuard` 在 distributed 非 `STATELESS` 时告警。

**目标**：多副本下 MCP 调用不依赖粘性会话。

**方案**

- distributed 模式设 `spring.ai.mcp.server.protocol=STATELESS`（已确认 Spring AI 2.0.2 + WebMVC 支持，端点仍是 `/mcp`）。
- standalone 保持 `STREAMABLE`。
- 网关保留轮询；WP7 已改为 Spring Cloud Gateway + Consul + LoadBalancer。
- 工具走注解扫描，`McpServerConfig` 无需改；需验证 stateless 下 13 个工具全部可见。

**验收**

- 两副本 + gateway：连续 50 次 `tools/call` 全部成功，无 404 / 会话错误。
- standalone：`initialize` → `tools/list` → `tools/call` 与 v0.0.2 一致。

**风险**：stateless 不支持 server-initiated notifications（当前未使用），需在文档注明。

### WP2 任务所有权统一 + fencing token（P0）— 已完成

**状态**：2026-11-09 完成。新增 `FencedLease`；`ReindexTaskService` 使用 `reindex-task` 租约 + epoch，续租线程与远程写栅栏已接入；Qdrant 锁 point 降级为诊断镜像；`ReindexTaskView` 已暴露 `ownerInstance` / `epoch` / `heartbeatAt`。

**目标**：同一时刻只有一个副本能写索引；旧持有者恢复后无法继续写。

**方案**

- 任务所有权统一到 `DistributedLock`（key：`<prefix>reindex-task`），与 `IndexActivityGuard` 共用租约 / 续租 / 远程校验。
- 新增 fencing：`INCR <prefix>reindex-task:epoch` 生成单调 epoch；每次 `delete/add` 前校验「本地 epoch == 当前锁 epoch」。
- Qdrant `_reindex_state` 锁 point 降级为**诊断镜像**（`ownerInstance`、`epoch`、`expiresAt`），不再作为权威。
- `ReindexTaskView` 增加 `ownerInstance`、`epoch`、`heartbeatAt`。
- standalone：`LocalDistributedLock` + `AtomicLong` epoch，接口与语义一致。

**验收**

- `kill -9` 持有者 → 租约过期后另一副本接管；旧副本重启后写入被拒绝（epoch 不匹配）。
- Redis IT：并发 `tryAcquire` 只有一个成功；epoch 严格递增。

**风险**：Qdrant 无 CAS，权威必须是 Redis；distributed 模式下 Redis 不可用时**拒绝写索引**（fail-closed），standalone 不受影响。

### WP3 跨副本任务状态与操作（P1）— 已完成

**状态**：2026-11-09 完成。`ReindexStateStore` 新增 `load` / `loadLatest`；`status()` 本地缺失时回读 Qdrant（`task-status-cache-ms`，默认 2s）；`resume` / `cancel` 抢任务租约后接管，抢不到返回持有者实例；`rag_task_status` 早已包含 `ownerInstance` / `epoch` / `heartbeatAt`。

**目标**：任意副本都能查询 / 续接 / 取消任务。

**方案**

- `ReindexStateStore` 增加 `load(taskId)`、`loadLatest()`；`status()` 本地缺失时回读 Qdrant（2s 短缓存）。
- `resume()` / `cancel()` 在非持有副本：先按 WP2 抢任务锁；抢到则接管（epoch 递增），抢不到返回持有者 `ownerInstance`。
- 运行中任务进度仍以内存为准；`persist()` 频率不变。
- `rag_task_status` 返回值新增 owner / epoch 字段（向后兼容）。

**验收**

- A 提交 → B `rag_task_status(taskId)` 可见进度与 owner。
- `kill A` → B `rag_task_resume` 成功续接，跳过已完成文档。
- A 存活时 B `resume` 返回「任务由 instance-X 持有」。

### WP4 幂等写入与重复片段清理（P1）— 已完成

**状态**：2026-11-09 完成。子块 id 改为 `chunk:<docId>:<signature>:<index>`；`rag_rebuild` 完成后清理无子块父块与缺 `chunk_type` 的旧片段；容器 E2E 覆盖重复 upsert 片段数恒定与孤儿清理。

**目标**：重复 upsert 不产生重复片段；中断后残留可清理。

**方案**

- chunk point id 去掉随机 `generation`，改为 `chunk:<docId>:<signature>:<index>`（确定性），写入用 upsert。
- 写后按 `doc_id` 过滤删除「非当前 signature / index」的旧片段。
- 新增孤儿清理：`parent_id` 无对应子块的父块、`chunk_type` 缺失的旧片段。
- `ChunkingSignature` 升版本 → 触发一次全量重切。

**验收**

- 同一文档连续 upsert 3 次，片段数恒定；中断后重跑不产生重复。
- `rag_rebuild` 后集合内无孤儿父块。

**风险**：id 方案变更需要一次全量重切；对已有部署是破坏性变更，release note 必须写明。

### WP5 观测与一致性诊断（P1）— 已完成

**状态**：2026-11-09 完成。`/actuator/health` 新增 `archive` 与 `reindexTask` 明细；启动时校验权威锁与 Qdrant 镜像并清理过期镜像；distributed 未声明 `SMALL_RAG_ARCHIVE_SHARED=true` 时启动告警。

**方案**

- `/actuator/health` 新增：`archive`（enabled / writable / dir）、`reindexTask`（owner / epoch / stateStore / lastHeartbeat）。
- 启动一致性校验：Redis 锁 owner 与 Qdrant 镜像不一致 → 以 Redis 为准并修正；distributed 且归档目录非共享 → WARN。
- `IndexLockHealthIndicator` 现有字段保留，只增不删。

**验收**

- 归档只读时 health 显示 `archive=DOWN` 或 `writable=false`。
- 两个副本 health 都能看到同一任务的 owner / epoch。

### WP6 测试与文档（P2）— 已完成

**状态**：2026-11-09 完成。新增 `RagQdrantContainerIT`（Testcontainers + 真实 Qdrant + 确定性哈希 Embedding，覆盖入库/检索、重复 upsert 幂等、孤儿清理）与 `RedisLockContainerIT`（真实 Redis 锁互斥、租约过期接管、epoch 单调）；`mvn verify -Pcontainer-it` 运行，GitHub Actions 分 `unit` / `container-it` 两个 job。

- Testcontainers E2E：`*ContainerIT`，默认 `mvn test` 跳过，`-Pcontainer-it` 运行。
- 故障注入：租约过期接管与旧 epoch 拒绝已在 Redis IT 覆盖；`kill -9` / Qdrant 短暂不可用仍以单元测试与部署手册为主。
- 真实语料评测 opt-in：`-Dsmallrag.eval.real=true`（需外部 Embedding）。
- 文档：standalone → distributed 迁移、容量、故障处理、STATELESS 限制。

### WP7 Spring Cloud 数据面（v0.0.3 后继变更）— 已完成

**状态**：2026-11-09 完成。仓库拆为 `app` / `gateway` / `config-server` 三个 Maven 模块；Gateway 使用 WebFlux + Consul + LoadBalancer 替换 HAProxy；Config Server 使用 native 文件系统后端；检索熔断改用 Spring Cloud CircuitBreaker（Resilience4j 实现）。

**方案**

- `gateway`：`spring-cloud-starter-gateway-server-webflux`，路由 `Path=/mcp` → `lb://small-rag`，长响应超时 1h；Caffeine 缓存 LoadBalancer。
- `config-server`：`spring-cloud-config-server` + native，`config-repo/` 挂载到 `/config`；应用用 `CONFIG_SERVER_ENABLED` + `SPRING_CONFIG_IMPORT=optional:configserver:...` 拉取。
- `app`：用 `spring-cloud-starter-circuitbreaker-resilience4j` 替代直接依赖 `resilience4j-circuitbreaker`；`rag.query-breaker.*` 映射到 `CircuitBreakerFactory`。
- `compose.distributed.yaml` 移除 HAProxy，增加 `config-server` 与 `gateway`；外部依赖增加 Config Server。

**验收**

- `mvn test` 覆盖三个模块；Gateway 路由用 SimpleDiscoveryClient 做端到端代理测试。
- Config Server native 仓库可返回 `small-rag/default`；应用启动日志出现 `Located environment: name=small-rag`。
- `mvn verify -Pcontainer-it` 继续通过。

## 5. 里程碑与排期

| 里程碑 | 内容 | 出口标准 | 预估 |
| --- | --- | --- | --- |
| M1 集群可用 | WP1 + WP2 | 两副本无会话错误；kill 后安全接管，旧副本写不进 | 3–4 人日 |
| M2 集群可运维 | WP3 + WP5 | 任意副本查 / 续 / 取消；health 可定位 owner / epoch | 2–3 人日 |
| M3 数据正确性 | WP4 + WP6 | 幂等回归 + 容器化 E2E + 文档 | 3–4 人日 |

## 6. 验证矩阵

| 场景 | standalone | distributed |
| --- | --- | --- |
| 启动 | jar + all-in-one 镜像 | 2 副本 + 外部依赖 |
| MCP | STREAMABLE 全链路 | STATELESS 经 gateway 连续 50 次调用 |
| 重建互斥 | 进程内锁 | Redis 锁 + epoch（IT 覆盖） |
| 故障 | — | kill -9 持有者、Redis 重启、Qdrant 短暂不可用 |
| 归档 | 本地卷 | 共享卷 + health 校验 |
| 容器 E2E | — | `mvn verify -Pcontainer-it`（Qdrant + Redis） |

## 7. 决策记录（2026-11-09）

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 范围 | M1 + M2 + M3 全做 |
| D2 | 集群传输 | distributed 默认 `STATELESS`；standalone 保持 `STREAMABLE` |
| D3 | 幂等改造 | 本期做：chunk id 确定性化，接受一次全量重切 |
| D4 | 容器化测试 | 纳入 CI：Testcontainers E2E 在 CI 跑 |
| D5 | Spring Cloud 范围 | 引入 Gateway（WebFlux）、Config Server（native）、CircuitBreaker（Resilience4j 实现）；保留 Consul discovery；移除 HAProxy（见 ADR-0002，ADR-0001 已 superseded） |

## 8. 实施顺序与分支

按 Git Flow 从 `develop` 切 3 个 feature 分支，squash 合回：

| 分支 | 覆盖 | 主要提交 |
| --- | --- | --- |
| `feature/cluster-safety` | WP1 + WP2 | ✅ 已完成：`feat: stateless MCP in distributed mode and fenced reindex task lease` |
| `feature/cluster-ops` | WP3 + WP5 | ✅ 已完成：`feat: expose reindex tasks across replicas and report cluster health` |
| `feature/idempotent-ingest` | WP4 + WP6 | ✅ 已完成：`refactor: make chunk point ids deterministic`、`test: add Testcontainers container coverage`、`ci: run unit and container tests` |
| `feature/spring-cloud-data-plane` | WP7 | ✅ 已完成：引入 Spring Cloud Gateway、Config Server、CircuitBreaker |

顺序：`cluster-safety` → `cluster-ops` → `idempotent-ingest`。前两个分支不改变 chunk id，可独立发布验证。

## 9. 配置与兼容

新增 / 调整的配置（均有默认值，standalone 不受影响）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | 环境变量 `SMALL_RAG_MCP_PROTOCOL`；distributed 设 `STATELESS` |
| `smallrag.distributed.reindex-fencing-enabled` | — | 未实现开关：fencing 始终启用，关闭会破坏互斥语义 |
| `smallrag.distributed.task-status-cache-ms` | `2000` | 跨副本状态读穿缓存 |
| `smallrag.archive.shared` | `false` | distributed 部署设 `true`，health / 启动告警据此判断共享卷 |
| `SMALL_RAG_ARCHIVE_SHARED` | `false` | 上述配置的环境变量入口 |
| `CONFIG_SERVER_ENABLED` | `false` | 启用 Spring Cloud Config 客户端 |
| `SPRING_CONFIG_IMPORT` | 空 | `optional:configserver:http://config-server:8888` |
| `GATEWAY_RESPONSE_TIMEOUT` | `1h` | Gateway 长响应超时 |
| `GATEWAY_CONNECT_TIMEOUT_MS` | `5000` | Gateway 连接后端超时 |

**破坏性变更**：WP4 的 chunk id 方案变更 + `ChunkingSignature` 升版本，升级后首次启动会自动全量重切；release note 必须写明。

## 10. CI 设计

`.github/workflows/ci.yml`（GitHub Actions）已落地：

- `unit`：JDK 25（temurin），`mvn -B clean verify`，不依赖外部服务。
- `container-it`：`mvn -B verify -Pcontainer-it`。GitHub 托管的 `ubuntu-latest` 自带 Docker daemon，Testcontainers 可直接使用，**不需要 DinD**。
- `image`：后续可选；当前 CI 只跑单元与容器 E2E，镜像构建由本地 / 发布流程负责。

> 假设：CI 使用 GitHub Actions（`github` 远程）。Gitee 侧继续作为镜像远程，不额外接 Gitee Go。

## 11. 完成定义（DoD）

- [x] `mvn clean package` 通过，单测全绿且默认不依赖外部服务（app 176 + gateway 2 + config-server 1）
- [x] `mvn verify -Pcontainer-it` 通过（Qdrant + Redis + 确定性哈希 Embedding）
- [x] standalone：jar 冒烟 + all-in-one 镜像冒烟（v0.0.3 发布前已验证）
- [ ] distributed：2 副本经 Spring Cloud Gateway 连续 50 次 MCP 调用成功（需真实部署环境演练）
- [ ] 故障：`kill -9` 持有者后安全接管，旧副本无法继续写入；Redis 不可用时写索引 fail-closed（需真实部署环境演练）
- [x] `rag_task_status` / `rag_task_resume` / `rag_task_cancel` 跨副本逻辑有单元测试覆盖
- [x] 同一文档重复 upsert 片段数恒定；`rag_rebuild` 后无孤儿父块（容器 E2E 覆盖）
- [x] README / ROADMAP / DEV-MEMORY 同步，迁移与限制写清楚

> 未勾选项属于真实多副本故障演练，无法在本地 CI 中可靠复现；部署验收时按第 6 节矩阵执行。
