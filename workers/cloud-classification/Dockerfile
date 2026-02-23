ARG BASE_IMAGE=workers-python-base:latest
FROM ${BASE_IMAGE}

ENV MODEL_DIR=/app/models/cloud-classifier
ENV MODEL_REPO=serbekun/CCAiM
ENV DEVICE=-1
ENV TOP_K=3

WORKDIR /app

COPY workers/cloud-classification/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
RUN python3 -c "import transformers, safetensors; print('cloud worker deps ok')"

COPY workers/cloud-classification/cloud_classifier_service.py /app/cloud_classifier_service.py
COPY workers/cloud-classification/clouds-classification-rest.py /app/clouds-classification-rest.py
COPY workers/cloud-classification/clouds-classification-amqp.py /app/clouds-classification-amqp.py
COPY workers/cloud-classification/mq_worker.py /app/mq_worker.py
# Models are not copied from the repo because they are git-ignored local artifacts.
# The service falls back to MODEL_REPO at runtime when MODEL_DIR does not exist.

EXPOSE 8000

CMD ["python3", "clouds-classification-rest.py"]
