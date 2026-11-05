#!/bin/sh
set -eu

# Embedding 推理服务（llama.cpp llama-server）。
# 模型与数据落在 /data，便于挂载持久化卷；服务只监听回环地址。

MODEL_PATH="${EMBEDDING_MODEL_PATH:-/data/models/embedding.gguf}"
MODEL_URL="${EMBEDDING_MODEL_URL:?EMBEDDING_MODEL_URL 必须设置}"

mkdir -p "$(dirname "${MODEL_PATH}")"

if [ ! -s "${MODEL_PATH}" ]; then
    echo "[small-rag] downloading embedding model: ${MODEL_URL}"
    # 断点续传：存在未完成的 .part 则从断点继续（首次下载失败后重启容器不重头再来）
    resume=""
    [ -f "${MODEL_PATH}.part" ] && resume="-C -"
    if ! curl -fL ${resume} --retry 5 --retry-delay 5 -o "${MODEL_PATH}.part" "${MODEL_URL}"; then
        echo "[small-rag] model download failed, will retry on next restart" >&2
        sleep 10
        exit 1
    fi
    mv "${MODEL_PATH}.part" "${MODEL_PATH}"
    echo "[small-rag] embedding model downloaded: ${MODEL_PATH}"
fi

# Qwen3-Embedding 是 last-token pooling 模型，必须显式 --pooling last
exec /opt/llama/llama-server \
    --host "${EMBEDDING_BIND_ADDR:-127.0.0.1}" \
    --port 11434 \
    --model "${MODEL_PATH}" \
    --embeddings \
    --pooling last \
    --ctx-size "${EMBEDDING_CTX_SIZE:-8192}" \
    --no-webui \
    ${EMBEDDING_THREADS:+--threads "${EMBEDDING_THREADS}"}
