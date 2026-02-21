MVN ?= ./mvnw
BACKEND_IMAGE_GROUP ?= nherbaut
BACKEND_IMAGE_NAME ?= ddd-lab
BACKEND_IMAGE_TAG ?= latest
BACKEND_IMAGE_REF := $(BACKEND_IMAGE_GROUP)/$(BACKEND_IMAGE_NAME):$(BACKEND_IMAGE_TAG)
COMPOSE ?= docker compose
COMPOSE_FILE ?= docker-compose.yml

.PHONY: help build-backend-image push-backend-image publish-all up upup down pull logs ps

help:
	@echo "Targets:"
	@echo "  make build-backend-image  Build JVM backend container image ($(BACKEND_IMAGE_REF))"
	@echo "  make push-backend-image   Build and push JVM backend container image ($(BACKEND_IMAGE_REF))"
	@echo "  make publish-all          Push workers + backend images"
	@echo "  make up                   Run full stack with $(COMPOSE_FILE)"
	@echo "  make down                 Stop full stack"
	@echo "  make pull                 Pull full stack images"
	@echo "  make logs                 Follow backend logs"
	@echo "  make ps                   Show stack status"

build-backend-image:
	$(MVN) -DskipTests clean package
	docker build -f src/main/docker/Dockerfile.jvm -t $(BACKEND_IMAGE_REF) .

push-backend-image:
	$(MAKE) build-backend-image
	docker push $(BACKEND_IMAGE_REF)

publish-all:
	$(MAKE) -C workers push-all
	$(MAKE) push-backend-image

up: build-backend-image
	BACKEND_IMAGE=$(BACKEND_IMAGE_REF) $(COMPOSE) -f $(COMPOSE_FILE) up -d --remove-orphans

upup: up

down:
	$(COMPOSE) -f $(COMPOSE_FILE) down

pull:
	BACKEND_IMAGE=$(BACKEND_IMAGE_REF) $(COMPOSE) -f $(COMPOSE_FILE) pull

logs:
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f ddd-backend

ps:
	$(COMPOSE) -f $(COMPOSE_FILE) ps
