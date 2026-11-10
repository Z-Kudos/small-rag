---
title: DEV-MEMORY
status: done
version: 0.0.4
updated: 2026-11-10
owners: [zjk]
related: [AGENTS.md, ROADMAP.md, docs/v0.0.4/plan.md]
---

# DEV-MEMORY — 当前开发状态

> 只记录**当前有效**的状态；过时内容直接覆盖 / 删除，历史看 `git log`。
> 编写规范见 [`AGENTS.md`](AGENTS.md)。

## 1. 当前阶段

- 版本：`0.0.4`（已发布，`main` tag `v0.0.4`）。
- 上一版：`v0.0.3`（已发布；分布式版本已封存为 `~/Projects/cloud-rag`）。
- 主题：**单机轻量化**。
- 规划：[`docs/v0.0.4/plan.md`](docs/v0.0.4/plan.md)。
- 状态：WP1–WP3 已完成；`main` + tag `v0.0.4` 已发布。
- 文档：`README.md`（简体中文）/ `README.en.md`（English）；设计/ADR 在 `docs/v0.0.4/`；`docs/v0.0.3/` 冻结。
- **不切换版本**：下一次版本变更需人工确认。

## 2. 构建与验证

- JDK 25；`mvn clean package` 单模块产出 `target/small-rag-0.0.4.jar`。
- 单测：`mvn test`，**142** 个测试全绿，不依赖外部服务。
- 容器 E2E：`mvn verify -Pcontainer-it`，Qdrant Testcontainers **3** 个测试全绿。
- 本机 Docker Hub 受限时可用 `-Dsmallrag.qdrant.image=docker.1ms.run/qdrant/qdrant:latest` 覆盖镜像。

## 3. 运行形态

- **唯一形态：all-in-one 单容器**。应用 + Qdrant + llama-server 由 supervisord 管理，只暴露 18080；Qdrant 6333/6334、Embedding 11434 绑定 127.0.0.1。
- 不再支持 `SMALL_RAG_RUNTIME=app-only`、Gateway、Config Server、Consul、Redis。
- 镜像构建：Maven 构建 → Qdrant 基底编译 llama-server（`LLAMA_CPP_TAG=v0.9.0`）→ 拷入 Temurin JRE。

## 4. 架构要点

### 4.1 切分

- 策略 4 种：`fixed` / `recursive` / `semantic` / `parent-child`（默认）。`MarkdownChunker` 是 semantic 与 parent-child 共用的内部引擎。
- 长度度量 = 全宽字符当量（`TextWeights`：CJK=1、拉丁=0.25）；英文硬切回退单词边界。
- 子块 point id = `chunk:<docId>:<signature>:<index>` 确定性生成；同配置重复 upsert 覆盖同一批 point。
- Parent-Child：父块独立存储（`chunk_type=parent`），子块只带 `parent_id`；检索按 id 取回父块，缺失时回退子块原文。
- MMR：`rag.mmr-lambda` 默认 0.7。

### 4.2 检索与存储

- `rag_search` 可选 `expand`；`rag_query` 返回增强上下文。
- payload 正文键 `RagService.CONTENT_FIELD="content"`。
- `Document` id 必须是 UUID，业务 id 放 metadata 的 `doc_id`；空库用 `KnowledgeBaseProbe` 短路。
- 归档：`DocumentArchive` 按 `doc_id` 存原文；`.archive/index.json` 摘要索引；目录不可写时降级关闭自动重切。
- 包结构：`mcp/`、`config/`、`rag/`、`rag/archive/`、`rag/chunking/`、`rag/reindex/`、`rag/sources/`。

### 4.3 重建任务与单机互斥

- `rag_rebuild` 与 `rag_reindex`（不传 id）为异步任务；单篇 `rag_reindex(documentId)` 同步。
- `IndexActivityGuard`：进程内 `AtomicReference<String>` owner 标记，`tryAcquire` CAS、`release` 按 label 内容比较；串行化重建与目录导入。
- `ReindexTaskService`：每任务独立 daemon 线程 + 单任务互斥 + 保留最近 20 个快照。
- 状态持久化：`ReindexStateStore` + `QdrantReindexStateBackend` 写入 `<collection>_reindex_state`（1 维哑向量 + JSON payload），只保存快照与 `cleared` / `completedDocumentIds` 检查点。
- 重启：未完成任务标 `interrupted` 并自动持有活动锁；`REINDEX_RESUME_ON_STARTUP=true` 自动续接，否则等 `rag_task_resume` / `rag_task_cancel`。
- 看门狗：停滞只标 `stalled`；硬超时撤销写权限 + 取消在途 Embedding + 中断线程；线程退出则释放活动锁，否则宽限期后强制释放。

### 4.4 目录导入

- `rag.sources.*`：`enabled`、`directories`、`extensions`、`exclude`、`scan-on-startup`、`scan-interval-seconds`、`watch-enabled`、`watch-debounce-seconds`、`max-file-bytes`、`max-files-per-scan`、`delete-missing`、`delete-orphan-roots`、`state-file`、`metadata`。
- 文档 id = `UUID.nameUUIDFromBytes("small-rag-source:" + label + "::" + 相对路径)`；label 重复拒绝扫描。
- 变更检测：mtime + size → SHA-256；状态文件 `<archive-dir>/.sources/state.json` 原子替换。
- 单实例直接扫描，无 leader 选举；与重建任务通过 `IndexActivityGuard` 互斥。

### 4.5 健康与熔断

- `QueryCircuitBreaker` 直接使用 Resilience4j（`rag.query-breaker.*`），只保护 `rag_search` / `rag_query`；打开时抛 `QueryUnavailableException`。
- `/actuator/health` 只暴露 health；无自定义锁/任务健康组件。
- Spring Boot 4 包名：`org.springframework.boot.health.contributor.Health/HealthIndicator`。

## 5. 关键约束与坑

- **Spring 多构造器 bean** 必须给 Spring 用的构造器加 `@Autowired`（`QueryCircuitBreaker`、`ReindexStateStore`），否则 Spring 报 “No default constructor found”。
- **Jackson 3**：import 一律 `tools.jackson.*`；`com.fasterxml.jackson.*` 编译失败。
- **Lombok**：JDK 23+ 需 `<maven.compiler.proc>full</maven.compiler.proc>`。
- **依赖源**：`pom.xml` 首位 `aliyun-public`，`spring-milestones` 回退；容器构建用 `docker/maven-settings.xml`（`mirrorOf=*,!spring-milestones`）。
- **Qdrant**：gRPC 6334；`Document` id 必须 UUID；集合初始化 fail-soft，维度不一致 fail-fast；deadline 用 `rag.qdrant.request-timeout-ms`。
- **状态集合隔离**：`<collection>_reindex_state` 不并入主集合，否则污染空库探活、检索与 `rag_rebuild` 清理。
- **MCP**：`McpSyncServerCustomizer` 已被 WebMVC 自动配置占用，不要自定义；instructions 走 `spring.ai.mcp.server.*`。
- **Dockerfile** 无 `# syntax=`；`docker/*.sh` 必须 LF；`.env` 只放模板值。
- **切分签名**：`ChunkingSignature` 版本与项目版本同步（当前 `v0.0.4`）；只改算法不升版本，旧索引不会自动重切。
- **Embedding 超时**：`EMBEDDING_CONNECT_TIMEOUT_MS=10000`、`EMBEDDING_READ_TIMEOUT_MS=120000`；`spring.http.client.*` 不生效。
- **归档摘要索引**：内容陈旧但 id 集合一致时可能保留旧签名。
- **目录导入**：watcher 受 inotify 限制；label 变化生成新 id；`lastRun` 不持久化。

## 6. 待办

- v0.0.4：已发布；下一版本待确认。
- 需要多副本时使用 `~/Projects/cloud-rag`。
