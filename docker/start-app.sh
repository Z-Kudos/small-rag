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

if [ "${SMALL_RAG_RUNTIME:-all-in-one}" = "all-in-one" ]; then
    wait_for "http://127.0.0.1:6333/readyz" "Qdrant" "${QDRANT_WAIT_RETRIES:-120}"
fi

# 默认等待 Embedding 服务就绪（模型下载与加载由 start-llama.sh 负责）；
# 使用外部 Embedding 服务时设为 false。
if [ "${SMALL_RAG_ENSURE_EMBEDDING_MODEL:-true}" = "true" ]; then
    # 首次启动需要下载模型（约 640 MB），默认最多等 15 分钟
    wait_for "http://127.0.0.1:11434/health" "embedding server" "${EMBEDDING_WAIT_RETRIES:-900}"
    echo "[small-rag] embedding model ready"
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar
