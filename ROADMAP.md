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
- ✅ Resilience4j CircuitBreaker 包住 Embedding 与 Qdrant 查询路径（仅 `rag_search`/`rag_query`），阈值/半开/fallback 可配置；入库与重建不受影响。
- ✅ 健康检查暴露：`/actuator/health` 提供实例 id、任务持有者/活动锁、熔断状态与当前 profile。
- ✅ Consul discovery（`spring-cloud-starter-consul-discovery`）：`CONSUL_ENABLED=true` 时注册 `small-rag`，健康检查 `/actuator/health`；默认关闭。
- ✅ Consul config（`spring-cloud-starter-consul-config`）：`SPRING_CONFIG_IMPORT=optional:consul:` 时读取 KV；默认 `KEY_VALUE` 格式，`fail-fast=false`，config watch 默认关闭。

### P2 多副本正确性
- ✅ 目录导入与定时/启动扫描只由 leader 执行：`SourcesLeaderElection` 持有 `sources-leader` 租约，leader 失联后自动接管；手动同步任意副本可发起，仍由活动锁串行化。
- ✅ 陈旧写入兜底：活动锁续租失败即 `isHeldBy=false`，重建任务、目录导入与启动重切在每次写入前校验。
- ✅ 双实例验证：两个 jar 实例只有一个 `sourcesLeader=true`；kill leader 后租约过期由另一副本接管；真实 Redis IT 覆盖锁互斥/续租/接管。
- ⬜ 任务 owner + lease + fencing token：Qdrant 任务状态锁仍是单实例读后写，需要与 Redis 锁对齐。
- ⬜ 归档共享存储校验；幂等写入与重复片段清理；Qdrant 状态集合与 Redis 锁的一致性校验。
- ⬜ 测试：双实例并发 `reindex` / `sync` 的完整互斥、续租、接管与释放（当前覆盖活动锁与 leader 选举）。

### P3 Spring Cloud Gateway（可选）
- 独立模块或独立 compose 服务，WebFlux Gateway 路由 `/mcp`。
- 确保流式响应不被缓冲；可选鉴权、限流、灰度。
- standalone 模式不启用。

### P4 RAG 完善与测试
- ✅ 离线评测骨架：`EvalMetrics`（recall@k / MRR / nDCG@k）+ `RagPipelineEvaluationTest`（真实切分 + SimpleVectorStore + 确定性哈希向量），不依赖外部服务。
- ⬜ 真实标注语料评测：接入 Qdrant + OpenAI 兼容 Embedding，跑同一组指标并回归 MMR λ / `SEMANTIC_BREAKPOINT_PERCENTILE`。
- ⬜ Testcontainers Qdrant + 假 Embedding 服务的端到端测试（入库 → 检索 → 查询 → 重建）。
- ⬜ 文档：双模式部署、从 standalone 迁移到 distributed、容量与故障处理。

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
