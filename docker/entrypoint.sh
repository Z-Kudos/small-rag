#!/bin/sh
set -eu

echo "[small-rag] starting all-in-one runtime"

RUNTIME_CONF=/run/supervisord-runtime.conf
cp /app/supervisord-all-in-one.conf "$RUNTIME_CONF"

# 远程 Qdrant / SQLite 后端：关闭本地 Qdrant 进程（按需保留镜像中的二进制）
if [ "${QDRANT_ENABLED:-true}" != "true" ]; then
    echo "[small-rag] QDRANT_ENABLED=false，本地 Qdrant 进程不启动"
    sed -i '/^\[program:qdrant\]/,/^$/ s/^autostart=true/autostart=false/' "$RUNTIME_CONF"
fi

# 远程 Embedding：关闭本地 llama.cpp 进程
if [ "${LLAMA_ENABLED:-true}" != "true" ]; then
    echo "[small-rag] LLAMA_ENABLED=false，本地 llama.cpp 进程不启动"
    sed -i '/^\[program:embedding\]/,/^$/ s/^autostart=true/autostart=false/' "$RUNTIME_CONF"
fi

exec /usr/bin/supervisord -c "$RUNTIME_CONF" -n
