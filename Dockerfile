# syntax=docker/dockerfile:1

# 单容器镜像：small-rag 应用 + Qdrant + Ollama 运行在同一个容器内。
# 仅暴露 18080（MCP/HTTP）；Qdrant（6333/6334）与 Ollama（11434）只绑定 127.0.0.1。
#
# 基础镜像可通过 --build-arg 覆盖为国内镜像，例如：
#   docker build \
#     --build-arg MAVEN_IMAGE=docker.1ms.run/library/maven:3.9-eclipse-temurin-25 \
#     --build-arg OLLAMA_IMAGE=docker.1ms.run/ollama/ollama:latest \
#     --build-arg QDRANT_IMAGE=docker.1ms.run/qdrant/qdrant:latest \
#     --build-arg JRE_IMAGE=docker.1ms.run/library/eclipse-temurin:25-jre \
#     -t small-rag:1.0.0 .

ARG MAVEN_IMAGE=docker.io/library/maven:3.9-eclipse-temurin-25
ARG OLLAMA_IMAGE=docker.io/ollama/ollama:latest
ARG QDRANT_IMAGE=docker.io/qdrant/qdrant:latest
ARG JRE_IMAGE=docker.io/library/eclipse-temurin:25-jre

# ---------- 构建应用 ----------
FROM ${MAVEN_IMAGE} AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
RUN mvn -B -q -DskipTests clean package

# ---------- 提取 Ollama CPU 运行时 ----------
FROM ${OLLAMA_IMAGE} AS ollama

# 官方镜像包含 CUDA / ROCm 运行库（约 4.3 GB）；本镜像只需要 CPU 推理。
RUN rm -rf \
    /usr/lib/ollama/cuda_v12 \
    /usr/lib/ollama/cuda_v13 \
    /usr/lib/ollama/mlx_cuda_v13 \
    /usr/lib/ollama/vulkan \
    /usr/lib/ollama/include

FROM ${JRE_IMAGE} AS jre

# ---------- 单容器运行时 ----------
# 以 Qdrant 官方镜像为基础：自带 qdrant 二进制、配置与 Debian 运行时。
FROM ${QDRANT_IMAGE}

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=ollama /usr/bin/ollama /usr/bin/ollama
COPY --from=ollama /usr/lib/ollama /usr/lib/ollama

# curl 用于进程就绪探测与模型拉取，supervisor 负责管理三个进程
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl supervisor ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /app app \
    && mkdir -p /data/qdrant/storage /data/ollama/models /data/ollama/home /app \
    && chown -R app:app /data /app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/target/small-rag-*.jar /app/app.jar
COPY --chown=app:app docker/start-qdrant.sh /app/start-qdrant.sh
COPY --chown=app:app docker/start-ollama.sh /app/start-ollama.sh
COPY --chown=app:app docker/start-app.sh /app/start-app.sh
COPY docker/supervisord.conf /etc/supervisor/conf.d/small-rag.conf
RUN chmod 0755 /app/start-qdrant.sh /app/start-ollama.sh /app/start-app.sh

# 应用只对外暴露 18080
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    EMBEDDING_BASE_URL="http://127.0.0.1:11434/v1" \
    EMBEDDING_API_KEY="ollama" \
    EMBEDDING_MODEL="modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF" \
    EMBEDDING_DIMENSION="1024" \
    QDRANT_HOST="127.0.0.1" \
    QDRANT_PORT="6334" \
    QDRANT_COLLECTION="small_rag_text" \
    QDRANT_USE_TLS="false" \
    QDRANT_STORAGE_PATH="/data/qdrant/storage" \
    OLLAMA_HOST="127.0.0.1:11434" \
    OLLAMA_MODELS="/data/ollama/models" \
    OLLAMA_LIBRARY_PATH="/usr/lib/ollama" \
    OLLAMA_KEEP_ALIVE="-1" \
    SMALL_RAG_ENSURE_OLLAMA_MODEL="true" \
    CHUNK_STRATEGY="fixed" \
    CHUNK_SIZE="800" \
    CHUNK_OVERLAP="100" \
    CHUNK_AUTO_RECHUNK="true" \
    SMALL_RAG_ARCHIVE_DIR="/data/documents" \
    SEMANTIC_SIMILARITY_THRESHOLD="0.75" \
    PARENT_CHUNK_SIZE="2000" \
    CHILD_CHUNK_SIZE="400"

USER root

EXPOSE 18080

HEALTHCHECK --interval=30s --timeout=3s --start-period=300s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/18080' || exit 1

ENTRYPOINT ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf", "-n"]
