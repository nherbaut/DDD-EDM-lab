import io
import os
from typing import Any

import numpy as np
from PIL import Image, UnidentifiedImageError

YOLO_MODEL = os.getenv("YOLO_MODEL", "yolov8n.pt")
YOLO_CONF = float(os.getenv("YOLO_CONF", "0.25"))
YOLO_IOU = float(os.getenv("YOLO_IOU", "0.7"))
YOLO_MAX_DET = int(os.getenv("YOLO_MAX_DET", "100"))


class GeneralClassifierService:
    def __init__(self) -> None:
        self.model: Any = None
        self.class_names: dict[int, str] = {}

    def load(self) -> None:
        from ultralytics import YOLO

        self.model = YOLO(YOLO_MODEL)
        raw_names = getattr(self.model, "names", {}) or {}
        if isinstance(raw_names, dict):
            self.class_names = {int(k): str(v) for k, v in raw_names.items()}
        else:
            self.class_names = {idx: str(name) for idx, name in enumerate(raw_names)}

    def health(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "model": YOLO_MODEL,
            "num_supported_classes": len(self.class_names),
            "supported_classes": [self.class_names[idx] for idx in sorted(self.class_names.keys())],
        }

    def predict_image_bytes(self, payload: bytes, content_type: str | None = None) -> dict[str, Any]:
        if content_type is not None:
            normalized = content_type.split(";")[0].strip().lower()
            if normalized not in {"image/jpeg", "image/png", "image/webp"}:
                raise ValueError("Content-Type must be image/jpeg, image/png, or image/webp")
        if not payload:
            raise ValueError("Empty file")

        try:
            image = Image.open(io.BytesIO(payload)).convert("RGB")
        except UnidentifiedImageError as exc:
            raise ValueError("Invalid image format") from exc

        image_array = np.asarray(image)
        results = self.model.predict(
            source=image_array,
            conf=YOLO_CONF,
            iou=YOLO_IOU,
            max_det=YOLO_MAX_DET,
            verbose=False,
        )
        result = results[0]

        detections: list[dict[str, Any]] = []
        for box in result.boxes:
            cls_id = int(box.cls.item())
            score = float(box.conf.item())
            x1, y1, x2, y2 = [float(v) for v in box.xyxy[0].tolist()]
            label = self.class_names.get(cls_id, str(cls_id))
            detections.append(
                {
                    "label": label,
                    "score": score,
                    "class_index": cls_id,
                    "bbox_xyxy": [x1, y1, x2, y2],
                }
            )

        predictions = [
            {
                "label": detection["label"],
                "score": detection["score"],
                "class_index": detection["class_index"],
            }
            for detection in detections
        ]
        best = predictions[0] if predictions else None

        return {
            "classification": best,
            "predictions": predictions,
            "detections": detections,
        }
