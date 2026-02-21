BASE_IMAGE ?= workers-python-base:latest

CLOUD_IMAGE ?= cloud-classification:latest
GENERAL_IMAGE ?= general-classification:latest

CLOUD_REMOTE ?= nherbaut/ddd-worker-classifier-cloud:latest
GENERAL_REMOTE ?= nherbaut/ddd-worker-classifier-general:latest

.PHONY: help build-base build-cloud build-general build-all tag-cloud tag-general tag-all push-cloud push-general push-all

help:
	@echo "Targets:"
	@echo "  make build-base     Build shared Python baseline image ($(BASE_IMAGE))"
	@echo "  make build-cloud    Build cloud worker image ($(CLOUD_IMAGE))"
	@echo "  make build-general  Build general worker image ($(GENERAL_IMAGE))"
	@echo "  make build-all      Build baseline + both worker images"
	@echo "  make tag-cloud      Tag cloud image as $(CLOUD_REMOTE)"
	@echo "  make tag-general    Tag general image as $(GENERAL_REMOTE)"
	@echo "  make tag-all        Tag both images for Docker Hub"
	@echo "  make push-cloud     Push cloud image to Docker Hub"
	@echo "  make push-general   Push general image to Docker Hub"
	@echo "  make push-all       Push both images to Docker Hub"

build-base:
	docker build -f Dockerfile.base -t $(BASE_IMAGE) ..

build-cloud: build-base
	docker build --build-arg BASE_IMAGE=$(BASE_IMAGE) -f cloud-classification/Dockerfile -t $(CLOUD_IMAGE) ..

build-general: build-base
	docker build --build-arg BASE_IMAGE=$(BASE_IMAGE) -f general-classification/Dockerfile -t $(GENERAL_IMAGE) ..

build-all: build-cloud build-general

tag-cloud:
	docker tag $(CLOUD_IMAGE) $(CLOUD_REMOTE)

tag-general:
	docker tag $(GENERAL_IMAGE) $(GENERAL_REMOTE)

tag-all: tag-cloud tag-general

push-cloud: tag-cloud
	docker push $(CLOUD_REMOTE)

push-general: tag-general
	docker push $(GENERAL_REMOTE)

push-all: push-cloud push-general
