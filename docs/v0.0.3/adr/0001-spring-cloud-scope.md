---
title: Spring Cloud 组件范围
status: approved
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
related: [AGENTS.md, ROADMAP.md, docs/v0.0.3/plan.md]
---

# ADR-0001：Spring Cloud 组件范围

## 1. 背景

- `pom.xml` 引入 Spring Cloud BOM `2026.0.0-RC1`，用于对齐 Consul starter 版本。
- 当前只声明 `spring-cloud-starter-consul-discovery` 与 `spring-cloud-starter-consul-config`，默认关闭（`CONSUL_ENABLED=false`）。
- v0.0.3 的集群工作（STATELESS MCP、Redis 租约 + fencing、跨副本任务状态、幂等写入、Testcontainers）不依赖任何 Spring Cloud 数据面组件。
- HAProxy 已提供网关；Docker DNS 已提供副本发现。

## 2. 决策

**只保留 Consul 两件套且保持可选；v0.0.3 不新增任何 Spring Cloud 组件。**

| 组件 | 结论 | 用途 / 理由 |
| --- | --- | --- |
| `spring-cloud-dependencies` BOM | 保留 | 仅用于 Consul starter 版本对齐 |
| `spring-cloud-starter-consul-discovery` | 保留（可选） | distributed 服务注册；不在请求路径 |
| `spring-cloud-starter-consul-config` | 保留（可选） | 可选 KV 配置；需显式 `SPRING_CONFIG_IMPORT=optional:consul:` |
| `spring-cloud-starter-loadbalancer` | 排除 | 无服务间调用；由 consul-discovery 传递引入，已在 pom 显式 exclude |
| Spring Cloud Gateway | 不引入 | HAProxy 已满足 `/mcp` 负载均衡；P3 再评估 |
| Spring Cloud Config Server | 不引入 | 配置用环境变量 + 可选 Consul KV |
| Spring Cloud CircuitBreaker | 不引入 | 直接使用 Resilience4j |
| Spring Cloud Bus / Stream | 不引入 | 非目标 |
| Spring Cloud Vault | 不引入 | 密钥由部署环境注入 |
| Spring Cloud Kubernetes | 不引入 | 部署目标为 Compose / Podman |
| Spring Cloud OpenFeign | 不引入 | 无服务间 HTTP 调用 |
| Spring Cloud Task / Function | 不引入 | 无相关场景 |

**硬约束**

1. Consul 关闭时（standalone 默认）不得连接 Consul，不得影响 MCP、检索、重建。
2. Consul 不在数据面：HAProxy 通过 Docker DNS 发现副本，不依赖 Consul。
3. 不因“用上 Spring Cloud”而新增组件；新增必须另立 ADR。

## 3. 备选方案

- **删除全部 Spring Cloud**：依赖更少，但会移除已发布的 Consul 能力，属于破坏性变更，且与 v0.0.3 主题无关。
- **引入 Spring Cloud Gateway 替换 HAProxy**：需要额外的 WebFlux 服务与发现 / 配置集成，内存与运维成本更高，当前收益不足。
- **保留 LoadBalancer**：当前无服务间调用，属于死依赖。

## 4. 后果

正面：

- 依赖面可控，standalone 仍零新增外部依赖。
- 集群工作不引入额外基础设施。

负面：

- Consul 仅提供服务目录与可选配置，网关路由不经过它，价值有限；若后续确认无用，可在下一版本删除。
- 排除 LoadBalancer 后，未来若增加 `@LoadBalanced` 客户端需重新引入。
