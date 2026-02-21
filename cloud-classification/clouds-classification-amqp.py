import json
import logging
import os
import random
import time
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

import pika
import requests

from cloud_classifier_service import CloudClassifierService

RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/%2F")
EXCHANGE = os.getenv("RABBITMQ_EXCHANGE", "cloud-classifier-exchange")
REQUEST_QUEUE = os.getenv("RABBITMQ_REQUEST_QUEUE", "cloud.cloud-classifier.request")
REQUEST_ROUTING_KEY = os.getenv("RABBITMQ_REQUEST_ROUTING_KEY", "cloud.cloud-classifier.request")
RESULT_QUEUE = os.getenv("RABBITMQ_RESULT_QUEUE", "cloud.cloud-classifier.results")
RESULT_ROUTING_KEY = os.getenv("RABBITMQ_RESULT_ROUTING_KEY", "cloud.cloud-classifier.results")
INVALID_ROUTING_KEY = os.getenv("RABBITMQ_INVALID_ROUTING_KEY", "cloud.cloud-classifier.invalid")
INVALID_DOCUMENT_TYPE = os.getenv("INVALID_DOCUMENT_TYPE", "InvalidClassificationRequest")
CONNECT_RETRY_BASE_SECONDS = float(os.getenv("RABBITMQ_RETRY_BASE_SECONDS", "1.0"))
CONNECT_RETRY_MAX_SECONDS = float(os.getenv("RABBITMQ_RETRY_MAX_SECONDS", "30.0"))

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s [mq-worker] %(message)s",
)
LOGGER = logging.getLogger("mq-worker")


service = CloudClassifierService()


class NonRetryableMessageError(Exception):
    pass


def compute_backoff_seconds(attempt: int) -> float:
    exp = min(CONNECT_RETRY_MAX_SECONDS, CONNECT_RETRY_BASE_SECONDS * (2 ** max(0, attempt - 1)))
    jitter = random.uniform(0, min(1.0, exp * 0.2))
    return min(CONNECT_RETRY_MAX_SECONDS, exp + jitter)


def normalize_image_url(image_url: str) -> str:
    parsed = urlparse(image_url)
    query_items = [(k, v) for (k, v) in parse_qsl(parsed.query, keep_blank_values=True) if k != "token"]
    return urlunparse(parsed._replace(path=parsed.path, query=urlencode(query_items)))


def publish_invalid_document(
    channel: pika.adapters.blocking_connection.BlockingChannel,
    original_document: dict,
    reason: str,
    details: str,
) -> None:
    invalid_document = {
        "documentType": INVALID_DOCUMENT_TYPE,
        "cloudId": original_document.get("cloudId"),
        "reason": reason,
        "details": details,
        "original": original_document,
    }
    channel.basic_publish(
        exchange=EXCHANGE,
        routing_key=INVALID_ROUTING_KEY,
        body=json.dumps(invalid_document).encode("utf-8"),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
    )
    LOGGER.warning(
        "Invalid message sent cloudId=%s routingKey=%s reason=%s",
        invalid_document.get("cloudId"),
        INVALID_ROUTING_KEY,
        reason,
    )


def handle_request_document(channel: pika.adapters.blocking_connection.BlockingChannel, body: bytes) -> None:
    document = json.loads(body.decode("utf-8"))
    cloud_id = int(document["cloudId"])
    image_url = normalize_image_url(document["imageUrl"])
    LOGGER.info("Accepted request document cloudId=%s imageUrl=%s", cloud_id, image_url)

    LOGGER.info("Downloading cloud image for cloudId=%s", cloud_id)
    image_response = requests.get(image_url, timeout=60)
    try:
        image_response.raise_for_status()
    except requests.HTTPError as exc:
        status_code = exc.response.status_code if exc.response is not None else None
        if status_code == 404:
            publish_invalid_document(
                channel=channel,
                original_document=document,
                reason="IMAGE_NOT_FOUND",
                details=f"Image URL returned HTTP 404: {image_url}",
            )
            raise NonRetryableMessageError("Image not found (404)") from exc
        raise
    image_bytes = image_response.content
    image_content_type = (image_response.headers.get("content-type") or "image/jpeg").split(";")[0].strip().lower()
    LOGGER.info("Cloud image received for cloudId=%s (%s bytes)", cloud_id, len(image_bytes))

    LOGGER.info("Running local inference for cloudId=%s", cloud_id)
    prediction = service.predict_image_bytes(image_bytes, content_type=image_content_type)
    best = prediction.get("classification") or {}
    LOGGER.info(
        "Inference done for cloudId=%s label=%s score=%.6f",
        cloud_id,
        best.get("label", "unknown"),
        float(best.get("score", 0.0)),
    )

    result_document = {
        "documentType": "CloudClassified",
        "cloudId": cloud_id,
        "cloudName": best.get("label", "unknown"),
        "score": best.get("score", 0.0),
        "predictions": prediction.get("predictions", []),
    }
    channel.basic_publish(
        exchange=EXCHANGE,
        routing_key=RESULT_ROUTING_KEY,
        body=json.dumps(result_document).encode("utf-8"),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
    )
    LOGGER.info(
        "Document message sent cloudId=%s routingKey=%s cloudName=%s",
        cloud_id,
        RESULT_ROUTING_KEY,
        result_document["cloudName"],
    )


def main() -> None:
    LOGGER.info("Loading local classifier model")
    service.load()
    LOGGER.info("Local classifier ready")

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
            LOGGER.info("Connecting to RabbitMQ (attempt=%s)", connect_attempt)
            connection = pika.BlockingConnection(parameters)
            connect_attempt = 0
            channel = connection.channel()

            # Topology is owned by Java/Camel. Only check it exists.
            channel.exchange_declare(exchange=EXCHANGE, exchange_type="direct", passive=True)
            channel.queue_declare(queue=REQUEST_QUEUE, passive=True)
            channel.queue_declare(queue=RESULT_QUEUE, passive=True)
            channel.basic_qos(prefetch_count=1)

            def on_message(ch, method, _properties, body):
                LOGGER.info("Message received deliveryTag=%s", method.delivery_tag)
                try:
                    handle_request_document(ch, body)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                    LOGGER.info("Message acked deliveryTag=%s", method.delivery_tag)
                except NonRetryableMessageError as exc:
                    LOGGER.warning("Worker non-retryable error, message acked: %s", exc)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                    LOGGER.info("Message acked deliveryTag=%s", method.delivery_tag)
                except Exception as exc:
                    LOGGER.exception("Worker error, message requeued: %s", exc)
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)

            channel.basic_consume(queue=REQUEST_QUEUE, on_message_callback=on_message)
            LOGGER.info("Cloud classification worker started and consuming")
            channel.start_consuming()
        except Exception as exc:
            wait_s = compute_backoff_seconds(connect_attempt)
            LOGGER.exception("RabbitMQ connection/consume error: %s. Retrying in %.1fs", exc, wait_s)
            time.sleep(wait_s)


if __name__ == "__main__":
    main()
