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

wait_for "http://127.0.0.1:6333/readyz" "Qdrant" "${QDRANT_WAIT_RETRIES:-120}"

# 默认确保 Ollama 已拉取 EMBEDDING_MODEL；使用外部 Embedding 服务时设为 false。
if [ "${SMALL_RAG_ENSURE_OLLAMA_MODEL:-true}" = "true" ]; then
    : "${EMBEDDING_MODEL:?SMALL_RAG_ENSURE_OLLAMA_MODEL=true 时必须设置 EMBEDDING_MODEL}"
    wait_for "http://127.0.0.1:11434/api/tags" "Ollama" "${OLLAMA_WAIT_RETRIES:-120}"

    # 等价于 `ollama pull ${EMBEDDING_MODEL}`，stream=false 让请求在拉取完成后返回。
    echo "[small-rag] ensuring embedding model: ${EMBEDDING_MODEL}"
    response="$(curl -sS -X POST "http://127.0.0.1:11434/api/pull" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"${EMBEDDING_MODEL}\",\"stream\":false}")"
    case "${response}" in
        *'"error"'*)
            echo "[small-rag] failed to pull embedding model: ${response}" >&2
            # 交给 supervisor 重试，避免高频重试打爆日志与网络
            sleep 10
            exit 1
            ;;
    esac
    echo "[small-rag] embedding model ready"
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar
