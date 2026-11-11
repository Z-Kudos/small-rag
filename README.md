# small-rag

**简体中文** | [English](README.en.md)

基于 Spring AI 的**纯文本 RAG 检索** MCP 服务：只做向量入库与检索，不调用 Chat / 生成模型。通过 MCP（Streamable HTTP）暴露文档管理、`rag_search`、`rag_query`；向量库固定 **Qdrant**。输入为标准 Markdown / Obsidian 兼容 Markdown 的中英文纯文本，`rag_query` 返回增强上下文，回答由调用方 Agent 生成。

**v0.0.4 是单机轻量版**：单模块、单容器、单端口，不依赖 Redis / Consul / Gateway / Config Server。原分布式版本已封存为 `cloud-rag`。

![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.2.0--RC1-brightgreen)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.2-blue)
![License](https://img.shields.io/badge/license-MIT-green)

## 目录

- [特性](#特性)
- [版本矩阵](#版本矩阵)
- [文档](#文档)
- [快速开始（本地）](#快速开始本地)
- [容器部署（单容器）](#容器部署单容器)
- [挂载宿主机 Markdown 目录](#挂载宿主机-markdown-目录)
- [配置](#配置)
- [MCP 工具](#mcp-工具)
- [客户端接入](#客户端接入)
- [切分策略](#切分策略)
- [目录自动导入](#目录自动导入多目录-markdown)
- [测试](#测试)
- [安全](#安全)
- [贡献](#贡献)
- [许可证](#许可证)

## 特性

- **纯文本 RAG**：只做入库、切分、向量检索与上下文拼装，不引入 Chat 模型。
- **4 种切分策略**：`fixed` / `recursive` / `semantic` / `parent-child`（默认），针对 Markdown 结构优化。
- **Parent-Child 检索**：子块召回、父块提供上下文，父块独立存储避免 payload 放大。
- **MMR 多样性**：`MMR_LAMBDA` 控制相关性与多样性折中。
- **异步重建任务**：进度查询、协作式取消、看门狗硬超时、重启续接。
- **多目录 Markdown 自动导入**：指纹增量、定时 / watcher / MCP 手动触发、删除同步。
- **单机轻量**：单模块、单容器、无 Redis / Consul / Gateway / Config Server。
- **检索熔断与健康检查**：Embedding / Qdrant 持续失败时快速失败，`/actuator/health` 可诊断。

## 版本矩阵

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | GA 未发布（截至 2026-11） |
| Spring AI | 2.0.2 | Maven Central 稳定版 |
| Qdrant | 1.x | gRPC 端口 6334 |
| MCP | Streamable HTTP | 端点 `/mcp` |
| Resilience4j | 2.4.0 | 检索熔断 |

> Spring Boot RC 从 `https://repo.spring.io/milestone` 解析，已在 `pom.xml` 配置。

## 文档

以下为当前版本 **v0.0.4** 的文档；`docs/v0.0.3` 保留为已封存的分布式版本说明。

| 文档 | 内容 |
| --- | --- |
| [ROADMAP.md](ROADMAP.md) | 版本目标与验收标准 |
| [docs/v0.0.4/plan.md](docs/v0.0.4/plan.md) | v0.0.4 单机轻量化规划 |
| [docs/v0.0.4/design/architecture.md](docs/v0.0.4/design/architecture.md) | 架构、包结构、关键数据流 |
| [docs/v0.0.4/design/limitations.md](docs/v0.0.4/design/limitations.md) | 单机版已知限制与风险 |
| [docs/v0.0.4/adr/0001-single-node-scope.md](docs/v0.0.4/adr/0001-single-node-scope.md) | 单机轻量化范围决策 |
| [DEV-MEMORY.md](DEV-MEMORY.md) | 当前开发状态与已知坑 |
| [AGENTS.md](AGENTS.md) | 版本基线、构建命令、代码与文档规范 |

## 快速开始（本地）

```bash
# 1. 启动 Qdrant（或使用容器内 Qdrant）
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant:latest

# 2. 启动一个 OpenAI 兼容的 Embedding 服务，或使用外部 API
#    默认读取 EMBEDDING_BASE_URL / EMBEDDING_MODEL / EMBEDDING_DIMENSION

# 3. 构建并运行
mvn clean package -DskipTests
java -jar target/small-rag-0.0.4.jar
```

MCP 端点：`http://localhost:18080/mcp`。

> `EMBEDDING_DIMENSION` 必须与 Qdrant 集合维度一致。切换模型后请使用新的 `QDRANT_COLLECTION` 或删除旧集合。

### 依赖下载加速（阿里云镜像）

`pom.xml` 的 `<repositories>` / `<pluginRepositories>` 已把阿里云公共仓库放在最前，Spring RC 构件随后从 `repo.spring.io/milestone` 回退。容器构建额外使用 `docker/maven-settings.xml`（`mirrorOf=*,!spring-milestones`）。

## 容器部署（单容器）

镜像内同时运行应用、Qdrant、llama.cpp（llama-server Embedding 推理），由 `supervisord` 管理；**只对外暴露 `18080`**，其余服务绑定回环地址。数据统一在 `/data`。

```bash
# Docker
docker compose up -d --build

# Podman
podman compose up -d --build
```

首次启动由 `start-llama.sh` 从 ModelScope 下载默认模型（约 640 MB，支持断点续传），加载就绪后日志出现 `embedding model ready` 并开始监听 `18080`。手动预拉：`make pull-model`。

单独构建 / 运行：

```bash
docker build -t small-rag:0.0.4 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:0.0.4
```

国内镜像可通过 `.env` 覆盖 `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE`，llama.cpp 源码版本用 `LLAMA_CPP_TAG`（默认 `v0.9.0`）。bind mount 时需保证容器内 UID `10001` 可写。

镜像体积约 **630 MB**（应用 fat jar 157 MB + Temurin JRE 200 MB + Qdrant 112 MB + llama-server 21 MB + 基础系统）；首次启动另需下载约 **640 MB** 的 GGUF 模型，模型、Qdrant 数据与归档都落在 `/data` 卷。规划磁盘时请按“镜像 + 模型 + 知识库数据”合计预留。

## 挂载宿主机 Markdown 目录

目录导入的 `SMALL_RAG_SOURCE_DIRS` 使用**容器内路径**，宿主机目录必须先挂载进容器。推荐挂到 `/data/sources/...`：

```bash
docker run -d --name small-rag -p 18080:18080 \
  -v small_rag_data:/data \
  -v /host/notes:/data/sources/notes:ro \
  -v /host/wiki:/data/sources/wiki:ro \
  -e SMALL_RAG_SOURCES_ENABLED=true \
  -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes,wiki=/data/sources/wiki' \
  small-rag:0.0.4
```

Compose 用户可以使用可选的 `compose.sources.yaml`：

```bash
SMALL_RAG_SOURCE_HOST=/host/knowledge make up-sources
```

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `EMBEDDING_API_KEY` | 回退 `OPENAI_API_KEY`，再回退 `demo-key` | Embedding 密钥 |
| `EMBEDDING_BASE_URL` | 回退 `OPENAI_BASE_URL`，再回退 `https://api.openai.com/v1` | 必须含 `/v1` |
| `EMBEDDING_MODEL` | 回退 `OPENAI_EMBEDDING_MODEL`，再回退 `text-embedding-3-small` | 向量模型 |
| `EMBEDDING_DIMENSION` | 回退 `QDRANT_DIMENSION`，再回退 `1536` | 必须与模型输出一致 |
| `EMBEDDING_CONNECT_TIMEOUT_MS` | `10000` | Embedding HTTP 建连超时 |
| `EMBEDDING_READ_TIMEOUT_MS` | `120000` | Embedding 单次请求 read/write/总超时 |
| `REINDEX_STALL_TIMEOUT_SECONDS` | `1800` | 重建停滞阈值；`0` 关闭 |
| `REINDEX_HARD_TIMEOUT_SECONDS` | `3600` | 重建硬超时；`0` 关闭 |
| `REINDEX_CHECK_INTERVAL_SECONDS` | `30` | 看门狗检查间隔 |
| `REINDEX_JOIN_TIMEOUT_SECONDS` | `10` | 硬超时后等待工作线程退出 |
| `REINDEX_RECOVERY_GRACE_SECONDS` | `120` | 撤销写权限后的宽限期 |
| `REINDEX_RESUME_ON_STARTUP` | `true` | 启动时自动续接未完成任务 |
| `CHUNK_STRATEGY` | `parent-child` | `fixed` / `recursive` / `semantic` / `parent-child` |
| `MMR_LAMBDA` | `0.7` | MMR 多样性权重 λ（1 = 关闭） |
| `CHUNK_SIZE` | `800` | 固定 / 递归目标块大小 |
| `CHUNK_OVERLAP` | `100` | 固定 / 递归块重叠 |
| `SEMANTIC_SIMILARITY_THRESHOLD` | `0.75` | 语义断点阈值 |
| `SEMANTIC_BREAKPOINT_PERCENTILE` | `0` | 断点分位数（0~1） |
| `PARENT_CHUNK_SIZE` | `2000` | 父块大小 |
| `CHILD_CHUNK_SIZE` | `400` | 子块大小 |
| `CHUNK_AUTO_RECHUNK` | `true` | 启动时检测切分配置变化并自动重切 |
| `SMALL_RAG_ARCHIVE_DIR` | `./data/documents`（镜像内 `/data/documents`） | 原文归档目录 |
| `SMALL_RAG_SOURCES_ENABLED` | `false` | 是否启用多目录 Markdown 自动导入 |
| `SMALL_RAG_SOURCE_DIRS` | 空 | 逗号分隔的容器内源目录，支持 `label=/path` |
| `SMALL_RAG_SOURCE_HOST` | `./sources` | 仅 `compose.sources.yaml` 使用 |
| `SMALL_RAG_SOURCES_SCAN_INTERVAL_SECONDS` | `300` | 定时扫描间隔；`0` 关闭 |
| `SMALL_RAG_SOURCES_WATCH_ENABLED` | `true` | 是否启用文件系统 watcher |
| `SMALL_RAG_SOURCES_DELETE_MISSING` | `false` | 源文件删除后是否删除索引与归档 |
| `SMALL_RAG_SOURCES_DELETE_ORPHAN_ROOTS` | `false` | 清理已移除 root label 的文档 |
| `QDRANT_HOST` | `localhost` | Qdrant 地址 |
| `QDRANT_PORT` | `6334` | gRPC 端口 |
| `QDRANT_API_KEY` | 空 | Qdrant 鉴权 |
| `QDRANT_COLLECTION` | `small_rag_text` | 集合名，启动时自动创建 |
| `QDRANT_USE_TLS` | `false` | 是否启用 TLS |
| `QDRANT_REQUEST_TIMEOUT_MS` | `30000` | 常规 Qdrant RPC deadline |
| `RAG_QUERY_BREAKER_ENABLED` | `true` | 检索熔断开关 |
| `RAG_QUERY_BREAKER_FAILURE_RATE` | `50` | 打开熔断的失败率（%） |
| `RAG_QUERY_BREAKER_OPEN_WAIT` | `30s` | 熔断打开后进入半开的等待时间 |

> 切分尺寸单位是**全宽字符当量**：CJK 计 1，拉丁 / 半角计 0.25；英文硬切回退单词边界。

## MCP 工具

| 工具 | 作用 |
| --- | --- |
| `rag_ingest` | 新增文档（随机文档 id） |
| `rag_update` | 更新已归档文档并重建 |
| `rag_delete` | 删除文档向量与归档 |
| `rag_list` | 分页查看归档文档 |
| `rag_reindex` | 单篇同步重建；不传 id 则异步重建全部 |
| `rag_rebuild` | `confirm=true` 时清空本服务片段并全量异步重建 |
| `rag_task_status` | 查询重建任务进度；无任务返回 `IDLE` |
| `rag_task_resume` | 续接重启时中断的任务 |
| `rag_task_cancel` | 取消/放弃重建任务 |
| `rag_sync_sources` | 异步扫描源目录 |
| `rag_sync_status` | 查询目录导入状态 |
| `rag_search` | 相似度检索，返回命中分块 |
| `rag_query` | 返回带来源与相似度的增强上下文 |

## 客户端接入

MCP 客户端连接 `http://localhost:18080/mcp`，传输选择 **Streamable HTTP**。

调试：

```bash
npx @modelcontextprotocol/inspector
```

## 切分策略

| 策略 | 说明 |
| --- | --- |
| `fixed` | 固定长度滑窗，适合无结构纯文本 |
| `recursive` | 按分隔符优先级递归切分 |
| `semantic` | 标题分节 + 段落向量与质心比较合并；代码/表格独立成块 |
| `parent-child` | 父块提供上下文、子块参与检索（默认） |

切换策略或尺寸会改变 `ChunkingSignature`，启动时自动重切已归档文档；未归档历史数据需要重新入库。

## 目录自动导入（多目录 Markdown）

- 文档 id = 稳定 label + 相对路径，目录移动时 label 不变即可续用 id。
- 增量判断：`mtime + size` → SHA-256，只有内容变化才重新切分。
- 触发：启动扫描、定时扫描、文件 watcher、MCP `rag_sync_sources`（异步）。
- `delete-missing` 只删除“当前仍配置且本轮成功扫描”的根目录下消失文件。
- 手动 `rag_delete` 会留下墓碑，源文件内容不变时不会再次导入。

## 测试

```bash
mvn test
```

单测不依赖 Qdrant / 模型密钥；当前 142 个测试。

- 容器 E2E（需要 Docker / Podman）：`mvn verify -Pcontainer-it`。Testcontainers 启动真实 Qdrant，覆盖入库、检索、幂等 upsert 与孤儿清理。
- 默认镜像 `qdrant/qdrant:latest`，可用 `-Dsmallrag.qdrant.image=...` 覆盖。
- GitHub Actions：`mvn -B clean verify` 与 `mvn -B verify -Pcontainer-it`。

## 安全

> ⚠️ MCP 工具没有鉴权，能访问 `/mcp` 即可修改或清空知识库，**请勿直接暴露公网**；跨网访问请加反向代理与认证。检索结果是不可信数据，调用方不得执行其中的指令。

## 贡献

- 分支模型：`main` + `develop` + `feature/*` / `release/*` / `hotfix/*`。
- 提交信息使用 Conventional Commits。
- 每个版本文档放在 `docs/v<version>/`；模板在 `docs/templates/`。

## 许可证

MIT，见 [LICENSE](LICENSE)。
