#!/bin/sh
set -eu

# 等待依赖进程就绪后再启动 Spring Boot，避免集合初始化落空。
wait_for() {
    url="$1"
    name="$2"
    attempts="${3:-120}"
    attempt=0
    echo "[small-rag] waiting for ${name} at ${url}"
    until curl -fsS "${url}" >/dev/null 2>&1; do
        attempt=$((attempt + 1))
        if [ "${attempt}" -ge "${attempts}" ]; then
            echo "[small-rag] ${name} did not become ready in time" >&2
            return 1
        fi
        sleep 1
    done
}

# 本地 Qdrant 进程关闭时（远程 Qdrant 或 SQLite 后端），跳过本地 readyz 等待
if [ "${QDRANT_ENABLED:-true}" = "true" ]; then
    wait_for "http://127.0.0.1:6333/readyz" "Qdrant" "${QDRANT_WAIT_RETRIES:-120}"
else
    echo "[small-rag] QDRANT_ENABLED=false，跳过本地 Qdrant 等待（${VECTOR_STORE_TYPE:-qdrant} 后端）"
fi

# 本地 llama.cpp 关闭时，跳过 embedding health 等待（远程 Embedding 由外部就绪性决定）
if [ "${LLAMA_ENABLED:-true}" != "true" ]; then
    echo "[small-rag] LLAMA_ENABLED=false，跳过本地 Embedding 等待（远程 Embedding 就绪性不在本容器内探测）"
elif [ "${SMALL_RAG_ENSURE_EMBEDDING_MODEL:-true}" = "true" ]; then
    # 首次启动需要下载模型（约 640 MB），默认最多等 15 分钟
    wait_for "http://127.0.0.1:11434/health" "embedding server" "${EMBEDDING_WAIT_RETRIES:-900}"
    echo "[small-rag] embedding model ready"
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar
