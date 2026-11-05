# Docker: make up
# Podman: make COMPOSE="podman compose" up
COMPOSE ?= docker compose
SERVICE ?= small-rag

.PHONY: build up down logs ps pull-model

# GGUF 模型下载（与容器启动时 start-llama.sh 的逻辑一致，支持断点续传）
MODEL_URL ?= https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/master/Qwen3-Embedding-0.6B-Q8_0.gguf

build:
	$(COMPOSE) build

up:
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f $(SERVICE)

ps:
	$(COMPOSE) ps

# 手动预拉模型（容器内执行；启动时 start-llama.sh 也会自动检查）
pull-model:
	$(COMPOSE) exec $(SERVICE) sh -c 'curl -fL --retry 5 -C - -o "$${EMBEDDING_MODEL_PATH}.part" "$${EMBEDDING_MODEL_URL}" && mv "$${EMBEDDING_MODEL_PATH}.part" "$${EMBEDDING_MODEL_PATH}" && echo "model ready: $${EMBEDDING_MODEL_PATH}"'
