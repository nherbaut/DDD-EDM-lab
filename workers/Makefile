BASE_IMAGE ?= workers-python-base:latest
PYTHON ?= python3
VENV_DIR ?= .venv
VENV_PYTHON := $(VENV_DIR)/bin/python

CLOUD_IMAGE ?= cloud-classification:latest
GENERAL_IMAGE ?= general-classification:latest

CLOUD_REMOTE ?= nherbaut/ddd-worker-classifier-cloud:latest
GENERAL_REMOTE ?= nherbaut/ddd-worker-classifier-general:latest

.PHONY: help venv install build-base build-cloud build-general build-all tag-cloud tag-general tag-all push-cloud push-general push-all run-cloud run-general run-both clean

help:
	@echo "Targets:"
	@echo "  make venv           Create local virtualenv ($(VENV_DIR))"
	@echo "  make install        Install both workers dependencies in $(VENV_DIR)"
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
	@echo "  make run-cloud      Run cloud AMQP worker locally"
	@echo "  make run-general    Run general AMQP worker locally"
	@echo "  make run-both       Run both AMQP workers locally"
	@echo "  make clean          Remove local worker images and Python caches"

venv:
	@test -x "$(VENV_PYTHON)" || $(PYTHON) -m venv $(VENV_DIR)

install: venv
	$(VENV_PYTHON) -m pip install --upgrade pip
	$(VENV_PYTHON) -m pip install -r requirements-common.txt
	$(VENV_PYTHON) -m pip install -r cloud-classification/requirements.txt
	$(VENV_PYTHON) -m pip install -r general-classification/requirements.txt

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

run-cloud:
	$(MAKE) install
	$(MAKE) -C cloud-classification run-amqp PYTHON=../$(VENV_PYTHON)

run-general:
	$(MAKE) install
	$(MAKE) -C general-classification run-amqp PYTHON=../$(VENV_PYTHON)

run-both:
	@set -e; \
	$(MAKE) install; \
	trap 'kill 0' INT TERM EXIT; \
	$(MAKE) -C general-classification run-amqp PYTHON=../$(VENV_PYTHON) & \
	$(MAKE) -C cloud-classification run-amqp PYTHON=../$(VENV_PYTHON) & \
	wait

clean:
	docker image rm -f $(CLOUD_IMAGE) $(GENERAL_IMAGE) $(BASE_IMAGE) || true
	find . -type d -name "__pycache__" -prune -exec rm -rf {} +
