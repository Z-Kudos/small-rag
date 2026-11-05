#!/bin/sh
set -eu

# 仅监听回环地址，避免 Qdrant 被容器外访问；对外只暴露应用的 18080。
export QDRANT__SERVICE__HOST="${QDRANT_BIND_HOST:-127.0.0.1}"
export QDRANT__SERVICE__HTTP_PORT="${QDRANT_HTTP_PORT:-6333}"
export QDRANT__SERVICE__GRPC_PORT="${QDRANT_GRPC_PORT:-6334}"
export QDRANT__STORAGE__STORAGE_PATH="${QDRANT_STORAGE_PATH:-/data/qdrant/storage}"
export QDRANT__TELEMETRY_DISABLED=true

if [ -n "${QDRANT_API_KEY:-}" ]; then
    export QDRANT__SERVICE__API_KEY="${QDRANT_API_KEY}"
fi

exec /qdrant/qdrant --config-path /qdrant/config/config.yaml
