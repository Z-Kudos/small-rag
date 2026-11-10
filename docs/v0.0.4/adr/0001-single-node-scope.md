---
title: 单机轻量化范围
status: done
version: 0.0.4
updated: 2026-11-10
owners: [zjk]
template: docs/templates/adr.md
related: [AGENTS.md, ROADMAP.md, docs/v0.0.4/plan.md]
---

# ADR-0001：单机轻量化范围

## 1. 背景

- v0.0.3 后期引入了 Spring Cloud Gateway、Config Server、Consul、Redis/Redisson 与跨副本任务视图。
- 这些能力只对多副本有价值，但显著增加单机部署的依赖、镜像体积、内存与维护成本。
- 原分布式版本需要完整保留，以便后续需要 HA 时继续维护。

## 2. 决策

1. **原分布式版本另存为 `~/Projects/cloud-rag`**：保留 git 历史，移除 remote，作为独立项目。
2. **本仓库 small-rag 只做单机**：版本号 `0.0.4`，删除 `gateway`、`config-server`、`compose.distributed.yaml`、`config-repo`。
3. **完全移除 Spring Cloud**：删除 Cloud BOM、Consul、Config Client、CircuitBreaker 包装、Gateway、LoadBalancer。
4. **检索熔断直接使用 Resilience4j**。
5. **不使用 Redis / Redisson**：删除分布式锁、fencing token、leader 选举；`IndexActivityGuard` 改为进程内 owner 标记。
6. **任务状态只保留快照与检查点**：Qdrant `<collection>_reindex_state` 不再保存锁镜像。
7. **运行形态只保留 all-in-one**：应用 + Qdrant + llama.cpp 同容器；删除 `app-only` 与 `SMALL_RAG_RUNTIME`。

## 3. 备选方案

- **保留可选分布式**：需要继续维护两套锁、两套部署和两套测试；与“轻量化”目标冲突。
- **只删除 Gateway/Config Server，保留 Redis**：仍需 Redis 与分布式锁，单机用户没有收益。
- **删除异步任务系统**：会丢失进度、取消、重启续接能力；保留单机版即可，不需要分布式租约。

## 4. 后果

正面：

- 单模块、单容器、单端口；无 Redis/Consul/Gateway/Config Server。
- 依赖更少，镜像更小，启动更快。
- 单机任务、目录导入、重建逻辑更简单。

负面：

- 不支持多副本与 HA；需要时使用 `cloud-rag`。
- 进程内锁不跨 JVM；多个 small-rag 实例不能共享同一个 Qdrant 进行写入。
- 升级需要停机与数据卷备份。
