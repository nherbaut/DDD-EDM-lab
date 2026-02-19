import json
import logging
import os
import random
import time
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import pika
import requests

from general_classifier_service import GeneralClassifierService

RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/%2F")
EXCHANGE = os.getenv("RABBITMQ_EXCHANGE", "cloud-classifier-exchange")
REQUEST_QUEUE = os.getenv("RABBITMQ_REQUEST_QUEUE", "cloud.classifier.requests")
RESULT_QUEUE = os.getenv("RABBITMQ_RESULT_QUEUE", "cloud.classifier.results")
RESULT_ROUTING_KEY = os.getenv("RABBITMQ_RESULT_ROUTING_KEY", "cloud.classify.result")
RESULT_DOCUMENT_TYPE = os.getenv("RESULT_DOCUMENT_TYPE", "ObjectClassified")
CLASSIFIER_FETCH_TOKEN = os.getenv("CLASSIFIER_FETCH_TOKEN", "dev-classifier-token")
CONNECT_RETRY_BASE_SECONDS = float(os.getenv("RABBITMQ_RETRY_BASE_SECONDS", "1.0"))
CONNECT_RETRY_MAX_SECONDS = float(os.getenv("RABBITMQ_RETRY_MAX_SECONDS", "30.0"))

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s [general-mq-worker] %(message)s",
)
LOGGER = logging.getLogger("general-mq-worker")

service = GeneralClassifierService()


def compute_backoff_seconds(attempt: int) -> float:
    exp = min(CONNECT_RETRY_MAX_SECONDS, CONNECT_RETRY_BASE_SECONDS * (2 ** max(0, attempt - 1)))
    jitter = random.uniform(0, min(1.0, exp * 0.2))
    return min(CONNECT_RETRY_MAX_SECONDS, exp + jitter)


def normalize_image_url(image_url: str) -> str:
    parsed = urlparse(image_url)
    path = parsed.path.replace("/cloud/event/", "/clouddb/")
    query = dict(parse_qsl(parsed.query, keep_blank_values=True))
    query.setdefault("token", CLASSIFIER_FETCH_TOKEN)
    return urlunparse(parsed._replace(path=path, query=urlencode(query)))


def handle_request_document(channel: pika.adapters.blocking_connection.BlockingChannel, body: bytes) -> None:
    document = json.loads(body.decode("utf-8"))
    cloud_id = int(document["cloudId"])
    image_url = normalize_image_url(document["imageUrl"])
    LOGGER.info("Accepted request document cloudId=%s imageUrl=%s", cloud_id, image_url)

    image_response = requests.get(image_url, timeout=60)
    image_response.raise_for_status()
    image_bytes = image_response.content
    image_content_type = (image_response.headers.get("content-type") or "image/jpeg").split(";")[0].strip().lower()

    prediction = service.predict_image_bytes(image_bytes, content_type=image_content_type)
    best = prediction.get("classification") or {}

    result_document = {
        "documentType": RESULT_DOCUMENT_TYPE,
        "cloudId": cloud_id,
        "cloudName": best.get("label", "unknown"),
        "score": best.get("score", 0.0),
        "predictions": prediction.get("predictions", []),
        "detections": prediction.get("detections", []),
    }
    channel.basic_publish(
        exchange=EXCHANGE,
        routing_key=RESULT_ROUTING_KEY,
        body=json.dumps(result_document).encode("utf-8"),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
    )
    LOGGER.info("Result sent cloudId=%s cloudName=%s", cloud_id, result_document["cloudName"])


def main() -> None:
    service.load()

    parameters = pika.URLParameters(RABBITMQ_URL)
    connect_attempt = 0
    LOGGER.info(
        "Starting worker with RabbitMQ URL=%s exchange=%s requestQueue=%s resultQueue=%s",
        RABBITMQ_URL,
        EXCHANGE,
        REQUEST_QUEUE,
        RESULT_QUEUE,
    )
    while True:
        try:
            connect_attempt += 1
            connection = pika.BlockingConnection(parameters)
            connect_attempt = 0
            channel = connection.channel()

            channel.exchange_declare(exchange=EXCHANGE, exchange_type="direct", passive=True)
            channel.queue_declare(queue=REQUEST_QUEUE, passive=True)
            channel.queue_declare(queue=RESULT_QUEUE, passive=True)
            channel.basic_qos(prefetch_count=1)

            def on_message(ch, method, _properties, body):
                try:
                    handle_request_document(ch, body)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except Exception:
                    LOGGER.exception("Worker error, message requeued")
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)

            channel.basic_consume(queue=REQUEST_QUEUE, on_message_callback=on_message)
            LOGGER.info("General classification worker started and consuming")
            channel.start_consuming()
        except Exception as exc:
            wait_s = compute_backoff_seconds(connect_attempt)
            LOGGER.exception("RabbitMQ connection/consume error: %s. Retrying in %.1fs", exc, wait_s)
            time.sleep(wait_s)


if __name__ == "__main__":
    main()
