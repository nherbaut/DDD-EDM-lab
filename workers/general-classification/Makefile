PYTHON ?= python3
YOLO_MODEL ?= yolov8n.pt
PORT ?= 8000
IMAGE ?= general-classification:latest
BASE_IMAGE ?= workers-python-base:latest

.PHONY: help install run run-rest run-worker run-amqp docker-build-base docker-build docker-run

help:
	@echo "Targets:"
	@echo "  make install         Install shared runtime deps + project deps"
	@echo "  make run             Alias of make run-rest"
	@echo "  make run-rest        Run REST API locally on port $(PORT)"
	@echo "  make run-worker      Alias of make run-amqp"
	@echo "  make run-amqp        Run RabbitMQ worker"
	@echo "  make docker-build-base Build shared workers base image $(BASE_IMAGE)"
	@echo "  make docker-build    Build container image $(IMAGE)"
	@echo "  make docker-run      Run container on port $(PORT)"

install:
	$(PYTHON) -m pip install -r ../requirements-common.txt
	$(PYTHON) -m pip install -r requirements.txt

run:
	$(MAKE) run-rest

run-worker:
	$(MAKE) run-amqp

run-rest:
	PYTHONUNBUFFERED=1 CUDA_VISIBLE_DEVICES="" YOLO_MODEL=$(YOLO_MODEL) PORT=$(PORT) $(PYTHON) general-classification-rest.py

run-amqp:
	PYTHONUNBUFFERED=1 CUDA_VISIBLE_DEVICES="" YOLO_MODEL=$(YOLO_MODEL) \
	RABBITMQ_REQUEST_QUEUE=cloud.general.classifier.request \
	RABBITMQ_RESULT_QUEUE=cloud.general.classifier.results \
	RABBITMQ_RESULT_ROUTING_KEY=cloud.general.classifier.results \
	$(PYTHON) general-classification-amqp.py

docker-build-base:
	docker build -f workers/Dockerfile.base -t $(BASE_IMAGE) ../..

docker-build: docker-build-base
	docker build --build-arg BASE_IMAGE=$(BASE_IMAGE) -f workers/general-classification/Dockerfile -t $(IMAGE) ../..

docker-run:
	docker run --rm -p $(PORT):8000 $(IMAGE)
