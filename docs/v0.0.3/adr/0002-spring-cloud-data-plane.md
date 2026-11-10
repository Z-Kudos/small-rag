---
title: Spring Cloud 数据面组件
status: done
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
template: docs/templates/adr.md
related: [AGENTS.md, ROADMAP.md, docs/v0.0.3/plan.md, docs/v0.0.3/adr/0001-spring-cloud-scope.md]
---

# ADR-0002：Spring Cloud 数据面组件

## 1. 背景

- ADR-0001 曾决定只保留 Consul discovery / config，不引入 Gateway、Config Server 与 CircuitBreaker 包装。
- v0.0.3 发布后确认需要真正使用 Spring Cloud 数据面：网关替换 HAProxy、配置中心替代环境变量 / Consul KV 的分散配置、检索熔断统一到 Spring Cloud CircuitBreaker API。
- 应用已按模块拆分：`app`（MCP + RAG）、`gateway`、`config-server`。

## 2. 决策

| 组件 | 结论 | 用途 / 理由 |
| --- | --- | --- |
| Spring Cloud Gateway | 引入（独立 `gateway` 模块，WebFlux） | 替换 HAProxy；`Path=/mcp` → `lb://small-rag`；长响应超时 1h |
| Spring Cloud LoadBalancer | 引入（仅 gateway） | 配合 Consul discovery 做客户端负载均衡 |
| Spring Cloud Config Server | 引入（独立 `config-server` 模块，native 后端） | `config-repo/` 挂载到 `/config`；应用用 `optional:configserver:` 拉取 |
| Spring Cloud CircuitBreaker | 引入（`app`，Resilience4j 实现） | `rag_search` / `rag_query` 熔断改为 `CircuitBreakerFactory` API；不再直接依赖 `resilience4j-circuitbreaker` |
| Consul discovery | 保留（可选） | 服务注册与 Gateway 副本发现；不再承担配置中心职责 |
| Consul config | 保留（可选） | 作为 Config Server 之外的兼容入口，不作为默认 |
| HAProxy | 移除 | 由 Gateway 替代，删除 `docker/haproxy.cfg` |

**硬约束**

1. standalone 仍然零新增外部依赖：Gateway / Config Server 只存在于 `compose.distributed.yaml`。
2. Config Server 不依赖 Consul，避免配置中心自身启动依赖服务发现。
3. 应用只依赖 Spring Cloud CircuitBreaker 抽象；Resilience4j 仅作为实现与 health 诊断来源。
4. Gateway 与 Config Server 构建上下文均为仓库根目录，镜像由各自模块的 Dockerfile 构建。

## 3. 备选方案

- **Gateway 用 WebMVC**：与 `app` 的 Servlet 栈一致，但 WebFlux 网关在流式 / 长连接代理上更成熟，且作为独立进程不影响 `app`。
- **Config Server 用 Git 后端**：适合多环境审计，但需要可访问的 Git 仓库；当前 Compose 部署用 native 文件系统即可。
- **保留 HAProxy**：可以继续工作，但无法复用 Spring Cloud 的发现 / 过滤 / 观测能力，与本次目标冲突。

## 4. 后果

正面：

- 网关、配置中心、熔断都使用 Spring Cloud 统一抽象，后续可替换实现。
- 配置集中到 `config-repo/`，不再把环境差异散落在 Compose 环境变量中。
- Gateway 通过 Consul 自动发现副本，扩缩容无需改配置。

负面：

- 多两个进程与镜像，distributed 模式资源占用与运维复杂度增加。
- Config Server 的 native 后端需要保证 `config-repo/` 在所有环境一致；变更配置需要重启或刷新。
- 旧 ADR-0001 的“最小 Spring Cloud 面”不再成立，已标记 superseded。
