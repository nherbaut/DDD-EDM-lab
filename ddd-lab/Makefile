MVN ?= ./mvnw
BACKEND_IMAGE_GROUP ?= nherbaut
BACKEND_IMAGE_NAME ?= ddd-lab
BACKEND_IMAGE_TAG ?= latest
BACKEND_IMAGE_REF := $(BACKEND_IMAGE_GROUP)/$(BACKEND_IMAGE_NAME):$(BACKEND_IMAGE_TAG)
ARCH ?= $(shell uname -m | sed -e 's/x86_64/amd64/' -e 's/aarch64/arm64/')
BACKEND_ARCH_TAG := $(BACKEND_IMAGE_TAG)-$(ARCH)
BACKEND_ARCH_IMAGE_REF := $(BACKEND_IMAGE_GROUP)/$(BACKEND_IMAGE_NAME):$(BACKEND_ARCH_TAG)
WORKER_CLOUD_REMOTE ?= nherbaut/ddd-worker-classifier-cloud
WORKER_GENERAL_REMOTE ?= nherbaut/ddd-worker-classifier-general
WORKER_CLOUD_ARCH_REF := $(WORKER_CLOUD_REMOTE):$(BACKEND_ARCH_TAG)
WORKER_GENERAL_ARCH_REF := $(WORKER_GENERAL_REMOTE):$(BACKEND_ARCH_TAG)
COMPOSE ?= docker compose
COMPOSE_FILE ?= docker-compose.yml

.PHONY: help build-backend-image build-all push-backend-image push-backend-arch push-workers-arch push-all-arch manifest-backend-latest manifest-workers-latest manifest-latest publish-all up upup down pull logs ps

help:
	@echo "Targets:"
	@echo "  make build-backend-image  Build JVM backend container image ($(BACKEND_IMAGE_REF))"
	@echo "  make build-all            Build backend + both worker images"
	@echo "  make push-backend-image   Build and push JVM backend container image ($(BACKEND_IMAGE_REF))"
	@echo "  make push-all-arch        Push arch-scoped tags (ARCH=$(ARCH)) for backend + workers"
	@echo "  make manifest-latest      Publish multi-arch :latest manifests from amd64+arm64 tags"
	@echo "  make publish-all          Push workers + backend images"
	@echo "  make up                   Run full stack with $(COMPOSE_FILE)"
	@echo "  make down                 Stop full stack"
	@echo "  make pull                 Pull full stack images"
	@echo "  make logs                 Follow backend logs"
	@echo "  make ps                   Show stack status"

build-backend-image:
	$(MVN) -DskipTests clean package
	docker build -f src/main/docker/Dockerfile.jvm -t $(BACKEND_IMAGE_REF) .

build-all: build-backend-image
	$(MAKE) -C ../workers build-all

push-backend-image:
	$(MAKE) build-backend-image
	docker push $(BACKEND_IMAGE_REF)

push-backend-arch: build-backend-image
	docker tag $(BACKEND_IMAGE_REF) $(BACKEND_ARCH_IMAGE_REF)
	docker push $(BACKEND_ARCH_IMAGE_REF)

push-workers-arch:
	$(MAKE) -C ../workers build-all
	docker tag cloud-classification:latest $(WORKER_CLOUD_ARCH_REF)
	docker tag general-classification:latest $(WORKER_GENERAL_ARCH_REF)
	docker push $(WORKER_CLOUD_ARCH_REF)
	docker push $(WORKER_GENERAL_ARCH_REF)

push-all-arch: push-backend-arch push-workers-arch

manifest-backend-latest:
	-docker manifest rm $(BACKEND_IMAGE_REF)
	docker manifest create $(BACKEND_IMAGE_REF) \
		$(BACKEND_IMAGE_GROUP)/$(BACKEND_IMAGE_NAME):$(BACKEND_IMAGE_TAG)-amd64 \
		$(BACKEND_IMAGE_GROUP)/$(BACKEND_IMAGE_NAME):$(BACKEND_IMAGE_TAG)-arm64
	docker manifest push $(BACKEND_IMAGE_REF)

manifest-workers-latest:
	-docker manifest rm $(WORKER_CLOUD_REMOTE):latest
	-docker manifest rm $(WORKER_GENERAL_REMOTE):latest
	docker manifest create $(WORKER_CLOUD_REMOTE):latest \
		$(WORKER_CLOUD_REMOTE):latest-amd64 \
		$(WORKER_CLOUD_REMOTE):latest-arm64
	docker manifest create $(WORKER_GENERAL_REMOTE):latest \
		$(WORKER_GENERAL_REMOTE):latest-amd64 \
		$(WORKER_GENERAL_REMOTE):latest-arm64
	docker manifest push $(WORKER_CLOUD_REMOTE):latest
	docker manifest push $(WORKER_GENERAL_REMOTE):latest

manifest-latest: manifest-backend-latest manifest-workers-latest

publish-all:
	$(MAKE) -C ../workers push-all
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
