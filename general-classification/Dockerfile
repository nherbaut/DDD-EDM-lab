FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV CUDA_VISIBLE_DEVICES=""
ENV YOLO_MODEL=yolov8n.pt
ENV YOLO_CONF=0.25
ENV YOLO_IOU=0.7
ENV YOLO_MAX_DET=100

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY . /app

EXPOSE 8000

CMD ["python3", "general-classification-rest.py"]
