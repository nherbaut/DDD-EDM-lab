ARG BASE_IMAGE=workers-python-base:latest
FROM ${BASE_IMAGE}

ENV YOLO_MODEL=yolov8n.pt
ENV YOLO_CONF=0.25
ENV YOLO_IOU=0.7
ENV YOLO_MAX_DET=100

WORKDIR /app

COPY workers/general-classification/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY workers/general-classification/general_classifier_service.py /app/general_classifier_service.py
COPY workers/general-classification/general-classification-rest.py /app/general-classification-rest.py
COPY workers/general-classification/general-classification-amqp.py /app/general-classification-amqp.py
COPY workers/general-classification/yolov8n.pt /app/yolov8n.pt

EXPOSE 8000

CMD ["python3", "general-classification-rest.py"]
