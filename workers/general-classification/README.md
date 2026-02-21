# general-classification

Minimal YOLO-based object detection service with:
- REST API (`POST /predict`)
- AMQP worker (RabbitMQ)

It mirrors the structure of `cloud-classification` but uses a vanilla YOLO detector (`yolov8n.pt` by default).
Dependencies are pinned to CPU-only PyTorch wheels.

## Quick start

```bash
cd workers/general-classification
make install
make run-rest
```

Run AMQP worker:

```bash
make run-amqp
```

If you already installed GPU wheels before, recreate the venv or reinstall:

```bash
python -m pip uninstall -y torch torchvision triton nvidia-cublas-cu12 nvidia-cudnn-cu12 nvidia-cuda-runtime-cu12
python -m pip install -r ../requirements-common.txt -r requirements.txt
```

## REST response shape

```json
{
  "classification": {"label": "person", "score": 0.98, "class_index": 0},
  "predictions": [{"label": "person", "score": 0.98, "class_index": 0}],
  "detections": [
    {"label": "person", "score": 0.98, "class_index": 0, "bbox_xyxy": [12.0, 20.1, 140.2, 360.7]}
  ]
}
```

Supported classes are exposed via `GET /health` as `supported_classes`.
