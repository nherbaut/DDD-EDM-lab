PYTHON ?= python3
MODEL_REPO ?= serbekun/CCAiM
MODEL_DIR ?= models/cloud-classifier
PORT ?= 8000
IMAGE ?= cloud-classification:latest
BASE_IMAGE ?= workers-python-base:latest

.PHONY: help install download-model run run-worker run-rest run-amqp docker-build-base docker-build docker-run

help:
	@echo "Targets:"
	@echo "  make install         Install shared runtime deps + project deps"
	@echo "  make download-model  Download HF model into $(MODEL_DIR)"
	@echo "  make run             Alias of make run-rest"
	@echo "  make run-rest        Run REST API locally on port $(PORT)"
	@echo "  make run-worker      Alias of make run-amqp"
	@echo "  make run-amqp        Run RabbitMQ classification worker"
	@echo "  make docker-build-base Build shared workers base image $(BASE_IMAGE)"
	@echo "  make docker-build    Build container image $(IMAGE)"
	@echo "  make docker-run      Run container on port $(PORT)"

install:
	$(PYTHON) -m pip install -r ../requirements-common.txt
	$(PYTHON) -m pip install -r requirements.txt

download-model:
	MODEL_REPO=$(MODEL_REPO) MODEL_DIR=$(MODEL_DIR) $(PYTHON) download_model.py

run:
	$(MAKE) run-rest

run-worker:
	$(MAKE) run-amqp

run-rest:
	PYTHONUNBUFFERED=1 CUDA_VISIBLE_DEVICES="" MODEL_DIR=$(MODEL_DIR) PORT=$(PORT) $(PYTHON) clouds-classification-rest.py

run-amqp:
	PYTHONUNBUFFERED=1 CUDA_VISIBLE_DEVICES="" MODEL_DIR=$(MODEL_DIR) \
	RABBITMQ_REQUEST_QUEUE=cloud.general.classifier.results \
	RABBITMQ_REQUEST_ROUTING_KEY=classification.general.authorized.v1 \
	RABBITMQ_RESULT_QUEUE=cloud.cloud-classifier.completed \
	RABBITMQ_RESULT_ROUTING_KEY=classification.cloud.completed.v1 \
	$(PYTHON) clouds-classification-amqp.py

docker-build-base:
	docker build -f workers/Dockerfile.base -t $(BASE_IMAGE) ../..

docker-build: docker-build-base
	docker build --build-arg BASE_IMAGE=$(BASE_IMAGE) -f workers/cloud-classification/Dockerfile -t $(IMAGE) ../..

docker-run:
	docker run --rm -p $(PORT):8000 $(IMAGE)
