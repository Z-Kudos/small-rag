# 单容器镜像：small-rag 应用 + Qdrant + llama.cpp（Embedding 推理）运行在同一个容器内。
# 仅暴露 18080（MCP/HTTP）；Qdrant（6333/6334）与 Embedding 服务（11434）只绑定 127.0.0.1。

ARG MAVEN_IMAGE=docker.io/library/maven:3.9-eclipse-temurin-25
ARG QDRANT_IMAGE=docker.io/qdrant/qdrant:latest
ARG JRE_IMAGE=docker.io/library/eclipse-temurin:25-jre
# llama.cpp 源码版本（GitHub tag）；源码包经 gh-proxy 加速下载
ARG LLAMA_CPP_TAG=v0.9.0

# ---------- 构建应用 ----------
FROM ${MAVEN_IMAGE} AS build

WORKDIR /workspace

COPY pom.xml ./
# 容器内构建统一走阿里云镜像（Spring RC 构件仍从 repo.spring.io 回退）
COPY docker/maven-settings.xml ./maven-settings.xml
COPY src ./src
RUN mvn -B -q -s /workspace/maven-settings.xml -DskipTests clean package

# ---------- 编译 llama.cpp ----------
# 编译基底 = 运行基底（qdrant 镜像 = debian:13-slim），产物无 glibc 兼容问题；
# 相比 Ollama 官方镜像（3.25GB，CUDA/ROCm 占大头）只需下载约 40MB 源码包。
FROM ${QDRANT_IMAGE} AS llama-build
ARG LLAMA_CPP_TAG

# apt 走清华镜像（构建机在国内网络）；deb822 源的 URI 本身带 /debian 路径，只替换域名
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/*.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

RUN set -eu; \
    url="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz"; \
    curl -fL --retry 3 -o /tmp/llama-src.tar.gz "https://gh-proxy.com/${url}" \
      || curl -fL --retry 3 -o /tmp/llama-src.tar.gz "${url}"; \
    mkdir -p /src && tar -xzf /tmp/llama-src.tar.gz -C /src --strip-components=1

# 只构建 llama-server（Embedding 推理服务）；不需要 libcurl 下载能力（模型由启动脚本提供）
RUN cmake -S /src -B /build \
      -DCMAKE_BUILD_TYPE=Release \
      -DGGML_NATIVE=ON \
      -DLLAMA_CURL=OFF \
      -DLLAMA_BUILD_EXAMPLES=OFF \
      -DLLAMA_BUILD_TESTS=OFF \
 && cmake --build /build --target llama-server -j"$(nproc)"

# ---------- JRE ----------
FROM ${JRE_IMAGE} AS jre

# ---------- 单容器运行时 ----------
# 以 Qdrant 官方镜像为基础（debian:13-slim）：自带 qdrant 二进制、配置与 Debian 运行时。
FROM ${QDRANT_IMAGE}

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=llama-build /build/bin/ /opt/llama/

# curl 用于进程就绪探测与模型下载，supervisor 负责管理三个进程
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl supervisor ca-certificates libstdc++6 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /app app \
    && mkdir -p /data/qdrant/storage /data/models /app \
    && chown -R app:app /data /app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/target/small-rag-*.jar /app/app.jar
COPY --chown=app:app docker/start-qdrant.sh /app/start-qdrant.sh
COPY --chown=app:app docker/start-llama.sh /app/start-llama.sh
COPY --chown=app:app docker/start-app.sh /app/start-app.sh
COPY --chown=app:app docker/supervisord.conf /app/supervisord-all-in-one.conf
COPY --chown=app:app docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 0755 /app/start-qdrant.sh /app/start-llama.sh /app/start-app.sh /app/entrypoint.sh

# 应用只对外暴露 18080
ENV JAVA_HOME=/opt/java/openjdk \
    PATH="/opt/java/openjdk/bin:/opt/llama:$PATH" \
    LD_LIBRARY_PATH="/opt/llama" \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError" \
    EMBEDDING_BASE_URL="http://127.0.0.1:11434/v1" \
    EMBEDDING_API_KEY="local" \
    EMBEDDING_MODEL="qwen3-embedding-0.6b" \
    EMBEDDING_MODEL_URL="https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/master/Qwen3-Embedding-0.6B-Q8_0.gguf" \
    EMBEDDING_MODEL_PATH="/data/models/qwen3-embedding-0.6b-q8_0.gguf" \
    EMBEDDING_CTX_SIZE="8192" \
    SMALL_RAG_ENSURE_EMBEDDING_MODEL="true" \
    QDRANT_HOST="127.0.0.1" \
    QDRANT_PORT="6334" \
    QDRANT_COLLECTION="small_rag_text" \
    QDRANT_USE_TLS="false" \
    # 部署形态：VECTOR_STORE_TYPE=sqlite 切换到内嵌单文件向量库（<5 万片段）；
    # QDRANT_ENABLED / LLAMA_ENABLED=false 时不启动本地 Qdrant / llama.cpp 进程
    VECTOR_STORE_TYPE="qdrant" \
    QDRANT_ENABLED="true" \
    LLAMA_ENABLED="true" \
    SQLITE_PATH="/data/smallrag.sqlite" \
    QDRANT_STORAGE_PATH="/data/qdrant/storage" \
    CHUNK_STRATEGY="parent-child" \
    CHUNK_SIZE="800" \
    CHUNK_OVERLAP="100" \
    CHUNK_AUTO_RECHUNK="true" \
    SMALL_RAG_ARCHIVE_DIR="/data/documents" \
    SMALL_RAG_SOURCES_ENABLED="false" \
    SEMANTIC_SIMILARITY_THRESHOLD="0.75" \
    PARENT_CHUNK_SIZE="2000" \
    CHILD_CHUNK_SIZE="400"

USER root

EXPOSE 18080

HEALTHCHECK --interval=30s --timeout=3s --start-period=300s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/18080' || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
