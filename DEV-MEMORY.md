# DEV-MEMORY.md — 开发记忆

> 每次开发同步更新：只记录当前有效的状态，过时内容直接覆盖/删除，不做历史归档（历史看 git log）。

## 项目状态（2026-11-05）

- 版本：`0.0.2`（pom.xml、application.yml MCP server version、README docker tag、ChunkingSignature 签名版本四处统一；签名版本与项目版本同步，但切分算法/存储布局变更需单独提升）。
- 构建：本机默认 JDK 21 编不了 release=25，用 `JAVA_HOME='D://java//jdk-27.0.1.0.3' mvn clean package`（JDK 27）；73 测试全绿。
- Dockerfile 无 `# syntax=` 指令（国内网络拉不到 docker.io 的 frontend 镜像；所用特性内置 BuildKit 全支持）；compose/.env 镜像 tag 随项目版本走 `APP_IMAGE_TAG`。
- 配置文件：`.env` 直接入库（由 .env.example 改名，仅模板值，.gitignore 已取消忽略；真实密钥禁止写入，.dockerignore 仍排除）。
- 容器迁移端到端验证通过（2026-11-05）：镜像构建 24 分钟（含首次 Maven 依赖下载与 llama.cpp 编译）；三进程（qdrant/llama-server/app）全部就绪；容器内 llama.cpp 与宿主机 Ollama 同文本向量余弦 = 1.0000（pooling last 正确）；MCP 全链路 rag_ingest→rag_query→rag_search(expand=false)→rag_delete 冒烟通过。**教训：docker/*.sh 工作区曾出现 CRLF 导致 exec ENOENT，已加 .gitattributes 强制 LF**。
- 容器推理运行时 = **llama.cpp**（0.0.2 起替换 Ollama）：以 qdrant 镜像为基底从源码编译 llama-server（`LLAMA_CPP_TAG=v0.9.0`），`start-llama.sh` 下载 GGUF（ModelScope，断点续传）并以 `--embeddings --pooling last` 启动（Qwen3-Embedding 必须 last pooling）；`SMALL_RAG_ENSURE_EMBEDDING_MODEL=true` 时 `start-app.sh` 等 `/health`。模型落 `/data/models/`。Ollama 仍可用于本机开发（README 本地运行节）。
- 手动校准工具：`src/test/java/.../calibration/BreakpointPercentileCalibration.java`，运行 `mvn test -Dtest=BreakpointPercentileCalibration -Dcalibrate=true`（需本机 OpenAI 兼容 Embedding 服务，`EMBEDDING_BASE_URL`/`EMBEDDING_MODEL`/`EMBEDDING_API_KEY` 可覆盖默认值）。

## 切分体系（当前有效）

- 策略 4 种：`fixed` / `recursive` / `semantic` / `parent-child`（默认）。`markdown` 不是策略——`MarkdownChunker` 是内部引擎，由 semantic 与 parent-child 共用。
- semantic = Markdown 分节 + 节内文本块与累积质心比较合并（`MarkdownChunker.chunkSemantic`）；代码/表格/数学块独立成块；超长文本块回落 `SemanticTextChunker` 句子级切分；尾块回收。
- 长度度量 = 全宽字符当量（`TextWeights`：CJK=1、拉丁=0.25）；英文硬切回退单词边界；句界英文缩写白名单。
- SEMANTIC `breakpoint-percentile`：真实语料校准结论（Qwen3-Embedding，2026-11-05）——技术文档语料上 0.1~0.4 输出完全一致，比绝对阈值 0.75 少约 5% 碎块（57 vs 60 块，p90 388 vs 312），参数鲁棒；技术文档推荐 `0.2`，默认仍为 0（绝对阈值）。混合主题语料上分位数模式优势更明显。
- Parent-Child **父块独立存储**（0.0.2 起）：父块作为独立 point（`chunk_type=parent`，id=`RagService.parentPointUuid(parentId)` 确定性 UUID）与子块同集合写入；子块只带 `parent_id`，**不再有 `parent_text`**；检索按 id 取回父块（payload `content` 字段 + 向量），父块缺失/取回失败回退子块原文。`rag_rebuild` 清理 chunk/child/parent 三类。
- 检索 MMR：`rag.mmr-lambda`（默认 0.7，=1 关闭）；候选 topK×4 → MMR（λ·score−(1−λ)·maxSim）→ topK；Parent-Child 复用父块存储向量（零额外模型调用），其余策略对候选文本批量 Embedding。
- MarkdownChunker 边界行为：围栏/缩进代码内的 `#` 不会误判为标题（回归测试锁定）；未闭合围栏吞到文末（符合规范）；段落后 `---` 按 Setext H2 解析（已知取舍）。
- 超长表格切片重复表头；超长 fenced code 切片补围栏；数学块补 `$$`。

## 检索与存储

- `rag_search` 可选 `expand`（默认 true，false 返回原始子块且不做 MMR）；`buildContext` 预算超限 continue 跳过；`maxChunkChars=1200` / `maxContextChars=12000` 按全宽字符当量计量。
- 包结构：`rag/`（RagService、KnowledgeBaseProbe、ChunkingReindexRunner）+ `rag/chunking/`（8 类）+ `rag/archive/`；payload 正文键 `RagService.CONTENT_FIELD="content"`（QdrantConfig 同步引用）。

## 待办（未做）

- 真实语料端到端验证 MMR 检索效果（需启动 Qdrant + 入库实际知识库后人工评估）。
- 混合主题语料校准 `SEMANTIC_BREAKPOINT_PERCENTILE`（当前校准语料为同质技术文档，分布扁平）。
