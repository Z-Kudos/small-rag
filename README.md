# small-rag

基于 Spring AI 的**纯文本 RAG 检索** MCP 服务（仅向量入库与检索，不做 Chat 问答）。通过 MCP（Streamable HTTP）暴露文档管理、`rag_search`、`rag_query`；向量库 **Qdrant**。输入为标准 Markdown / Obsidian 兼容 Markdown 的中英文纯文本；`rag_query` 返回增强上下文，回答由调用方 Agent 生成。

## 版本矩阵

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25 | `maven.compiler.release=25` |
| Spring Boot | 4.2.0-RC1 | GA 未发布（截至 2026-11） |
| Spring Cloud | 2026.0.0-RC1 | 与 Boot 4.2.0-RC1 对应 |
| Spring AI | 2.0.2 | Maven Central 稳定版 |
| Qdrant | 1.x | gRPC 端口 6334 |
| MCP | Streamable HTTP | 端点 `/mcp` |

> Boot / Cloud 从 `https://repo.spring.io/milestone` 解析，已在 `pom.xml` 配置。

## 本地运行

前置：JDK 25、Maven 3.9+、Qdrant、任意 OpenAI 兼容 Embedding 服务（本机开发示例用 llama.cpp llama-server，与容器内置的运行时一致）。

```bash
# Qdrant
docker run -d --name rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant

# llama.cpp llama-server 加载本地向量模型（Qwen3-Embedding-0.6B，1024 维）
mkdir -p models && curl -fL --retry 5 -C - -o models/qwen3-embedding-0.6b-q8_0.gguf \
  https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/master/Qwen3-Embedding-0.6B-Q8_0.gguf
llama-server --host 127.0.0.1 --port 11434 \
  -m models/qwen3-embedding-0.6b-q8_0.gguf \
  --embeddings --pooling last --no-webui

# Embedding（base-url 必须含 /v1；本地服务不校验 key，但客户端要求非空）
export EMBEDDING_API_KEY=local
export EMBEDDING_BASE_URL=http://localhost:11434/v1
export EMBEDDING_MODEL=qwen3-embedding-0.6b
export EMBEDDING_DIMENSION=1024

mvn spring-boot:run
```

MCP 端点：`http://localhost:18080/mcp`。

> `EMBEDDING_DIMENSION=1024` 必须与 Qdrant 集合维度一致。之前用 1536 维模型创建过集合的，请设置新的 `QDRANT_COLLECTION` 或删除旧集合后再启动。

## 容器部署（单容器）

镜像内同时运行应用、Qdrant、llama.cpp（llama-server Embedding 推理），由 `supervisord` 管理；**只对外暴露 `18080`**，其余服务绑定回环地址。数据统一在 `/data`（Qdrant 向量库与重建任务状态 + Embedding 模型 + 文档归档），建议挂载卷。

```bash
# Docker
docker compose up -d --build

# Podman
podman compose up -d --build   # 或 podman-compose up -d --build
```

首次启动由 `start-llama.sh` 从 ModelScope 下载默认模型（约 640 MB，支持断点续传），加载就绪后日志出现 `embedding model ready` 并开始监听 `18080`。手动预拉：`make pull-model`。

单独构建/运行：

```bash
docker build -t small-rag:0.0.2 .
docker run -d --name small-rag -p 18080:18080 -v small_rag_data:/data small-rag:0.0.2
```

国内镜像可通过 `.env` 覆盖 `MAVEN_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE`，llama.cpp 源码版本用 `LLAMA_CPP_TAG`（默认 `v0.9.0`，镜像内从源码编译 llama-server，与运行时同基底无 glibc 兼容问题）。bind mount 时需保证容器内 UID `10001` 可写：`sudo chown -R 10001:10001 /path/to/data`。

### 挂载宿主机 Markdown 目录

目录导入的 `SMALL_RAG_SOURCE_DIRS` 使用的是**容器内路径**，所以宿主机目录必须先通过 `-v` / Compose `volumes` 挂进容器。推荐挂到 `/data/sources/...`：

```bash
# 分散的多个宿主机目录：一条 -v 对应一个目录（只读挂载）
docker run -d --name small-rag -p 18080:18080 \
  -v small_rag_data:/data \
  -v /host/notes:/data/sources/notes:ro \
  -v /host/wiki:/data/sources/wiki:ro \
  -e SMALL_RAG_SOURCES_ENABLED=true \
  -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes,wiki=/data/sources/wiki' \
  small-rag:0.0.2
```

```bash
# 或者只挂一个宿主机根目录，导入其下的多个子目录
docker run -d --name small-rag -p 18080:18080 \
  -v small_rag_data:/data \
  -v /host/knowledge:/data/sources:ro \
  -e SMALL_RAG_SOURCES_ENABLED=true \
  -e SMALL_RAG_SOURCE_DIRS='notes=/data/sources/notes,wiki=/data/sources/wiki' \
  small-rag:0.0.2
```

Compose 用户可以直接使用可选的 `compose.sources.yaml`：

```bash
# 默认把 .env 的 SMALL_RAG_SOURCE_HOST（默认 ./sources）挂到 /data/sources
SMALL_RAG_SOURCE_HOST=/host/knowledge \
  docker compose -f compose.yaml -f compose.sources.yaml up -d --build

# 或先写入 .env，再执行
make up-sources
```

`compose.sources.yaml` 默认启用 `SMALL_RAG_SOURCES_ENABLED=true`，并导入 `/data/sources/notes`、`/data/sources/wiki`。目录不在这两个子目录下时，修改 `.env` 的 `SMALL_RAG_SOURCE_DIRS`（使用 `label=容器内路径` 形式），或编辑 `compose.sources.yaml`。

注意：

- 宿主机目录必须对容器内 UID `10001` 可读；只读挂载用 `:ro` 即可，应用只读取源文件。权限不足时可用 `sudo chmod -R a+rX /host/notes` 或 `sudo chown -R 10001:10001 /host/notes`。
- `SMALL_RAG_SOURCE_DIRS` 不是宿主机路径，而是容器内路径；宿主机路径只出现在 `-v`/`volumes` 左侧。
- label 用于稳定文档 id：目录移动时保持 label 不变即可续用原文档；label 重复会拒绝扫描。

### 换模型 / 外部 Embedding

- 换容器内模型：修改 `EMBEDDING_MODEL_URL`（GGUF 下载地址）与 `EMBEDDING_DIMENSION`，并换新的 `QDRANT_COLLECTION`；`EMBEDDING_MODEL` 只是 API 侧名称，llama-server 不校验。
- 外部 Embedding：`SMALL_RAG_ENSURE_EMBEDDING_MODEL=false`，配置 `EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`。

## 分布式模式（可选，v0.0.2 开发中）

默认仍是单容器 standalone。需要多副本时使用 overlay：Qdrant / Redis / Consul / Embedding 独立部署，应用以 `app-only` 模式运行，HAProxy gateway 统一暴露 18080。

```bash
docker compose -f compose.yaml -f compose.distributed.yaml build
docker compose -f compose.yaml -f compose.distributed.yaml up -d --scale small-rag=2
# 或：make up-distributed
```

- 需要 Docker Compose v2.24+（overlay 使用 `!reset []` 去掉应用自身的端口映射）。
- 两个副本共享 `small_rag_data:/data` 作为归档卷；多主机部署需换成 NFS/CephFS 等 RWX 存储。
- 应用通过 `SMALL_RAG_DISTRIBUTED_ENABLED=true` 启用 Redis 租约锁；重建任务、目录导入、启动自动重切共用一把跨副本锁，同一时刻只有一个副本写入。
- 锁租约默认 30s、每 10s 续租；持有者失联后，其他副本在租约过期后接管。续租失败会立即停止本地写入（`IndexActivityGuard.isHeldBy=false`）。
- 目录自动扫描（启动/定时/watcher）只由 leader 执行：各副本竞争 `sources-leader` 租约，leader 失联后由其他副本接管；MCP 手动 `rag_sync_sources` 不受 leader 限制，仍由索引活动锁串行化。
- `CONSUL_ENABLED=true` 时应用注册到 Consul（`small-rag` 服务，健康检查 `/actuator/health`），并可按需从 Consul KV 读取配置。overlay 内置单节点 Consul server（生产建议三节点）。
- Consul 配置默认使用 `KEY_VALUE` 格式：每个配置项一个 key，例如
  ```bash
  consul kv put config/small-rag/logging.level.com.smallrag.ragmcp DEBUG
  ```
  启动时加 `SPRING_CONFIG_IMPORT=optional:consul:`；若要用单个 `data` key 存 YAML，需设置 `spring.cloud.consul.config.format=YAML`。
- Gateway 目前用 HAProxy 提供，Spring Cloud Gateway 在 P3 评估。详见 [ROADMAP.md](ROADMAP.md)。

### 从 standalone 迁移到 distributed

1. 停止单容器实例，保留 `small_rag_data`（归档）与容器内 `/data/qdrant/storage`（向量数据）。
2. 用 overlay 启动外部 Qdrant/Redis/Consul/Embedding；`small_rag_data` 直接复用，跨主机时换成 RWX 卷。
3. 向量数据可复制到新的 `qdrant_data` 卷，或清空后重新 `rag_rebuild`/重新入库；复制时注意 Qdrant 版本一致。
4. 设置 `SMALL_RAG_DISTRIBUTED_ENABLED=true`、`SMALL_RAG_RUNTIME=app-only`、共享 `SMALL_RAG_ARCHIVE_DIR`，按需打开 `CONSUL_ENABLED`。
5. 通过 gateway 的 `/mcp` 验证；`/actuator/health` 确认只有一个副本 `sourcesLeader=true`，索引写入由 `activeLock` 指示。

### 容量与故障处理

- **Redis 故障**：索引活动锁与 leader 选举失败，重建/目录导入停止并返回可读错误；检索不受影响。恢复后自动重新竞选。
- **Embedding 故障**：`rag_search`/`rag_query` 由熔断快速失败；入库/重建按超时失败并在下次重试，不会永久占用线程。
- **Qdrant 故障**：检索有 gRPC deadline；重建任务可能标记 `stalled`，硬超时后撤销写权限，恢复后可 `rag_task_resume`。
- **副本失联**：索引活动锁在租约（默认 30s）过期后由其他副本接管；目录扫描 leader 在下一轮选举接管。
- **Consul 故障**：`CONSUL_CONFIG_FAIL_FAST=false` 允许继续启动；服务发现恢复后重新注册。
- **归档卷**：必须共享且可写；建议 RWX 存储并纳入备份，否则多副本无法自动重切/续接。

### 熔断与健康检查

- `rag_search` / `rag_query` 经过 Resilience4j 熔断：默认窗口 20 次、最少 10 次调用、失败率 50% 打开、30s 后进入半开。Embedding 或 Qdrant 持续失败时快速返回“知识库检索暂时不可用（熔断已打开）”，不再占用请求线程；入库与重建不受影响。
- 可用 `RAG_QUERY_BREAKER_*` 环境变量调整，或 `RAG_QUERY_BREAKER_ENABLED=false` 关闭。
- `GET /actuator/health` 返回实例 id、是否 distributed、活动索引锁、目录扫描 leader、熔断状态与 active profiles，便于确认多副本中谁在写入。

## 配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `EMBEDDING_API_KEY` | 回退 `OPENAI_API_KEY`，再回退 `demo-key` | Embedding 密钥 |
| `EMBEDDING_BASE_URL` | 回退 `OPENAI_BASE_URL`，再回退 `https://api.openai.com/v1` | 必须含 `/v1` |
| `EMBEDDING_MODEL` | 回退 `OPENAI_EMBEDDING_MODEL`，再回退 `text-embedding-3-small` | 向量模型 |
| `EMBEDDING_DIMENSION` | 回退 `QDRANT_DIMENSION`，再回退 `1536` | 必须与模型输出一致 |
| `EMBEDDING_CONNECT_TIMEOUT_MS` | `10000` | Embedding HTTP 建连超时 |
| `EMBEDDING_READ_TIMEOUT_MS` | `120000` | Embedding 单次请求 read/write/总超时，防止服务假死时重建线程被永久占用 |
| `REINDEX_STALL_TIMEOUT_SECONDS` | `1800` | 重建停滞阈值：超过该时长无进度只标记 `stalled` 并告警；`0` 关闭 |
| `REINDEX_HARD_TIMEOUT_SECONDS` | `3600` | 重建硬超时：撤销写权限、取消在途 Embedding 调用并中断工作线程；`0` 关闭 |
| `REINDEX_CHECK_INTERVAL_SECONDS` | `30` | 看门狗检查间隔 |
| `REINDEX_JOIN_TIMEOUT_SECONDS` | `10` | 硬超时中断后等待工作线程退出的时间 |
| `REINDEX_RECOVERY_GRACE_SECONDS` | `120` | 撤销写权限后的宽限期（秒），到期强制释放任务锁；实际值不小于 `QDRANT_REQUEST_TIMEOUT_MS` 的 2 倍 |
| `REINDEX_RESUME_ON_STARTUP` | `true` | 启动时自动续接重启前未完成的重建任务；`false` 时停在 `interrupted` 并继续持锁 |
| `CHUNK_STRATEGY` | `parent-child` | `fixed` / `recursive` / `semantic` / `parent-child` |
| `MMR_LAMBDA` | `0.7` | 检索 MMR 多样性权重 λ（0 < λ < 1 启用，1 = 纯相关排序） |
| `CHUNK_SIZE` | `800` | 固定/递归目标块大小，语义切分默认上限 |
| `CHUNK_OVERLAP` | `100` | 固定/递归块重叠 |
| `SEMANTIC_SIMILARITY_THRESHOLD` | `0.75` | 文本块与当前块质心的相似度低于该值时断开（`breakpoint-percentile > 0` 时忽略） |
| `SEMANTIC_BREAKPOINT_PERCENTILE` | `0` | 断点分位数（0~1）：大于 0 时取相邻文本块相似度的该分位数作断点阈值，对语言/模型分布差异更鲁棒。真实语料校准（Qwen3-Embedding、技术文档）：0.1~0.4 输出一致且比绝对阈值少约 5% 碎块，技术文档建议从 `0.2` 起试 |
| `SEMANTIC_MIN_CHUNK_CHARS` | `200` | 语义切分目标最小块 |
| `SEMANTIC_MAX_CHUNK_CHARS` | `0` | `0` 表示回落到 `CHUNK_SIZE` |
| `PARENT_CHUNK_SIZE` | `2000` | Parent-Child 父块大小 |
| `PARENT_CHUNK_OVERLAP` | `0` | 父块重叠（子块不跨父块边界，默认关闭，避免重复命中） |
| `CHILD_CHUNK_SIZE` | `400` | Parent-Child 子块大小（写入向量库） |
| `CHILD_CHUNK_OVERLAP` | `50` | Parent-Child 子块重叠 |
| `CHUNK_AUTO_RECHUNK` | `true` | 启动时检测切分配置变化并自动重切已归档文档 |
| `SMALL_RAG_ARCHIVE_DIR` | `./data/documents`（镜像内 `/data/documents`） | 原文归档目录，自动重切的数据来源 |
| `SMALL_RAG_SOURCES_ENABLED` | `false` | 是否启用多目录 Markdown 自动导入 |
| `SMALL_RAG_SOURCE_DIRS` | 空 | 逗号分隔的容器内源目录，支持 `label=/path`；label 默认取目录名，是文档 id 的稳定命名空间 |
| `SMALL_RAG_SOURCE_HOST` | `./sources` | 仅 `compose.sources.yaml` 使用：挂载到容器 `/data/sources` 的宿主机目录 |
| `SMALL_RAG_SOURCE_EXTENSIONS` | `.md,.markdown` | 参与导入的扩展名（大小写不敏感） |
| `SMALL_RAG_SOURCE_EXCLUDE` | `**/node_modules/**,**/target/**,**/build/**` | 相对源目录的排除 glob |
| `SMALL_RAG_SOURCES_SKIP_HIDDEN` | `true` | 跳过 `.` 开头的目录/文件（`.git`、`.obsidian` 等） |
| `SMALL_RAG_SOURCES_FOLLOW_SYMLINKS` | `false` | 是否跟随符号链接 |
| `SMALL_RAG_SOURCES_SCAN_ON_STARTUP` | `true` | 应用就绪后是否立即扫描一次 |
| `SMALL_RAG_SOURCES_SCAN_INTERVAL_SECONDS` | `300` | 定时扫描间隔；`0` 关闭定时扫描 |
| `SMALL_RAG_SOURCES_WATCH_ENABLED` | `true` | 是否启用文件系统 watcher（带防抖） |
| `SMALL_RAG_SOURCES_WATCH_DEBOUNCE_SECONDS` | `5` | watcher 防抖时间（秒） |
| `SMALL_RAG_SOURCES_MAX_FILE_BYTES` | `2097152` | 单文件大小上限；`0` 不限制 |
| `SMALL_RAG_SOURCES_MAX_FILES_PER_SCAN` | `0` | 每轮最多处理多少篇变化文档；`0` 不限制（未变化文件不占额度） |
| `SMALL_RAG_SOURCES_DELETE_MISSING` | `false` | 源文件删除后是否同时删除对应索引与归档 |
| `SMALL_RAG_SOURCES_DELETE_ORPHAN_ROOTS` | `false` | 根目录从配置移除后，是否删除该 label 下已导入的文档 |
| `SMALL_RAG_SOURCES_STATE_FILE` | 空（`<archive-dir>/.sources/state.json`） | 增量状态文件路径 |
| `QDRANT_HOST` | `localhost` | Qdrant 地址 |
| `QDRANT_PORT` | `6334` | gRPC 端口 |
| `QDRANT_API_KEY` | 空 | Qdrant 鉴权 |
| `QDRANT_COLLECTION` | `small_rag_text` | 集合名，启动时自动创建 |
| `QDRANT_USE_TLS` | `false` | 是否启用 TLS |
| `QDRANT_REQUEST_TIMEOUT_MS` | `30000` | 常规 Qdrant RPC（写入/删除/检索）deadline；与初始化超时分开 |
| `SMALL_RAG_DISTRIBUTED_ENABLED` | `false` | 启用 distributed 模式（Redis 租约锁）；standalone 不连接 Redis |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / 空 | Redis 连接（仅 distributed 使用） |
| `SMALL_RAG_LOCK_LEASE` | `30s` | 索引活动锁租约 |
| `SMALL_RAG_LOCK_RENEW_INTERVAL` | `10s` | 续租间隔，必须明显小于租约 |
| `SMALL_RAG_LOCK_PREFIX` | `smallrag:lock:` | 锁 key 前缀 |
| `CONSUL_ENABLED` | `false` | 启用 Consul 服务注册与配置（服务名 `small-rag`） |
| `CONSUL_HOST` / `CONSUL_PORT` | `localhost` / `8500` | Consul agent 地址 |
| `CONSUL_REGISTER` | `true` | 是否注册到 Consul |
| `CONSUL_CONFIG_FAIL_FAST` | `false` | Consul 不可达时是否阻止启动 |
| `SPRING_CONFIG_IMPORT` | 空 | 设为 `optional:consul:` 时从 Consul KV 读取配置 |
| `RAG_QUERY_BREAKER_ENABLED` | `true` | `rag_search`/`rag_query` 熔断开关 |
| `RAG_QUERY_BREAKER_FAILURE_RATE` | `50` | 打开熔断的失败率（%） |
| `RAG_QUERY_BREAKER_OPEN_WAIT` | `30s` | 熔断打开后进入半开的等待时间 |
| `SMALL_RAG_ENSURE_EMBEDDING_MODEL` | `true`（镜像内） | 启动时等待容器内 Embedding 服务就绪（模型由 `start-llama.sh` 下载） |
| `EMBEDDING_MODEL_URL` | Qwen3-Embedding-0.6B Q8_0（ModelScope） | GGUF 模型下载地址 |
| `EMBEDDING_MODEL_PATH` | `/data/models/qwen3-embedding-0.6b-q8_0.gguf` | GGUF 落盘路径（须在持久化卷内） |
| `EMBEDDING_CTX_SIZE` | `8192` | llama-server 上下文长度 |

> 切分尺寸类配置的单位是**全宽字符当量**：CJK 字符计 1，拉丁/半角计 0.25，中英文在相同配置下语义体量对齐；英文硬切自动回退单词边界，句界识别内置英文缩写白名单。

镜像内覆盖：`EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1`、`EMBEDDING_MODEL=qwen3-embedding-0.6b`、`EMBEDDING_DIMENSION=1024`、`QDRANT_HOST=127.0.0.1`。

⚠️ 切换 Embedding 模型必须同步修改维度并重建集合；已存在集合维度不一致时启动直接失败。

## MCP 工具

| 工具 | 用途 |
| --- | --- |
| `rag_ingest(text, metadata)` | 新增文档：按策略切块写入 Qdrant 并归档原文，返回 `documentId`、`chunkCount`、`parentCount` |
| `rag_update(documentId, text, metadata)` | 更新已归档文档；`metadata` 不传时保留原元数据，并立即重建该文档向量 |
| `rag_delete(documentId)` | 删除文档的全部向量和原文归档 |
| `rag_list(limit, offset)` | 分页列出已归档文档的 id、元数据、切分签名和文本长度 |
| `rag_reindex(documentId)` | 手动重建：传 `documentId` 同步重建单篇；不传则提交异步任务重建全部归档文档，返回 `taskId` |
| `rag_rebuild(confirm)` | 全量重建：清空当前集合中本服务写入的片段后按归档异步重建，必须 `confirm=true`，返回 `taskId` |
| `rag_task_status(taskId)` | 查询重建任务进度；`taskId` 不传返回最近一次任务，从未执行过时返回 `{"status":"IDLE"}` |
| `rag_task_resume(taskId)` | 续接重启前未完成的重建任务（`status=interrupted`）：跳过已成功文档继续重建，不重新清空；`taskId` 不传表示最近一次任务 |
| `rag_task_cancel(taskId)` | 取消重建任务：运行中为协作式（当前文档处理完后停止）；`interrupted` 任务立即置为 `cancelled` 并释放任务锁 |
| `rag_sync_sources(force)` | 异步触发多目录 Markdown 扫描：按文件指纹增量解析、切分并建立索引；`force=true` 忽略指纹重新切分全部文件；立即返回，用 `rag_sync_status` 查询 |
| `rag_sync_status()` | 查询目录导入状态：是否启用/运行/排队、最近一次触发、扫描统计与错误 |
| `rag_search(query, topK, expand)` | 仅检索，返回命中分块、相似度、来源元数据；Parent-Child 下默认按父块去重返回，`expand=false` 返回原始子块 |
| `rag_query(query, topK)` | 返回带编号/来源/相似度的增强上下文（不调用生成模型） |

- `rag_update`：先更新归档再切分替换旧片段；`metadata` 不传时沿用归档中的元数据。
- `rag_reindex`：只重建已归档文档，不清理归档之外的残留片段；单篇重建仍同步返回片段数。
- `rag_rebuild`：先删 `chunk_type=chunk` / `chunk_type=child` / `chunk_type=parent` 的片段再全量重建；归档为空时拒绝执行。
- `rag_reindex`（全量）/`rag_rebuild` 为**异步任务**：同一时刻只允许一个重建任务，重复提交返回错误。任务快照（保留最近 20 个）与任务锁存放在 Qdrant 的独立集合 `<QDRANT_COLLECTION>_reindex_state`（与向量数据同一 Qdrant/同一持久化卷，不依赖应用容器文件系统；集合不影响检索、空库探活与 `rag_rebuild` 清理）。进程重启后恢复进度：未完成任务为 `interrupted` 并**继续持有任务锁**，默认由 `REINDEX_RESUME_ON_STARTUP=true` 自动续接（跳过已完成文档、不重复清空）；关闭自动续接时可用 `rag_task_resume` 手动续接，或用 `rag_task_cancel` 放弃并释放锁。优雅关机会把在跑任务保存为 `interrupted` 并保留锁；硬超时已撤销写权限的任务不会续接。
- `rag_task_status` 返回 `status`（`queued` / `clearing` / `reindexing` / `interrupted` / `completed` / `aborted` / `cancelled` / `failed`）、`total`、`processed`、`percent`、`succeeded`、`failed`、`currentDocumentId`、`lastProgressAt`、`stalled`、`cancelRequested`、`startedAt`、`finishedAt`、`aborted`、`error`、`revoked`、`lockReleaseAt`。
- 看门狗：超过 `REINDEX_STALL_TIMEOUT_SECONDS` 无进度只标记 `stalled` 并告警，**保留任务锁**；超过 `REINDEX_HARD_TIMEOUT_SECONDS` 才**撤销写权限**（此后拒绝一切 Qdrant 写入）、主动取消在途 Embedding 调用并中断工作线程。
- 锁释放：线程在 `REINDEX_JOIN_TIMEOUT_SECONDS` 内退出则立即释放；否则进入 `REINDEX_RECOVERY_GRACE_SECONDS` 宽限期，到期强制释放任务锁。撤销后旧任务无法再发起写入，宽限期只需覆盖撤销瞬间的在途请求（有 `QDRANT_REQUEST_TIMEOUT_MS` 上界），因此**无需重启**；此时提交新任务会提示“已撤销，正在释放任务锁”。
- `rag_task_cancel`：运行中为协作式取消，当前文档处理完后停止，最终 `status=cancelled`；卡在阻塞调用时依靠 Embedding 超时、主动取消或看门狗硬超时兜底。对 `interrupted` 任务则立即置为 `cancelled` 并释放任务锁（放弃续接）。
- 没有归档的历史数据无法 `rag_list` / `rag_update` / `rag_reindex`，只能按 `docId` 删除或重新入库。
- Qdrant 数据若被整体清空或更换集合名，重建进度与锁也会随之丢失：切分配置变化时 `ChunkingReindexRunner` 会按归档签名补建；但同一签名下的全量重建（`rag_rebuild`）中断后，剩余文档不会被自动补建，需要重新执行重建。

> ⚠️ 这些工具没有鉴权，能访问 `/mcp` 即可修改或清空知识库，**请勿直接暴露公网**；跨网访问请加反向代理与认证。检索结果是不可信数据，调用方不得执行其中的指令。

## 客户端接入

服务端为标准 Streamable HTTP MCP Server，URL `http://localhost:18080/mcp`。

**OpenCode**（`opencode.json`）：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "small-rag": { "type": "remote", "url": "http://localhost:18080/mcp", "enabled": true }
  }
}
```

**Claude Code**：`claude mcp add --transport http small-rag http://localhost:18080/mcp`

| 客户端 | 配置要点 |
| --- | --- |
| Cursor | `.cursor/mcp.json` → `{"mcpServers":{"small-rag":{"url":"http://localhost:18080/mcp"}}}` |
| VS Code / Copilot | `.vscode/mcp.json` → `{"servers":{"small-rag":{"type":"http","url":"http://localhost:18080/mcp"}}}` |
| Cline / Roo Code | `type: streamableHttp`，`url` 同上 |
| Windsurf | `serverUrl` 同上 |
| Claude Desktop | 仅支持 stdio：`npx -y mcp-remote http://localhost:18080/mcp` |
| Spring AI 客户端 | `spring-ai-starter-mcp-client` + `streamable-http` 连接 `http://localhost:18080` |

调试：`npx @modelcontextprotocol/inspector`，选择 Streamable HTTP 并填入上述 URL。

## 切分策略

| 策略 | 配置值 | 行为 | 适用场景 |
| --- | --- | --- | --- |
| 固定长度 | `fixed` | 按 `chunk-size` / `chunk-overlap` 滑窗硬切 | 无结构长文本 |
| 递归字符 | `recursive` | 按段落 → 换行 → 句号 → 逗号 → 空格逐级切分，仍超长才硬切 | 普通纯文本、FAQ |
| 语义 | `semantic` | Markdown 标题分节 + 节内文本块与累积质心比较合并/断开；代码/表格等受保护块独立成块；超长文本块回落句子级切分；尾块不足 `min-chunk-chars` 时回收；可配 `breakpoint-percentile` 用分位数断点替代绝对阈值；入库多一次 Embedding（按文本块，比句级省） | 主题切换明显、愿意为切分质量付 Embedding 成本 |
| Parent-Child | `parent-child`（默认） | 父块/子块都由 Markdown 引擎切分；子块写入向量库，命中后按 `parent_id` 去重展开父块上下文；非标题起始片段正文前置 `heading_path` | 精确召回 + 完整上下文 |

> `fixed` / `recursive` 不感知 Markdown 结构；`semantic` 与 `parent-child` 内部共用 Markdown 结构切分引擎（原 `markdown` 独立策略已并入）。已设置 `CHUNK_STRATEGY=markdown` 的部署请改用 `parent-child`。

```yaml
rag:
  chunking:
    strategy: parent-child      # fixed | recursive | semantic | parent-child
    markdown:
      heading-separator: " > "  # 生成 heading_path，例如 "指南 > 安装 > Linux"
    recursive:
      separators: ["\n\n", "\n", "。", "！", "？", "；", "，", " "]
    semantic:
      similarity-threshold: 0.75   # 文本块向量与当前块质心的余弦相似度阈值
      breakpoint-percentile: 0     # > 0 时用相邻文本块相似度分位数替代绝对阈值
      min-chunk-chars: 200
      max-chunk-chars: 0
    parent-child:
      parent-size: 2000
      parent-overlap: 0
      child-size: 400
      child-overlap: 50
```

Markdown 感知切分引擎（`semantic` 与 `parent-child` 共用）的行为：

- 按 `#` / Setext 标题分节，`heading_path` 记录完整标题层级；检索结果可直接看到片段所属章节。
- 非标题行开头的片段正文前置 `heading_path`，让向量模型在嵌入时感知章节层级；此类片段长度可能超出 max-chars 一个标题路径的长度。
- fenced code（``` / ~~~）、缩进代码、表格、引用块和 Obsidian callout 作为受保护块，正常大小下不会被从中间切断；代码块内的 `#` 注释不会误判为标题。
- 超长表格按行切分时每个切片重复表头；超长 fenced code 切片补回围栏（含语言标注）；超长数学块补回 `$$`。
- 解析 YAML frontmatter，`title` / `tags` / `aliases` / `description` 写入 `frontmatter_*` 元数据；frontmatter 不进入正文。
- 提取 Obsidian `[[wikilink]]` 目标到 `wikilinks` 元数据。
- 单块超过大小上限才回落：文本块用递归字符切分，代码/表格按行切分，标记 `oversized_block=true`。
- 所有片段带 `content_format=markdown` 与 `markdown_block_types`（例如 `heading,paragraph,code`）。
- `rag_query` 的上下文前缀附带 `heading=标题路径`，方便调用方 Agent 标注引用章节。

入库元数据记录 `chunk_strategy`、`chunk_type`；Parent-Child 子块额外含 `parent_id`、`parent_index`，父块作为独立 point（`chunk_type=parent`，id 由 `parent_id` 确定性生成）与子块同集合写入。不同策略的向量语义不同，**不要在同一集合混用**；已归档文档切换策略并重启后会自动重切，未归档的历史数据建议换 `QDRANT_COLLECTION` 或重新入库。

### 自动重切

启用 `rag.chunking.auto-rechunk`（默认 `true`）后，应用启动时会：

1. 计算当前切分配置签名（策略、块大小/重叠、分隔符、语义阈值/分位数、Parent-Child 尺寸、标题分隔符）；
2. 扫描 `SMALL_RAG_ARCHIVE_DIR` 中的原文归档；
3. 对签名不一致的文档按 `doc_id` 删除旧片段，用当前策略重新切分、Embedding 并写入 Qdrant；
4. 成功后更新归档签名；单篇失败只告警，下次启动重试，连续 3 篇失败中止本次任务。

修改 `CHUNK_STRATEGY` 或切分参数后，**重启即自动重切**。归档是重切的前提：容器内 `/data/documents`（`small_rag_data` 卷内），本地 `./data/documents`（已在 `.gitignore`）；归档目录不可写时应用仍可入库/检索，但自动重切不可用。`CHUNK_AUTO_RECHUNK=false` 可关闭。

签名同时包含目标 `QDRANT_COLLECTION`：切到新集合后启动会把归档文档重建到新集合；若新集合是独立知识库，请同时换用独立的 `SMALL_RAG_ARCHIVE_DIR`。

⚠️ 只对归档启用后入库的文档生效。启用前已存在于 Qdrant 的历史数据没有原文，无法重建；请重新 `rag_ingest` 或换新集合全量重建。

## 目录自动导入（多目录 Markdown）

配置 `rag.sources.directories` 后，服务会扫描一个或多个目录下的 Markdown 文件，自动解析、切分、Embedding 并归档；源文件变化时增量更新，不需要手工调用 `rag_ingest`。

```yaml
rag:
  sources:
    enabled: true
    # label 是稳定的文档 id 命名空间；不写 label 时默认取目录名
    directories: notes=/data/sources/notes,wiki=/data/sources/wiki
    extensions: .md,.markdown
    exclude: "**/node_modules/**,**/target/**,**/build/**"
    skip-hidden: true
    scan-on-startup: true
    scan-interval-seconds: 300      # 0 = 关闭定时扫描
    watch-enabled: true             # 文件系统 watcher（带防抖）
    watch-debounce-seconds: 5
    max-file-bytes: 2097152         # 单文件上限，0 = 不限
    max-files-per-scan: 0           # 每轮最多处理的变化文档数，0 = 不限
    delete-missing: false           # 源文件删除后是否清理索引
    delete-orphan-roots: false      # 根目录从配置移除后是否清理该 label 下的文档
```

容器示例：

```yaml
services:
  small-rag:
    volumes:
      - /host/notes:/data/sources/notes:ro
      - /host/wiki:/data/sources/wiki:ro
    environment:
      SMALL_RAG_SOURCES_ENABLED: "true"
      SMALL_RAG_SOURCE_DIRS: notes=/data/sources/notes,wiki=/data/sources/wiki
```

也可以直接用仓库提供的可选 override（把 `.env` 的 `SMALL_RAG_SOURCE_HOST` 挂到 `/data/sources`）：

```bash
make up-sources
# 等价于：
docker compose -f compose.yaml -f compose.sources.yaml up -d --build
```

**触发方式**

| 方式 | 说明 |
| --- | --- |
| 启动扫描 | `SMALL_RAG_SOURCES_SCAN_ON_STARTUP=true` 时，应用就绪后在后台扫描一次 |
| 定时扫描 | 每 `SMALL_RAG_SOURCES_SCAN_INTERVAL_SECONDS` 秒增量扫描；`0` 关闭 |
| 文件 watcher | `SMALL_RAG_SOURCES_WATCH_ENABLED=true` 时监听新增/修改/删除，按防抖时间合并为一次增量扫描 |
| 手动触发 | MCP `rag_sync_sources(force)` 异步触发；`force=true` 忽略指纹重新切分全部文件（且不受 `MAX_FILES_PER_SCAN` 限制）；用 `rag_sync_status` 轮询 |
| 阈值/限流 | `MAX_FILE_BYTES` 控制单文件上限；`MAX_FILES_PER_SCAN` 控制每轮 Embedding 数量 |

**增量与一致性**

- 文档 id 由“根目录 label + 相对路径”确定性生成；目录移动时保持 label 不变即可续用原文档 id。label 重复会拒绝扫描，请用 `label=/path` 显式命名。
- 先比较 `mtime + size`，再比较内容 SHA-256；只有内容变化才重新切分。仅改 mtime 不会触发重建。
- 增量状态默认写在 `<archive-dir>/.sources/state.json`（原子替换），缺失或损坏时从归档元数据重建；最坏情况是多一次幂等重建。
- 源文件删除后，只有 `DELETE_MISSING=true` 才删除对应向量与归档；删除只对“当前仍配置且本轮成功扫描”的根目录生效，避免临时不可读的挂载点误删。
- 目录导入与重建任务共用进程内索引活动锁：重建运行中会跳过/拒绝导入，导入运行中会拒绝新的重建任务。
- 导入文档的元数据包含 `source`、`source_root`、`source_root_path`、`source_file`、`source_path`、`source_size`、`source_mtime`、`source_sha256`；`source` 可直接用于引用。
- 归档另维护 `<archive-dir>/.archive/index.json` 摘要索引；`rag_list` 和启动重切只读摘要，按需逐篇读取原文，避免一次性把全部归档 JSON 载入内存。

**注意**

- 源文件是权威数据：对导入文档执行 `rag_update` 后，文件内容不变时下次扫描会保留手工更新；文件一旦变化，扫描会以文件内容覆盖。
- 手动 `rag_delete` 会留下墓碑：源文件内容不变时不会被自动重新导入；文件内容变化后墓碑失效并重新导入。要永久删除请删除源文件或取消该目录配置。
- 根目录 label 必须稳定；label 变化会生成新的文档 id。`DELETE_MISSING=true` 清理已消失文件；`DELETE_ORPHAN_ROOTS=true` 清理从配置中移除的 label 下全部文档（默认关闭，避免误删）。
- 仅支持 UTF-8 文本；超过 `MAX_FILE_BYTES` 的文件会被跳过并告警。默认不跟随符号链接、跳过隐藏目录/文件。
- `rag_sync_sources` 是异步调用，立即返回；用 `rag_sync_status` 查看 `running/queued/lastRun`。watcher 受平台 inotify 限额影响，失败时自动退化为启动/定时扫描。
- 容器内 bind mount 的源目录必须对 UID `10001` 可读（`chmod -R a+rX /path/to/notes` 或对应属主）。

## 设计要点

- **仅向量模型**：`spring.ai.model.chat=none`，不创建 ChatModel / ChatClient。
- **阈值与预算**：`rag.min-score=0.35`；单片段超长时以命中子块（`matched_text`）为中心做窗口截断，总上下文 12000 全宽字符当量，预算不足时跳过当前片段继续尝试装载后续更短片段。
- **父块独立存储**：父块作为独立 point 与子块同集合写入，子块不再复制父块正文（消除 payload N 倍放大）；检索命中后按 id 取回父块正文与向量，父块缺失时回退为子块原文。
- **MMR 多样性**：候选超出 topK 时按 `λ·score − (1−λ)·与已选结果的最大相似度` 迭代选取（`MMR_LAMBDA`，默认 0.7）；Parent-Child 复用父块存储向量，零额外 Embedding，其余策略对候选文本做一次批量 Embedding。
- **空库短路**：检索前用 Qdrant `count` 判断，避免无意义 Embedding 调用。
- **启动韧性**：Qdrant 不可用只告警（fail-soft）；集合维度不一致直接失败（fail-fast）。
- **自动重切**：原文归档 + 切分配置签名（签名版本与项目版本同步，当前 `0.0.2`），启动时检测到变化自动删旧片段重建。
- **Markdown 结构**：标题分节，代码/表格/callout 受保护，frontmatter、heading_path、wikilink 写入元数据；超长表格/代码切片重复表头或围栏。
- **中英对齐**：切分长度按全宽字符当量计量（CJK=1、拉丁=0.25），英文硬切回退单词边界，句界识别带缩写白名单；语义断点支持分位数模式。
- **重切时机**：启动时同步执行，失败不阻断启动，下次启动继续重试。
- **目录导入**：确定性文档 id（根 label + 相对路径）+ `mtime/size/SHA-256` 增量；状态文件与归档同卷，丢失时从归档元数据重建；启动/定时/watcher/MCP 异步触发，`max-files-per-scan` 限流。
- **归档摘要索引**：`.archive/index.json` 保存 metadata/签名/长度，`rag_list` 与启动重切只读摘要，原文按需 `find`。
- **重切写入顺序**：能枚举旧 point id 时先写新片段再删旧片段，失败时旧数据仍可检索；无法枚举时回退先删后写。
- **错误收敛**：MCP 工具把运行期异常转为 `{"error": "..."}`，不抛协议层异常。

## 架构

```
mcp/            RagMcpTools：rag_* 共 13 个工具（入库/检索/重建/任务查询/目录导入）
rag/            RagService（入库/检索/上下文/更新删除重建）、IndexActivityGuard（重建与目录导入互斥）、
                KnowledgeBaseProbe（空库短路）
rag/reindex/    ReindexTaskService（异步重建/看门狗/撤销与宽限期）、ReindexStateStore（任务快照与锁门面）、
                ReindexStateBackend、QdrantReindexStateBackend（Qdrant 状态集合）、
                ReindexProgress、ReindexListener、ReindexRevokedException、CancellationScope（OkHttp 主动取消）、
                ChunkingReindexRunner（启动自动重切）
rag/sources/    SourceSyncService（多目录 Markdown 扫描/增量/定时/手动触发/watcher）、
                SourceStateStore（文件指纹与手动删除墓碑）
rag/chunking/   ChunkingService、ChunkingStrategy、ChunkingSignature、TextChunk、
                TextChunker、RecursiveTextChunker、MarkdownChunker、SemanticTextChunker
rag/archive/    DocumentArchive、ArchivedDocument、ArchivedDocumentSummary（原文归档、摘要索引与签名记录）
config/         QdrantConfig、McpServerConfig、EmbeddingConfig（HTTP 超时）、RagProperties、QdrantProperties
docker/         start-qdrant.sh / start-llama.sh / start-app.sh / supervisord.conf
```

`VectorStore` 与 `EmbeddingModel` 均按 Spring AI 接口注入。

## 已知限制与风险

- **索引失败窗口**：单篇重建能枚举旧 point id 时先写新片段再删旧片段，Embedding/Qdrant 失败不会让文档不可检索；只有无法枚举 id 时才回退为先删后写。写入窗口内可能短暂返回新旧重复片段，删除旧片段失败会保留重复并由下次重切清理。
- **旧数据清理**：`rag_rebuild` 只按 `chunk_type=chunk/child/parent` 清理；更早版本写入、没有 `chunk_type` 的片段不会被清理，需换新集合。
- **归档索引**：`rag_list`/启动重切使用 `.archive/index.json` 摘要索引，不再加载全部原文；索引丢失、版本不符或文档 id 集合不一致时会全量重建一次。索引内容陈旧但 id 集合一致时可能保留旧签名，异常后建议手动重建索引/重切。
- **重启续接边界**：Qdrant 状态集合中的锁是单实例读后写，不是分布式 CAS；多副本部署不互斥。锁 point 丢失但任务快照仍在时，按单实例假设由最近一个未完成/未撤销任务重新持锁；多副本下可能误抢。
- **启动顺序**：`ChunkingReindexRunner` 在 `ApplicationReadyEvent` 之前同步执行；若检测到有重启时中断、待续接的重建任务，则跳过自动重切并交给续接任务处理，避免重复重建。
- **目录导入**：watcher 依赖平台 inotify/事件队列，超限或注册失败时会退化为启动/定时扫描；`rag_sync_sources` 已改为异步触发，用 `rag_sync_status` 轮询；root label 重复会拒绝扫描；label 变化会生成新文档 id，旧 label 需 `DELETE_ORPHAN_ROOTS=true` 才会清理；墓碑只在源文件内容不变时生效。
- **切分签名**：签名版本与项目版本同步（当前 `0.0.2`）；只改切分算法而不升版本时，旧索引不会被自动重切，需要手动 `rag_rebuild`。
- **导入状态**：最近一次扫描统计只在内存中，重启后 `rag_sync_status` 的 `lastRun` 为空；日志仍保留历史。

## 分支模型（Git Flow）

| 分支 | 用途 | 合并方向 |
|---|---|---|
| `main` | 生产，随时可构建发布；发布打 `vX.Y.Z` tag | ← `release/*` / `hotfix/*` |
| `develop` | 集成开发 | ← `feature/*` |
| `feature/*` | 单个功能 | → `develop`（squash） |
| `release/x.y.z` | 发布收敛，只修 bug | `develop` → `release/*` → `main` + tag → 回灌 `develop` |
| `hotfix/*` | 生产缺陷 | `main` → `hotfix/*` → `main` + tag → 回灌 `develop` |

- 不设 `stable` 长期分支；生产版本由 `main` 上的 tag 标识。
- `develop` 版本号用 `X.Y.Z-SNAPSHOT`，`main` 上的 tag 为正式版本。
- 生产镜像 tag 与 git tag 对齐（如 `small-rag:v0.0.2`），不要用 `latest` 上生产。
- `main`/`develop` 应开启分支保护：只能通过 PR 合并，且 CI 必须通过。

## v0.0.2 规划

v0.0.2 在保持**单容器 standalone 默认运行**的前提下，增加可选的分布式多副本能力（外部 Qdrant / Redis / Consul / Embedding、分布式任务锁、Spring Cloud 服务治理），并补齐 RAG 评测与集成测试。详见 [ROADMAP.md](ROADMAP.md)。

## 测试

```bash
mvn test
```

单测不依赖 Qdrant / 模型密钥；端到端需启动 Qdrant 并配置 Embedding 服务。

- 离线评测：`RagPipelineEvaluationTest` 用确定性哈希向量 + `SimpleVectorStore` 跑通“入库 → 切分 → 检索 → 文档级指标”，回归 recall@k / MRR / nDCG 与 MMR；不依赖外部服务，指标不代表真实语义模型效果。
- Redis 集成测试（默认跳过）：`mvn test -Dtest='*RedisIT' -Dsmallrag.redis.it=true`，需本机 Redis `localhost:6390`。
