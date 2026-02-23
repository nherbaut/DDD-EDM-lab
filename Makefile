SUBDIR ?= cloud-catcher

CLOUD_CATCHER_DIR ?= cloud-catcher
CLOUD_ACCOUNTING_DIR ?= cloud-accounting
WORKERS_DIR ?= workers

ACCOUNTING_IMAGE_GROUP ?= nherbaut
ACCOUNTING_IMAGE_NAME ?= cloud-accounting
ACCOUNTING_IMAGE_TAG ?= latest
ACCOUNTING_IMAGE_REF := $(ACCOUNTING_IMAGE_GROUP)/$(ACCOUNTING_IMAGE_NAME):$(ACCOUNTING_IMAGE_TAG)
BACKEND_IMAGE_GROUP ?= nherbaut
BACKEND_IMAGE_NAME ?= cloud-catcher
BACKEND_IMAGE_TAG ?= latest
BACKEND_IMAGE_REF := $(BACKEND_IMAGE_GROUP)/$(BACKEND_IMAGE_NAME):$(BACKEND_IMAGE_TAG)
COMPOSE ?= docker compose
COMPOSE_FILE ?= docker-compose.yml

.PHONY: help \
	build-cloud-catcher build-cloud-accounting build-workers build-all \
	push-cloud-catcher push-cloud-accounting push-workers push-all \
	up upup down pull logs ps

help:
	@echo "Root targets:"
	@echo "  make build-all            Build cloud-catcher backend + cloud-accounting + workers images"
	@echo "  make push-all             Push cloud-catcher backend + cloud-accounting + workers images"
	@echo "  make build-cloud-catcher  Build cloud-catcher backend image"
	@echo "  make push-cloud-catcher   Push cloud-catcher backend image"
	@echo "  make build-cloud-accounting Build cloud-accounting image ($(ACCOUNTING_IMAGE_REF))"
	@echo "  make push-cloud-accounting  Push cloud-accounting image ($(ACCOUNTING_IMAGE_REF))"
	@echo "  make build-workers        Build both workers images"
	@echo "  make push-workers         Push both workers images"
	@echo "  make up                   Start full stack from root compose (includes cloud-accounting)"
	@echo "  make down                 Stop root compose stack"
	@echo "  make pull                 Pull images referenced by root compose stack"
	@echo "  make logs                 Follow logs for cloud-catcher + cloud-accounting"
	@echo "  make ps                   Show root compose stack status"
	@echo ""
	@echo "Any other target is delegated to: $(SUBDIR)"

build-cloud-catcher:
	$(MAKE) -C $(CLOUD_CATCHER_DIR) build-backend-image

push-cloud-catcher:
	$(MAKE) -C $(CLOUD_CATCHER_DIR) push-backend-image

build-cloud-accounting:
	$(CLOUD_ACCOUNTING_DIR)/mvnw -f $(CLOUD_ACCOUNTING_DIR)/pom.xml -DskipTests clean package
	docker build -f $(CLOUD_ACCOUNTING_DIR)/src/main/docker/Dockerfile.jvm -t $(ACCOUNTING_IMAGE_REF) $(CLOUD_ACCOUNTING_DIR)

push-cloud-accounting: build-cloud-accounting
	docker push $(ACCOUNTING_IMAGE_REF)

build-workers:
	$(MAKE) -C $(WORKERS_DIR) build-all

push-workers:
	$(MAKE) -C $(WORKERS_DIR) push-all

build-all: build-cloud-catcher build-cloud-accounting build-workers

push-all: push-cloud-catcher push-cloud-accounting push-workers

up: build-cloud-catcher build-cloud-accounting
	BACKEND_IMAGE=$(BACKEND_IMAGE_REF) ACCOUNTING_IMAGE=$(ACCOUNTING_IMAGE_REF) $(COMPOSE) -f $(COMPOSE_FILE) up -d --remove-orphans

upup: up

down:
	$(COMPOSE) -f $(COMPOSE_FILE) down

pull:
	BACKEND_IMAGE=$(BACKEND_IMAGE_REF) ACCOUNTING_IMAGE=$(ACCOUNTING_IMAGE_REF) $(COMPOSE) -f $(COMPOSE_FILE) pull

logs:
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f backend cloud-accounting

ps:
	$(COMPOSE) -f $(COMPOSE_FILE) ps

%:
	$(MAKE) -C $(SUBDIR) $@
