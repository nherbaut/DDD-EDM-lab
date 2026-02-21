PYTHON ?= python3
YOLO_MODEL ?= yolov8n.pt
PORT ?= 8000
IMAGE ?= general-classification:latest

.PHONY: help install run run-rest run-worker run-amqp docker-build docker-run

help:
	@echo "Targets:"
	@echo "  make install         Install runtime deps (CPU-only torch wheels)"
	@echo "  make run             Alias of make run-rest"
	@echo "  make run-rest        Run REST API locally on port $(PORT)"
	@echo "  make run-worker      Alias of make run-amqp"
	@echo "  make run-amqp        Run RabbitMQ worker"
	@echo "  make docker-build    Build container image $(IMAGE)"
	@echo "  make docker-run      Run container on port $(PORT)"

install:
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

docker-build:
	docker build -t $(IMAGE) .

docker-run:
	docker run --rm -p $(PORT):8000 $(IMAGE)
