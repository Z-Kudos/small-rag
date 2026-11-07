# ROADMAP — v0.0.2

> 目标：在**不破坏现有单容器一键运行**的前提下，增加可选的分布式多副本能力，并补齐 RAG 评测与集成测试。

## 1. 两种运行模式

| 维度 | standalone（默认） | distributed（可选） |
| --- | --- | --- |
| 进程 | 单容器 supervisord：app + Qdrant + llama-server | app × N，外部 Qdrant / Redis / Consul / Embedding |
| 配置 | `application.yml` + 环境变量 | `distributed` profile + Consul KV（可选） |
| 重建/导入互斥 | 进程内 + Qdrant 状态集合（现状） | Redis 租约锁 + fencing token |
| 原文归档 | 本地卷 | 共享 RWX 卷（NFS/CephFS）；对象存储留待后续 |
| 目录扫描 / watcher | 每实例执行 | 仅 leader 执行 |
| 查询保护 | 超时 + 重试 | Resilience4j 熔断 + 超时 |
| 网关 | 无 | 可选 Spring Cloud Gateway（`/mcp`） |

`standalone` 必须保持零新增外部依赖，`distributed` 通过 profile 与 compose overlay 显式开启。

## 2. 里程碑

### P0 双模式骨架 + 分布式锁
- 新增 `smallrag.distributed.enabled`（默认 `false`）与 `distributed` profile。
- `DistributedLock` 抽象：
  - `LocalDistributedLock`：进程内，standalone 使用；
  - `RedisDistributedLock`：`SET NX PX` + Lua 释放/续租，多副本使用。
- 重建任务、目录导入、启动自动重切全部走同一把锁；锁丢失立即停止写入。
- 同一镜像支持 `SMALL_RAG_RUNTIME=all-in-one|app-only`，避免多副本时重复启动 Qdrant/llama-server。
- 新增 `compose.distributed.yaml`：外部 Qdrant / Redis / Consul / Embedding + 2 个 app 副本 + 共享归档卷。

验收：standalone 构建与测试不变；distributed 下两个副本只有一个能执行重建/扫描。

### P1 Spring Cloud 服务治理
- Consul discovery（`spring-cloud-starter-consul-discovery`）。
- Consul config（`spring-cloud-starter-consul-config`，`spring.config.import=optional:consul:`）。
- Resilience4j CircuitBreaker 包住 Embedding 与 Qdrant 查询路径，阈值/半开/fallback 可配置。
- 健康检查暴露：实例 id、任务持有者、锁状态、当前 profile。

### P2 多副本正确性
- 任务 owner + lease + fencing token：owner 失联后由其他副本接管。
- 归档共享存储校验；目录导入与定时扫描只由 leader 执行。
- 幂等写入与重复片段清理；任务状态集合与 Redis 锁的一致性校验。
- 测试：双实例并发 `reindex` / `sync` 的互斥、续租、接管与释放。

### P3 Spring Cloud Gateway（可选）
- 独立模块或独立 compose 服务，WebFlux Gateway 路由 `/mcp`。
- 确保流式响应不被缓冲；可选鉴权、限流、灰度。
- standalone 模式不启用。

### P4 RAG 完善与测试
- 建立小型标注语料（query → 期望 doc/chunk），评测 `recall@k`、`MRR`、`nDCG`。
- Testcontainers Qdrant + 假 Embedding 服务的端到端测试（入库 → 检索 → 查询 → 重建）。
- 真实语料回归 MMR `λ` 与 `SEMANTIC_BREAKPOINT_PERCENTILE`。
- 文档：双模式部署、从 standalone 迁移到 distributed、容量与故障处理。

## 3. 非目标

- 不在 v0.0.2 拆成多个业务微服务（ingest / search 仍在同一应用内）。
- 不强制所有用户使用 Redis / Consul；standalone 保持零新增外部依赖。
- 不引入 Kafka / Spring Cloud Stream / Bus（留到确有跨副本事件广播需求时）。
- 不改变 MCP 工具签名与 `rag_query` 的纯文本语义。

## 4. 验收标准

1. `mvn clean package` 通过，现有 126 个测试保持绿色。
2. `docker compose up -d --build` 仍可单容器运行，行为与 v0.0.1 一致。
3. `docker compose -f compose.yaml -f compose.distributed.yaml up -d --scale app=2` 可运行。
4. 双副本下同一时刻只有一个重建任务 / 目录扫描；kill 持有者后，另一副本在租约过期后接管。
5. Embedding 故障时 `rag_query` 快速失败并返回可读错误，不出现线程永久占用。
