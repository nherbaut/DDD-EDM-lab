PYTHON ?= python3
MODEL_REPO ?= serbekun/CCAiM
MODEL_DIR ?= models/cloud-classifier
PORT ?= 8000
IMAGE ?= cloud-classification:latest

.PHONY: help install download-model run run-worker run-rest run-amqp docker-build docker-run

help:
	@echo "Targets:"
	@echo "  make install         Install runtime deps + CPU-only torch"
	@echo "  make download-model  Download HF model into $(MODEL_DIR)"
	@echo "  make run             Alias of make run-rest"
	@echo "  make run-rest        Run REST API locally on port $(PORT)"
	@echo "  make run-worker      Alias of make run-amqp"
	@echo "  make run-amqp        Run RabbitMQ classification worker"
	@echo "  make docker-build    Build container image $(IMAGE)"
	@echo "  make docker-run      Run container on port $(PORT)"

install:
	$(PYTHON) -m pip install --index-url https://download.pytorch.org/whl/cpu torch==2.8.0
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
	RABBITMQ_REQUEST_QUEUE=cloud.cloud-classifier.request \
	RABBITMQ_REQUEST_ROUTING_KEY=cloud.cloud-classifier.request \
	RABBITMQ_RESULT_QUEUE=cloud.cloud-classifier.results \
	RABBITMQ_RESULT_ROUTING_KEY=cloud.cloud-classifier.results \
	$(PYTHON) clouds-classification-amqp.py

docker-build:
	docker build -t $(IMAGE) .

docker-run:
	docker run --rm -p $(PORT):8000 $(IMAGE)
