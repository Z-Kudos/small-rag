# Docker: make up
# Podman: make COMPOSE="podman compose" up
COMPOSE ?= docker compose
SERVICE ?= small-rag
MODEL ?= modelscope.cn/Qwen/Qwen3-Embedding-0.6B-GGUF

.PHONY: build up down logs ps pull-model

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

# 手动预拉模型，等价于容器启动时的自动检查
pull-model:
	$(COMPOSE) exec $(SERVICE) ollama pull $(MODEL)
