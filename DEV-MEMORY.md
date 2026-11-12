---
title: DEV-MEMORY
status: done
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
related: [AGENTS.md, ROADMAP.md, docs/v0.0.5/plan.md]
---

# DEV-MEMORY — 当前开发状态

> 只记录**当前有效**的状态；过时内容直接覆盖 / 删除，历史看 `git log`。
> 编写规范见 [`AGENTS.md`](AGENTS.md)。

## 1. 当前阶段

- 版本：`0.0.5`（已发布，`main` tag `v0.0.5`；`ChunkingSignature = v0.0.5`，升级触发一次全量重切）。
- 上一版：`v0.0.4`（已发布 2026-11-11，含 WP4 多形态部署：SQLite 可选向量库 + 四形态开关）；`v0.0.3`（已封存，`cloud-rag`）。
- 主题：**切片图谱与编辑管理**——让 AGENT 通过 MCP 操作和编辑 chunk。
- 规划：[`docs/v0.0.5/plan.md`](docs/v0.0.5/plan.md)；ADR：[`docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md`](docs/v0.0.5/adr/0001-agent-editable-knowledge-base.md)。
- 状态：**v0.0.5 已发布**（WP0–WP5 完成；Qdrant Testcontainers E2E 与容器构建/四形态冒烟待恢复）；`main` + tag `v0.0.5`；本机 JVM 冒烟通过（sqlite + ollama Qwen3-Embedding-0.6B）。
- 文档：`README.md`（简体中文）/ `README.en.md`（English）；设计/ADR 在 `docs/v0.0.5/`（已冻结）；`docs/v0.0.4/`、`docs/v0.0.3/` 冻结。
- **不切换版本**：下一次版本变更需人工确认。
- **tag v0.0.4 改写说明**：旧 tag `v0.0.4` 指向 `e5c165c`（2026-11-10 发布），2026-11-11 删除并重打至包含 WP4 的 release main；旧发布内容仍保留在主线历史中（`git log` 可查），但已 clone 仓库的 tag 指针不会自动更新。

## 2. 构建与验证

- JDK 25；`mvn clean package` 单模块产出 `target/small-rag-0.0.5.jar`。
- 单测：`mvn test`，**190** 个测试全绿，不依赖外部服务。
- 容器 E2E：`mvn verify -Pcontainer-it`，Qdrant Testcontainers **3** 个测试全绿。
- 本机 Docker Hub 受限时可用 `-Dsmallrag.qdrant.image=docker.1ms.run/qdrant/qdrant:latest` 覆盖镜像。

## 3. 运行形态

- **唯一镜像 + 四部署形态**：应用 + Qdrant + llama-server 同镜像、supervisord 管理、只暴露 18080；默认 qdrant+llama（全量），关闭本地进程即切形态（见 [`README.md#部署形态`](README.md#部署形态)）。
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
- 状态持久化：`ReindexStateStore` + 后端（Qdrant：`QdrantReindexStateBackend` 写 `<collection>_reindex_state`，1 维哑向量 + JSON payload；sqlite：`SqliteReindexStateBackend` 写同一 db 的 `reindex_state` 表）只保存快照与 `cleared` / `completedDocumentIds` 检查点。
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

- v0.0.5：已发布（2026-11-12）；WP0–WP5 完成，190 单测 + 本机 JVM 冒烟；Qdrant Testcontainers E2E 3 全绿；容器冒烟（Qdrant+llama 本地 all-in-one 默认形态）**通过**（镜像 `small-rag:0.0.5`，首启 GGUF 下载后 health=200，ingest/search/query/delete 全通）；容器冒烟其余三形态推迟（内存）。
- v0.0.5 升 `ChunkingSignature=v0.0.5`：所有用户升级触发一次全量重切（归档自动兜底）；README 迁移说明待补。
- 镜像默认 `EMBEDDING_DIMENSION=1024`（与默认 Qwen3-Embedding-0.6B 输出一致）；之前裸 `podman run` 会错配 1536 → 入库报 `expected dim: 1536, got 1024`，已改。podman 最小镜像格式是 OCI，`HEALTHCHECK` 在 OCI 下被忽略（docker 格式/compose 不受影响）。
- 需要多副本时使用 `~/Projects/cloud-rag`。
- **不做镜像体积优化**：`jlink` 定制运行时、删 `/qdrant/static`、排除 QUIC native 等一律不实施。
- 技术栈轻量化方向（依赖面收敛 / 嵌入式向量库 / 去 Spring Boot / 合并抽象层）已讨论：嵌入式向量库以 **SQLite 可选后端**形式进入 WP4（纯 Java 余弦，无 JNI）；其余（去 Spring AI / 去 Spring Boot）仍暂不实施。
- 多形态部署（WP4，代码已完成）：同一镜像四形态——① 远程 Qdrant + 远程 Embedding；② 本地 Qdrant + 本地 llama（默认）；③ SQLite + 远程 Embedding；④ SQLite + 本地 llama。开关：`VECTOR_STORE_TYPE`（默认 qdrant）、`QDRANT_ENABLED`、`LLAMA_ENABLED`；sqlite 规模护栏 <5 万片段；任务状态落 `SQLITE_PATH` 的 `reindex_state` 表；切后端按 `IndexNamespaces` 隔离切分签名，归档触发一次全量重建。158 单测绿；本机 JVM 冒烟（sqlite + 本地 ollama Qwen3-Embedding-0.6B）ingest → search → rag_query 通过，重启不重灌。
- **spring.ai.vectorstore.type 必须与 rag.vector-store.type 同步**（已在 application.yml 用同一 env 绑定），否则 Spring AI Qdrant 自动装配会另建 QdrantVectorStore 与 SQLite 后端竞争，且按参数名解析覆盖 SQLite Bean。
- **待办**：容器构建（本机内存不足，避免 docker build / llama.cpp 编译）+ 四形态冒烟 + `mvn verify -Pcontainer-it` 回归；恢复后第一件事是容器验证。
