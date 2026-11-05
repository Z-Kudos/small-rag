#!/bin/sh
set -eu

# 仅监听回环地址；模型与缓存落在 /data，便于挂载持久化卷。
export OLLAMA_HOST="${OLLAMA_BIND_ADDR:-127.0.0.1:11434}"
export OLLAMA_MODELS="${OLLAMA_MODELS:-/data/ollama/models}"
export OLLAMA_LIBRARY_PATH="${OLLAMA_LIBRARY_PATH:-/usr/lib/ollama}"
# supervisor 以 root 启动子进程，HOME 默认是 /root；改到 app 可写目录
export HOME="${OLLAMA_HOME:-/data/ollama/home}"

exec ollama serve
