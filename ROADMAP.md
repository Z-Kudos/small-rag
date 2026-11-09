---
title: ROADMAP
status: implementing
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
related: [docs/v0.0.3/plan.md, docs/v0.0.3/adr/0002-spring-cloud-data-plane.md, DEV-MEMORY.md]
---

# ROADMAP — v0.0.3（已发布，后继变更中）

> 当前版本 `v0.0.3`（2026-11-09 发布）；上一版 `v0.0.2` 已于 2026-11-07 发布。
> 工作包与验收细节见 [`docs/v0.0.3/plan.md`](docs/v0.0.3/plan.md)。
> 本文只记录版本级目标、里程碑与验收，不记录实现细节。

## 1. 版本目标

v0.0.3 主题：**单机与集群**——一套语义、两种形态。

- **standalone（默认）**：单容器、零新增外部依赖，行为与 v0.0.2 一致。
- **distributed（可选）**：多副本安全接管、跨副本任务可见、不依赖粘性会话。

## 2. 运行形态

| 维度 | standalone（默认） | distributed（可选） |
| --- | --- | --- |
| 开关 | 无 | `SMALL_RAG_DISTRIBUTED_ENABLED=true` + `compose.distributed.yaml` |
| 进程 | 单容器 supervisord：app + Qdrant + llama-server | app × N + 外部 Qdrant / Redis / Consul / Embedding |
| MCP 传输 | `STREAMABLE` | `STATELESS`（v0.0.3 起） |
| 重建 / 导入互斥 | 进程内锁 + Qdrant 状态集合 | Redis 租约锁 + fencing token |
| 原文归档 | 本地卷 | 共享 RWX 卷（NFS / CephFS） |
| 目录扫描 | 每实例执行 | 仅 leader 执行 |
| 查询保护 | 超时 | Resilience4j 熔断 + 超时 |
| 网关 | 无 | Spring Cloud Gateway（WebFlux，`/mcp`，Consul + LoadBalancer） |

> distributed 不是 Spring profile，而是 `smallrag.distributed.enabled` 开关 + compose overlay。

## 3. 里程碑

### M1 集群可用

- ✅ **WP1 集群会话**：distributed 使用 `STATELESS`，不依赖粘性会话。
- ✅ **WP2 任务所有权**：Redis 租约 + fencing token；Qdrant 锁 point 降级为诊断镜像。
- 验收：两副本经 gateway 连续 50 次 MCP 调用成功；`kill -9` 持有者后安全接管，旧副本无法继续写入。

### M2 集群可运维

- ✅ **WP3 跨副本任务状态**：`rag_task_status` / `rag_task_resume` / `rag_task_cancel` 任意副本可用。
- ✅ **WP5 观测**：health 增加 `archive` 与 `reindexTask`（owner / epoch）。
- 验收：任意副本可查 / 续 / 取消；health 能定位 owner / epoch。

### M3 数据正确性

- ✅ **WP4 幂等写入**：chunk id 确定性化，孤儿 / 重复片段清理。
- ✅ **WP6 测试与文档**：Testcontainers E2E 纳入 CI；迁移与限制文档。
- 验收：重复 upsert 片段数恒定；`mvn verify -Pcontainer-it` 通过。

### v0.0.3 后继变更：Spring Cloud 数据面

- ✅ **WP7 网关与配置中心**：Spring Cloud Gateway（WebFlux）替换 HAProxy；Config Server native 后端；检索熔断改用 Spring Cloud CircuitBreaker。
- 验收：Gateway 通过 Consul + LoadBalancer 路由 `/mcp`；应用可从 Config Server 拉取配置；`mvn test` 覆盖三个模块。

## 4. 已完成（v0.0.2）

- ✅ 双运行模式：`SMALL_RAG_RUNTIME=all-in-one|app-only`
- ✅ 分布式锁：`LocalDistributedLock` / `RedisDistributedLock`（`SET NX PX` + Lua）
- ✅ 活动锁远程校验与丢失即停写
- ✅ 目录扫描 leader 选举（`SourcesLeaderElection`）
- ✅ Resilience4j 检索熔断；`/actuator/health` 诊断
- ✅ Consul discovery / config（默认关闭）
- ✅ HAProxy gateway + `compose.distributed.yaml`
- ✅ 离线 RAG 评测（recall@k / MRR / nDCG）

## 5. 非目标（v0.0.3）

- 不拆分为多个微服务。
- 不强制使用 Redis / Consul；standalone 零新增外部依赖。
- 不引入 Kafka / Spring Cloud Stream / Bus。
- 不改变 MCP 工具签名与 `rag_query` 纯文本语义。
- 不引入 Spring Cloud Bus / Vault / Kubernetes / OpenFeign。

## 6. 验收标准

1. `mvn clean package` 通过；全部测试全绿（当前基线 179：app 176 + gateway 2 + config-server 1，`*RedisIT` / `*ContainerIT` 默认跳过）。
2. `mvn verify -Pcontainer-it` 通过（Testcontainers：真实 Qdrant + Redis；Qdrant E2E 使用确定性哈希 Embedding）。
3. `docker compose up -d --build` 单容器行为与 v0.0.2 一致。
4. `docker compose -f compose.yaml -f compose.distributed.yaml up -d --scale small-rag=2` 可运行（Qdrant / Redis / Consul / Embedding / Config Server / Gateway）。
5. 双副本同一时刻只有一个重建任务 / 目录扫描；`kill` 持有者后租约过期接管；Gateway 路由不依赖粘性会话。
6. Embedding 故障时 `rag_query` 快速失败并返回可读错误，不出现线程永久占用。
