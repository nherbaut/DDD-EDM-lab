FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV CUDA_VISIBLE_DEVICES=""
ENV MODEL_DIR=/app/models/cloud-classifier
ENV MODEL_REPO=serbekun/CCAiM
ENV DEVICE=-1
ENV TOP_K=3

WORKDIR /app

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt \
    && pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.8.0

COPY . /app

EXPOSE 8000

CMD ["python3", "clouds-classification-rest.py"]
