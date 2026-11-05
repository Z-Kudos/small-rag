# AGENTS.md — small-rag

本项目：单模块 Spring Boot 服务 `small-rag`，通过 MCP（Streamable HTTP）暴露**纯文本** RAG 能力；向量库固定使用 **Qdrant**，不引入 Tika/OCR/图谱/混合检索。

## 版本基线（严格锁定）

| 组件 | 版本 |
|---|---|
| JDK | 25（`maven.compiler.release=25`） |
| Spring Boot | 4.2.0 |
| Spring Cloud | 2026.0.0（Paddington） |
| Spring AI | 2.0.2 |

- **解析现状（2026-11 验证）**：Boot 4.2.0 只有 RC1/RC2，Spring Cloud 2026.0.0 只有 RC1——GA 尚未发布。实际可解析组合：`4.2.0-RC1` + `2026.0.0-RC1`，且必须在 pom 或 settings.xml 加 `https://repo.spring.io/milestone`，并在 README 注明用了 RC。禁止降级到 Boot 3.x / Cloud 2025.x 来"修复"解析失败。
- Spring AI 2.0.2 是 Maven Central 稳定版，不需要 milestone 仓库。

## 依赖与构建

- parent 用 `spring-boot-starter-parent`；`spring-cloud-dependencies` 与 `spring-ai-bom` 均以 `import` 方式引入。
- Starter 清单：`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-model-openai`（**2.0.2 不存在 `spring-ai-starter-model-embedding`**，Embedding 由具体模型 starter 提供）、`spring-ai-starter-vector-store-qdrant`、`spring-boot-starter-web`、`org.projectlombok:lombok`（optional）。
- **不要**同时引入 STDIO 的 `spring-ai-starter-mcp-server` 和 webmvc starter——自动配置冲突。只用 webmvc + `protocol=STREAMABLE`。
- **JDK 23+ 默认不自动发现注解处理器**：必须设置 `<maven.compiler.proc>full</maven.compiler.proc>`，否则 Lombok 的 `@Slf4j`/getter 全部“找不到符号”。
- 验证命令：`mvn clean package -DskipTests`；单测用 `mvn test`。

## 容器化

- **单容器架构**：`Dockerfile` 最终镜像同时包含应用、Qdrant、Ollama，`supervisord` 作为 PID 1 管理三个进程；不要拆成 compose 多服务，也不要把依赖做成 sidecar。
- 只对外暴露 **18080**（应用）；Qdrant（6333/6334）与 Ollama（11434）必须绑定 `127.0.0.1`。
- 多阶段构建：`maven:3.9-eclipse-temurin-25` 构建应用 → 运行时基于 `qdrant/qdrant`，复制 Temurin JRE 与 Ollama 的 CPU 运行时（剔除 CUDA/ROCm）；基础镜像可用 `MAVEN_IMAGE` / `OLLAMA_IMAGE` / `QDRANT_IMAGE` / `JRE_IMAGE` 构建参数替换。
- 默认向量模型 `modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF`，维度 1024；`docker/start-app.sh` 在 `SMALL_RAG_ENSURE_OLLAMA_MODEL=true` 时等待 Ollama 并调用 `/api/pull`。
- 启动脚本：`docker/start-qdrant.sh`、`docker/start-ollama.sh`、`docker/start-app.sh`，由 `docker/supervisord.conf` 调度；数据统一放在 `/data`（Qdrant + Ollama 模型）。
- 新增容器配置时保持 `Dockerfile`、`compose.yaml`、`.env.example`、`README.md` 的端口与默认值一致；不要把 `.env` 或密钥提交到仓库。
- Podman 命令：`podman compose up -d --build` 或 `podman-compose up -d --build`；两者都读取同一份 `compose.yaml`。

## 架构分层

- `mcp` 包：`RagMcpTools` 暴露 `rag_query`、`rag_search`、`rag_ingest`。该类**不是** `@Component`，由 `McpServerConfig` 显式注册为 Bean。工具方法必须写 Javadoc——Spring AI 注解扫描把它作为工具描述发给客户端。
- `rag` 包：`RagService` 只做“分块入库 + 向量检索 + 上下文拼装”，**不调用 Chat/生成模型**；`TextChunker` 负责 800/100 定长滑窗分块；`KnowledgeBaseProbe` 负责空库短路。
- `config` 包：`QdrantConfig`（QdrantClient / QdrantVectorStore / 集合初始化 / 探活）、`McpServerConfig`、`RagProperties`、`QdrantProperties`。
- **只使用向量模型**：`spring.ai.model.chat=none`；不要新增 `ChatClient` / `ChatModel` 依赖或 Bean。`rag_query` 返回增强上下文，生成由调用方 Agent 完成。
- **Embedding 通过环境变量配置**：`EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSION`（分别回退 `OPENAI_*`）。新增配置时保持这一入口。
- **base-url 必须含 `/v1`**：Spring AI 2.x 使用官方 OpenAI SDK，请求路径是 `/embeddings`；默认 `https://api.openai.com/v1`，Ollama 用 `http://localhost:11434/v1`。
- 当前 starter 只支持 OpenAI 兼容的 HTTP Embedding 服务；**不支持 JVM 内 ONNX/transformers 本地模型**（需要另加 `spring-ai-starter-model-transformers`）。
- **Qdrant 关键约束**：
  - gRPC 端口是 `6334`（REST 才是 6333）。
  - `QdrantVectorStore` 要求 `Document` 的 id 是 **UUID**（内部 `UUID.fromString`）；不要把业务 id 设为 Document id，业务 id 放 metadata 的 `doc_id`。
  - `QdrantVectorStore.doSimilaritySearch` 会**先调用 Embedding 再查库**；空库必须先用 `KnowledgeBaseProbe`（Qdrant `count`）短路，否则无模型密钥时 `rag_query` 会抛异常而不是返回友好提示。
  - 集合初始化 fail-soft（Qdrant 不可用只告警、不阻断启动）；集合已存在但维度与 `rag.qdrant.dimension` 不一致时 fail-fast。
  - `rag.qdrant.dimension` 必须与 Embedding 模型输出维度一致（text-embedding-3-small = 1536）。
- `McpSyncServerCustomizer` 是单例扩展点且已被 WebMVC 传输的自动配置占用；**不要再定义自己的 customizer**，否则启动报 “required a single bean, but 2 were found”。instructions 等配置走 `spring.ai.mcp.server.*`。
- `application.yml` 必须：`spring.ai.mcp.server.protocol=STREAMABLE`、`name=small-rag`、`version=1.0.0`。MCP 端点默认 `/mcp`。

## 代码约束

- Lombok：DTO 用 `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor`，Service 用 `@RequiredArgsConstructor` 构造注入（禁止 `@Autowired` 字段注入），日志用 `@Slf4j`。
- **Spring Boot 4 已迁移 Jackson 3**：import 一律 `tools.jackson.*`，`com.fasterxml.jackson.*` 编译直接失败。
- 暂不开启 AOT/Native Image（JDK 25 AOT cache 留待后续）。

## Git 与文档

- `git init` 主分支 `main`；`.gitignore` 至少覆盖 `target/`、`.idea/`、`*.iml`、`.vscode/`、`*.log`、`.env`、`data/`。
- 提交信息用 Conventional Commits。首次提交固定为：
  `chore: bootstrap small-rag with JDK 25 + Spring Cloud 2026.0.0 + Spring AI 2.0.2`
- `README.md` 必须含：版本矩阵、启动命令（`mvn spring-boot:run`）、`/mcp` 端点说明、入库示例、RC/milestone 回退说明。

## 验收标准

1. `mvn clean package -DskipTests` 成功；`mvn test` 无外部依赖可跑（Mockito 覆盖空库短路/分块）。
2. 启动后 `/mcp` 可用，MCP Inspector 能列出 `rag_query`、`rag_search`、`rag_ingest`。
3. 空知识库下 `rag_query` 返回"未检索到相关内容"类提示，而不是异常堆栈（依赖 Qdrant count 短路）。
4. 端到端（入库→检索）需要 Qdrant + OpenAI 兼容的 Embedding 服务；不需要 Chat/生成模型。
5. README 步骤可复现，`git log` 至少一条 Conventional Commits。
