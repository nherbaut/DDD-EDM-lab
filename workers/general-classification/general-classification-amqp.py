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
REQUEST_QUEUE = os.getenv("RABBITMQ_REQUEST_QUEUE", "cloud.general.classifier.request")
REQUEST_ROUTING_KEY = os.getenv("RABBITMQ_REQUEST_ROUTING_KEY", "classification.quota.reserved.v1")
AUTHORIZED_QUEUE = os.getenv("RABBITMQ_AUTHORIZED_QUEUE", "cloud.general.classifier.results")
AUTHORIZED_ROUTING_KEY = os.getenv("RABBITMQ_AUTHORIZED_ROUTING_KEY", "classification.general.authorized.v1")
DENIED_QUEUE = os.getenv("RABBITMQ_DENIED_QUEUE", "cloud.general.classifier.denied")
DENIED_ROUTING_KEY = os.getenv("RABBITMQ_DENIED_ROUTING_KEY", "classification.general.denied.v1")
INVALID_ROUTING_KEY = os.getenv("RABBITMQ_INVALID_ROUTING_KEY", "cloud.general.classifier.invalid")
INVALID_QUEUE = os.getenv("RABBITMQ_INVALID_QUEUE", INVALID_ROUTING_KEY)
AUTHORIZED_DOCUMENT_TYPE = os.getenv("AUTHORIZED_DOCUMENT_TYPE", "GeneralClassificationAuthorizedV1")
DENIED_DOCUMENT_TYPE = os.getenv("DENIED_DOCUMENT_TYPE", "GeneralClassificationDeniedV1")
INVALID_DOCUMENT_TYPE = os.getenv("INVALID_DOCUMENT_TYPE", "InvalidClassificationRequest")
CONNECT_RETRY_BASE_SECONDS = float(os.getenv("RABBITMQ_RETRY_BASE_SECONDS", "1.0"))
CONNECT_RETRY_MAX_SECONDS = float(os.getenv("RABBITMQ_RETRY_MAX_SECONDS", "30.0"))
GENERAL_CLASSIFICATION_THRESHOLD = float(os.getenv("GENERAL_CLASSIFICATION_THRESHOLD", "0.5"))

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s [general-mq-worker] %(message)s",
)
LOGGER = logging.getLogger("general-mq-worker")

service = GeneralClassifierService()


class NonRetryableMessageError(Exception):
    pass


def compute_backoff_seconds(attempt: int) -> float:
    exp = min(CONNECT_RETRY_MAX_SECONDS, CONNECT_RETRY_BASE_SECONDS * (2 ** max(0, attempt - 1)))
    jitter = random.uniform(0, min(1.0, exp * 0.2))
    return min(CONNECT_RETRY_MAX_SECONDS, exp + jitter)


def normalize_image_url(image_url: str) -> str:
    parsed = urlparse(image_url)
    query_items = [(k, v) for (k, v) in parse_qsl(parsed.query, keep_blank_values=True) if k != "token"]
    return urlunparse(parsed._replace(query=urlencode(query_items)))


def score_at_or_above(predictions: list[dict], threshold: float) -> bool:
    for item in predictions:
        try:
            if float(item.get("score", 0.0)) >= threshold:
                return True
        except (TypeError, ValueError):
            continue
    return False


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

    prediction = service.predict_image_bytes(image_bytes, content_type=image_content_type)
    best = prediction.get("classification") or {}

    classification = {
        "label": best.get("label", "unknown"),
        "score": best.get("score", 0.0),
    }
    predictions = prediction.get("predictions", [])
    detections = prediction.get("detections", [])
    blocked = float(classification.get("score", 0.0) or 0.0) >= GENERAL_CLASSIFICATION_THRESHOLD or score_at_or_above(predictions, GENERAL_CLASSIFICATION_THRESHOLD) or score_at_or_above(
        detections, GENERAL_CLASSIFICATION_THRESHOLD
    )
    routing_key = DENIED_ROUTING_KEY if blocked else AUTHORIZED_ROUTING_KEY
    document_type = DENIED_DOCUMENT_TYPE if blocked else AUTHORIZED_DOCUMENT_TYPE

    result_document = {
        "documentType": document_type,
        "cloudId": cloud_id,
        "requestId": document.get("requestId"),
        "userName": document.get("userName"),
        "minioObjectName": document.get("minioObjectName"),
        "imageUrl": document.get("imageUrl"),
        "classification": classification,
        "cloudName": best.get("label", "unknown"),
        "score": best.get("score", 0.0),
        "predictions": predictions,
        "detections": detections,
        "occurredAt": document.get("occurredAt"),
    }
    channel.basic_publish(
        exchange=EXCHANGE,
        routing_key=routing_key,
        body=json.dumps(result_document).encode("utf-8"),
        properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
    )
    LOGGER.info(
        "Result sent cloudId=%s routingKey=%s cloudName=%s blocked=%s threshold=%.3f",
        cloud_id,
        routing_key,
        result_document["cloudName"],
        blocked,
        GENERAL_CLASSIFICATION_THRESHOLD,
    )


def main() -> None:
    service.load()

    parameters = pika.URLParameters(RABBITMQ_URL)
    connect_attempt = 0
    LOGGER.info(
        "Starting worker with RabbitMQ URL=%s exchange=%s requestQueue=%s authorizedQueue=%s deniedQueue=%s",
        RABBITMQ_URL,
        EXCHANGE,
        REQUEST_QUEUE,
        AUTHORIZED_QUEUE,
        DENIED_QUEUE,
    )
    while True:
        try:
            connect_attempt += 1
            connection = pika.BlockingConnection(parameters)
            connect_attempt = 0
            channel = connection.channel()

            channel.exchange_declare(exchange=EXCHANGE, exchange_type="direct", durable=True)
            channel.queue_declare(queue=REQUEST_QUEUE, durable=False)
            channel.queue_declare(queue=AUTHORIZED_QUEUE, durable=False)
            channel.queue_declare(queue=DENIED_QUEUE, durable=False)
            channel.queue_declare(queue=INVALID_QUEUE, durable=False)
            channel.queue_bind(queue=REQUEST_QUEUE, exchange=EXCHANGE, routing_key=REQUEST_ROUTING_KEY)
            channel.queue_bind(queue=AUTHORIZED_QUEUE, exchange=EXCHANGE, routing_key=AUTHORIZED_ROUTING_KEY)
            channel.queue_bind(queue=DENIED_QUEUE, exchange=EXCHANGE, routing_key=DENIED_ROUTING_KEY)
            channel.queue_bind(queue=INVALID_QUEUE, exchange=EXCHANGE, routing_key=INVALID_ROUTING_KEY)
            channel.basic_qos(prefetch_count=1)

            def on_message(ch, method, _properties, body):
                try:
                    handle_request_document(ch, body)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except NonRetryableMessageError as exc:
                    LOGGER.warning("Worker non-retryable error, message acked: %s", exc)
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
