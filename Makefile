# Docker: make up
# Podman: make COMPOSE="podman compose" up
COMPOSE ?= docker compose
SERVICE ?= small-rag

.PHONY: build build-distributed up up-sources up-distributed down down-distributed logs ps pull-model

# GGUF 模型下载（与容器启动时 start-llama.sh 的逻辑一致，支持断点续传）
MODEL_URL ?= https://modelscope.cn/models/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/master/Qwen3-Embedding-0.6B-Q8_0.gguf

build:
	$(COMPOSE) build

# 构建 distributed 全部镜像（small-rag / gateway / config-server）
build-distributed:
	$(COMPOSE) -f compose.yaml -f compose.distributed.yaml build

up:
	$(COMPOSE) up -d --build

# 带宿主机 Markdown 目录挂载（compose.sources.yaml）
up-sources:
	$(COMPOSE) -f compose.yaml -f compose.sources.yaml up -d --build

# 分布式 overlay：外部 Qdrant / Redis / Consul / Embedding + gateway，2 个应用副本
up-distributed:
	$(COMPOSE) -f compose.yaml -f compose.distributed.yaml up -d --build --scale small-rag=2

down-distributed:
	$(COMPOSE) -f compose.yaml -f compose.distributed.yaml down

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f $(SERVICE)

ps:
	$(COMPOSE) ps

# 手动预拉模型（容器内执行；启动时 start-llama.sh 也会自动检查）
pull-model:
	$(COMPOSE) exec $(SERVICE) sh -c 'curl -fL --retry 5 -C - -o "$${EMBEDDING_MODEL_PATH}.part" "$${EMBEDDING_MODEL_URL}" && mv "$${EMBEDDING_MODEL_PATH}.part" "$${EMBEDDING_MODEL_PATH}" && echo "model ready: $${EMBEDDING_MODEL_PATH}"'
