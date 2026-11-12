# AGENTS.md — small-rag

单模块 Spring Boot 服务 `small-rag`：通过 MCP（Streamable HTTP）暴露**纯文本 RAG** 能力；向量库可配置——默认 **Qdrant**，可切换为内置 **SQLite** 引擎（`VECTOR_STORE_TYPE`）。v0.0.4 起只支持单机单容器部署。当前开发版本：**v0.0.5**（切片图谱与编辑管理）。

## 版本基线（严格锁定）

| 组件 | 版本 |
|---|---|
| JDK | 25（`maven.compiler.release=25`） |
| Spring Boot | 4.2.0（实际解析 `4.2.0-RC1`） |
| Spring AI | 2.0.2（Maven Central 稳定版） |
| Resilience4j | 2.4.0 |

- **解析现状（2026-11 验证）**：Boot GA 未发布，实际使用 `4.2.0-RC1`，依赖 `https://repo.spring.io/milestone`（pom 已配置）。**禁止降级到 Boot 3.x 来"修复"解析失败**。
- 项目版本 `0.0.5`；`ChunkingSignature` 签名版本与项目版本同步（当前 `v0.0.5`）。
- **不引入 Spring Cloud**（Gateway / Config Server / Consul / CircuitBreaker 包装 / LoadBalancer 均无）。

## 依赖与构建

- `pom.xml` 是单模块 `spring-boot-starter-parent` 项目；`spring-ai-bom` 以 `import` 引入。
- Starter 清单：`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-model-openai`（**不存在 `spring-ai-starter-model-embedding`**）、`spring-ai-starter-vector-store-qdrant`、`spring-boot-starter-web`、`spring-boot-starter-actuator`（仅 `/actuator/health`）、`resilience4j-circuitbreaker`、`lombok`（optional）。
- **不要**同时引入 STDIO 的 `spring-ai-starter-mcp-server` 与 webmvc starter——自动配置冲突。只用 webmvc，`protocol=STREAMABLE`。
- **不要**引入 Redis / Redisson / Consul / Gateway / Config Server；单实例互斥使用进程内锁。
- **JDK 23+ 默认不自动发现注解处理器**：必须 `<maven.compiler.proc>full</maven.compiler.proc>`，否则 Lombok "找不到符号"。
- 本机构建：`JAVA_HOME=/opt/jdk/jdk25 mvn clean package`；单测 `mvn test`；容器 E2E `mvn verify -Pcontainer-it`。
- **依赖源**：`pom.xml` 的 `<repositories>` / `<pluginRepositories>` 首位是 `aliyun-public`，其后才是 `spring-milestones`；容器构建额外用 `docker/maven-settings.xml`（`mirrorOf=*,!spring-milestones`）。

## 容器化

- **只保留一个 all-in-one 镜像**：镜像包含应用、Qdrant、llama.cpp，`supervisord` 作为 PID 1；`docker/entrypoint.sh` 直接启动 `supervisord-all-in-one.conf`。
- **四种部署形态**（同一镜像，靠进程级开关切换）：① `qdrant` + `QDRANT_ENABLED=false` + `LLAMA_ENABLED=false`（远程 Qdrant + 远程 Embedding，最轻）；② `qdrant` + `QDRANT_ENABLED=true` + `LLAMA_ENABLED=true`（本地全量，默认）；③ `sqlite` + `QDRANT_ENABLED=false` + `LLAMA_ENABLED=false`；④ `sqlite` + `QDRANT_ENABLED=false` + `LLAMA_ENABLED=true`。`VECTOR_STORE_TYPE=sqlite|qdrant`（默认 `qdrant`）；`QDRANT_ENABLED=true|false`；`LLAMA_ENABLED=true|false`。
- 只对外暴露 **18080**；Qdrant（6333/6334）与 Embedding 服务（11434）绑定 `127.0.0.1`。
- 多阶段构建：`maven:3.9-eclipse-temurin-25` 构建 → 以 `qdrant/qdrant` 为基底从源码编译 llama-server（`LLAMA_CPP_TAG`，默认 `v0.9.0`）→ 拷入 Temurin JRE；基础镜像可用 `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE` 替换。
- 默认向量模型 Qwen3-Embedding-0.6B Q8_0（1024 维），`start-llama.sh` 首次启动从 ModelScope 下载 GGUF（断点续传），以 `--embeddings --pooling last` 启动（**必须 last pooling**）。
- 启动脚本：`docker/start-qdrant.sh`、`start-llama.sh`、`start-app.sh`；数据统一 `/data`。
- 目录导入的源目录用只读 bind mount 挂到 `/data/sources/...`；`SMALL_RAG_SOURCE_DIRS` 必须是容器内路径；源目录必须对 UID `10001` 可读。
- **不再提供** `SMALL_RAG_RUNTIME=app-only`、`compose.distributed.yaml`、Gateway / Config Server 镜像。
- `.env` 已入库（仅模板值），**真实密钥不要写入 `.env`**。

## 架构分层

- `mcp`：`RagMcpTools` 暴露 `rag_ingest`/`rag_update`/`rag_delete`/`rag_list`/`rag_reindex`/`rag_rebuild`/`rag_task_status`/`rag_task_resume`/`rag_task_cancel`/`rag_sync_sources`/`rag_sync_status`/`rag_search`/`rag_query`/`rag_chunks`/`rag_graph`/`rag_graph_export`/`rag_get_chunk`/`rag_edit_chunk`/`rag_delete_chunk`（19 个）。**不是** `@Component`，由 `McpServerConfig` 显式注册。工具方法必须写 Javadoc。
- `rag`：`RagService` 负责分块入库、检索、上下文拼装、更新/删除/重建，**不调用 Chat/生成模型**；子块 point id 为 `chunk:<docId>:<切分签名>:<index>` 确定性生成（重复 upsert 覆盖同一批 point）；单篇 `reindex` 能枚举旧 id 时先写新片段再删旧片段；`rag_rebuild` 完成后清理无子块父块与缺 `chunk_type` 的旧片段；`IndexActivityGuard` 用进程内 owner 标记串行化重建与目录导入；`QueryCircuitBreaker` 直接使用 Resilience4j，只包 `rag_search`/`rag_query`；`KnowledgeBaseProbe` 空库短路。
- `rag/reindex`：`ReindexTaskService` 提供异步重建、进度快照与看门狗（每任务独立线程 + 单任务互斥 + 保留最近 20 个；停滞只标 `stalled`，硬超时撤销写权限、取消在途调用并中断线程，确认退出或宽限期到期后释放活动锁；`rag_task_cancel` 为文档边界协作式取消）。任务快照 + 检查点（`cleared` / 已完成文档 id）写入 Qdrant 独立集合 `<rag.qdrant.collection-name>_reindex_state`（1 维哑向量 + payload JSON，集合按需创建），不保存锁镜像；重启后未完成任务恢复为 `interrupted`，由 `resume-on-startup` 或 `rag_task_resume` 续接（跳过已完成文档、不重复清空）；`CancellationScope` 负责任务级 OkHttp 调用取消；`ChunkingReindexRunner` 启动自动重切（存在待续接任务时跳过）。
- `rag/sources`：`SourceSyncService` 扫描 `rag.sources.directories`（逗号分隔，支持 `label=/path`）；文档 id 由稳定 label + 相对路径确定性生成；先比 `mtime + size`，再比 SHA-256；触发：启动、定时、watcher、MCP `rag_sync_sources(force)`；`max-file-bytes` / `max-files-per-scan` 限流；`delete-missing` 只删除当前仍配置且本轮成功扫描的根目录下消失文件，`delete-orphan-roots` 清理已移除 label（默认关闭）；手动 `rag_delete` 留下墓碑。**单实例直接扫描，无 leader 选举**。
- `rag/chunking`：`ChunkingService` 分发 `fixed`/`recursive`/`semantic`/`parent-child`（默认）；`MarkdownChunker` 是 semantic 与 parent-child 共用的内部结构引擎；`SemanticTextChunker` 做超长文本句子级回落；长度单位是全宽字符当量（`TextWeights`：CJK=1、拉丁=0.25）；`ChunkingSignature` 只含影响切分结果的配置。
- `rag/archive`：`DocumentArchive` 按 `doc_id` 归档原文；`.archive/index.json` 摘要索引供 `rag_list`、启动重切、全文重建使用；索引缺失/版本不符/id 集合不一致时重建；归档不可写时降级关闭自动重切。
- `config`：`QdrantConfig`（qdrant 后端）、`SqliteVectorStoreConfig`（sqlite 后端）、`CommonRagConfig`、`McpServerConfig`、`EmbeddingConfig`（OpenAI SDK/OkHttp 超时）、`RagProperties`、`QdrantProperties`。
- **只使用向量模型**：`spring.ai.model.chat=none`；不新增 `ChatClient`/`ChatModel`。
- **Embedding 配置**：`EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`（回退 `OPENAI_*`）；base-url 必须含 `/v1`；不支持 JVM 内 ONNX；HTTP 超时用 `EMBEDDING_CONNECT_TIMEOUT_MS` / `EMBEDDING_READ_TIMEOUT_MS`。
- **Qdrant 约束**（仅 `VECTOR_STORE_TYPE=qdrant`）：gRPC 端口 `6334`；`Document` id 必须是 UUID；`doSimilaritySearch` 先 Embedding 再查库，空库用 `KnowledgeBaseProbe` 短路；集合初始化 fail-soft，维度不一致 fail-fast；gRPC deadline 用 `rag.qdrant.request-timeout-ms`；状态集合独立，不并入主集合。切换为 `sqlite` 时：数据与任务状态落到 `SQLITE_PATH`（默认 `/data/smallrag.sqlite`，WAL 模式）；纯 Java 余弦检索，规模建议 <5 万片段，更大请用 Qdrant；**同一 sqlite 文件禁止被多个容器实例共享**。
- **MCP**：`McpSyncServerCustomizer` 是单例且已被 WebMVC 自动配置占用，不要自定义；instructions 走 `spring.ai.mcp.server.*`。
- `application.yml` 必须：`protocol=STREAMABLE`、`name=small-rag`、`version=0.0.5`，MCP 端点 `/mcp`，payload 正文字段名共用 `RagService.CONTENT_FIELD`（"content"）。

## 代码约束

- 测试：默认 `mvn test` 不依赖外部服务；容器化 E2E 用 `mvn verify -Pcontainer-it`（Testcontainers + 真实 Qdrant，需 Docker/Podman）；CI 见 `.github/workflows/ci.yml`。
- Lombok：DTO 用 `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor`，Service 用 `@RequiredArgsConstructor` 构造注入（禁 `@Autowired` 字段注入），日志 `@Slf4j`。
- **Spring Boot 4 已迁移 Jackson 3**：import 一律 `tools.jackson.*`，`com.fasterxml.jackson.*` 编译失败。
- 暂不开启 AOT/Native Image。

## Git 与文档

- **分支模型（Git Flow）**：`main`（生产）与 `develop`（集成）；`feature/*` 从 `develop` 切出并 squash 合回；`release/x.y.z` 从 `develop` 收敛，`--no-ff` 合入 `main` 打 tag，再回灌 `develop`；`hotfix/*` 从 `main` 切出。禁止直接 push `main`/`develop`。
- 提交信息用 Conventional Commits。
- **开发记忆（强制）**：每次开发同步更新 `DEV-MEMORY.md`，只保留当前有效状态。

## 文档规范（强制）

### 1. 文档分工

| 文档 | 读者 | 写什么 |
| --- | --- | --- |
| `README.md` / `README.en.md` | 使用者 | 安装、运行、配置、MCP 工具、客户端、迁移 |
| `ROADMAP.md` | 维护者 | 版本目标、里程碑、验收、非目标 |
| `docs/v<version>/` | 开发者 / 运维 | 当前开发版本的规划、设计、ADR、运维手册 |
| `DEV-MEMORY.md` | 开发者 / AI | 当前有效状态、坑、待办 |
| `AGENTS.md` | AI / 贡献者 | 硬约束、构建命令、代码规范、文档治理 |

**版本绑定**：除 `docs/templates/` 外，`docs/` 下每份文档必须位于 `docs/v<version>/`，front matter 的 `version` 与目录一致。`docs/v0.0.3/` 已冻结（分布式版本说明），`docs/v0.0.4/` 已冻结（v0.0.4 已发布），当前开发文档在 `docs/v0.0.5/`。

### 2. README 双语

- `README.md` = 简体中文；`README.en.md` = English；第一行互相切换。
- 两份章节、表格、命令、版本号、默认值、端口、链接必须对等。
- 必须含：介绍、徽章、目录、版本矩阵、文档索引、快速开始、容器部署、配置、MCP 工具、客户端、切分、目录导入、测试、安全、贡献、许可证。
- 设计细节放 `docs/v0.0.5/design/`，README 只写使用说明。

### 3. 目录与模板

```text
docs/
├── templates/          # 唯一跨版本共享：模板与写作规范
└── v0.0.5/
    ├── plan.md
    ├── design/
    │   ├── architecture.md
    │   └── limitations.md
    └── adr/
        └── 0001-single-node-scope.md
```

- 版本目录 `docs/v<MAJOR>.<MINOR>.<PATCH>/`；文件名小写 kebab-case；ADR 四位编号只增不改。
- 文档类型与模板：规划 [`docs/templates/plan.md`](docs/templates/plan.md)、设计 [`design.md`](docs/templates/design.md)、ADR [`adr.md`](docs/templates/adr.md)、运维 [`runbook.md`](docs/templates/runbook.md)。
- 写作规范只放在模板文件内；AGENTS 不重复。

### 4. 文档头

```yaml
---
title: 一句话标题
status: draft | review | approved | implementing | done | superseded
version: 0.0.5
updated: 2026-11-12
owners: [zjk]
template: docs/templates/<type>.md
related: [ROADMAP.md, DEV-MEMORY.md]
---
```

`docs/` 下文档必须含 `template`；`ROADMAP.md` / `DEV-MEMORY.md` 不需要。`updated` 必须是最后修改日期。

### 5. 生命周期

`draft → review → approved → implementing → done`；被替代标 `superseded` 并写替代链接。版本发布后该版本目录冻结，下一版本新建 `docs/vX.Y.Z+1/`。

### 6. 提交前检查

- [ ] front matter 完整，`version` 与目录一致，`updated` 是今天
- [ ] `docs/` 下文档含 `template`，内容符合模板规范
- [ ] 没有在 `docs/` 根下新建 `plans/` / `design/` / `adr/` / `runbooks/`
- [ ] 数字、端口、命令与代码一致
- [ ] 没有复制其他文档已有细节
- [ ] 相对链接可打开
- [ ] 中英文 README 对等

## 验收标准

1. `mvn clean package` 成功；`mvn test` 无外部依赖可跑。
2. 启动后 `/mcp` 可用，MCP Inspector 能列出全部 19 个工具。
3. 空知识库下 `rag_query` 返回友好提示。
4. 端到端（入库→检索）需要 Qdrant + OpenAI 兼容 Embedding；不需要 Chat 模型。
5. README 步骤可复现，`git log` 至少一条 Conventional Commits。
