import io
import json
import os
from pathlib import Path
from typing import Any

os.environ.setdefault("TRANSFORMERS_NO_TF", "1")
os.environ.setdefault("TRANSFORMERS_NO_FLAX", "1")

import numpy as np
from PIL import Image, UnidentifiedImageError
import torch
import torch.nn as nn
import torch.nn.functional as F

MODEL_DIR = os.getenv("MODEL_DIR", "models/cloud-classifier")
MODEL_REPO = os.getenv("MODEL_REPO", "serbekun/CCAiM")
DEVICE = int(os.getenv("DEVICE", "-1"))  # -1 = CPU
TOP_K = int(os.getenv("TOP_K", "3"))

DEFAULT_CLASS_LABELS = [
    "Cirroculumulus",
    "Stratus",
    "Stratocumulus",
    "Cumulonimbus",
    "Altostratus",
    "Cirrostratus",
    "Nimbostratus",
    "Cumulus",
    "Cirrus",
    "Altocumulus",
]


class ConvBNAct(nn.Module):
    def __init__(self, in_ch: int, out_ch: int, stride: int = 1) -> None:
        super().__init__()
        self.conv = nn.Conv2d(in_ch, out_ch, kernel_size=3, stride=stride, padding=1, bias=False)
        self.bn = nn.BatchNorm2d(out_ch)
        self.act = nn.ReLU(inplace=True)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.act(self.bn(self.conv(x)))


class CCAiMNet(nn.Module):
    def __init__(self, num_classes: int = 10) -> None:
        super().__init__()
        self.stem = ConvBNAct(3, 64, stride=2)
        self.features = nn.ModuleList(
            [
                ConvBNAct(64, 128, stride=2),
                ConvBNAct(128, 128, stride=1),
                ConvBNAct(128, 256, stride=2),
                ConvBNAct(256, 256, stride=1),
                ConvBNAct(256, 512, stride=2),
                ConvBNAct(512, 512, stride=1),
                ConvBNAct(512, 512, stride=2),
                ConvBNAct(512, 512, stride=1),
            ]
        )
        self.proj = nn.Conv2d(512, 1024, kernel_size=1, stride=1, padding=0, bias=False)
        self.proj_bn = nn.BatchNorm2d(1024)
        self.classifier = nn.Linear(1024, num_classes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        x = self.stem(x)
        for block in self.features:
            x = block(x)
        x = F.relu(self.proj_bn(self.proj(x)), inplace=True)
        x = F.adaptive_avg_pool2d(x, output_size=1).flatten(1)
        return self.classifier(x)


class CloudClassifierService:
    def __init__(self) -> None:
        self.classifier: Any = None
        self.ccaim_model: nn.Module | None = None
        self.model2: nn.Module | None = None
        self.model2_transforms: Any = None
        self.class_labels: list[str] | None = None

    def _load_labels(self, num_classes: int) -> list[str]:
        raw = os.getenv("CLASS_LABELS", "").strip()
        if raw:
            labels = [item.strip() for item in raw.split(",") if item.strip()]
            if len(labels) == num_classes:
                return labels
        if num_classes == len(DEFAULT_CLASS_LABELS):
            return DEFAULT_CLASS_LABELS
        return [f"class_{i + 1}" for i in range(num_classes)]

    def _load_ccaim_model(self, model_dir: str) -> tuple[nn.Module, list[str]]:
        config_path = Path(model_dir) / "config.json"
        weights_path = Path(model_dir) / "CCAiM_V0_0_4.pth"
        if not config_path.exists() or not weights_path.exists():
            raise RuntimeError("CCAiM model files not found. Run `make download-model`.")

        with config_path.open("r", encoding="utf-8") as f:
            cfg = json.load(f)
        num_classes = int(cfg.get("num_classes", 10))
        model = CCAiMNet(num_classes=num_classes)

        state_dict = torch.load(weights_path, map_location="cpu")
        model.load_state_dict(state_dict, strict=True)
        model.eval()
        return model, self._load_labels(num_classes)

    def _load_model2(self, model_dir: str) -> tuple[nn.Module, list[str]]:
        try:
            from monai.networks.nets import DenseNet121
        except Exception as exc:
            raise RuntimeError("model2.pth requires MONAI. Run `make install` to install dependencies.") from exc

        weights_path = Path(model_dir) / "model2.pth"
        if not weights_path.exists():
            raise RuntimeError("model2.pth not found")

        state_dict = torch.load(weights_path, map_location="cpu")
        num_classes = int(state_dict["class_layers.out.weight"].shape[0])
        model = DenseNet121(spatial_dims=2, in_channels=1, out_channels=num_classes)
        model.load_state_dict(state_dict, strict=True)
        model.eval()
        return model, self._load_labels(num_classes)

    def _build_model2_transforms(self) -> Any:
        def _transform(inputs: np.ndarray) -> torch.Tensor:
            image = np.asarray(inputs, dtype=np.uint8)
            h = int(image.shape[0])
            resized_hw1 = np.asarray(Image.fromarray(image).resize((1, h), resample=Image.BICUBIC), dtype=np.float32)
            if resized_hw1.ndim == 3:
                reduced = resized_hw1.sum(axis=2)
            else:
                reduced = resized_hw1
            tensor = torch.from_numpy(reduced).unsqueeze(0).unsqueeze(0)
            resized_400_300 = F.interpolate(tensor, size=(400, 300), mode="bicubic", align_corners=False)
            crop = resized_400_300[:, :, 75:225, 100:300]
            return crop.squeeze(0).float()

        return _transform

    def load(self) -> None:
        model_ref = MODEL_DIR if os.path.isdir(MODEL_DIR) else MODEL_REPO
        if os.path.isdir(model_ref):
            model2_path = Path(model_ref) / "model2.pth"
            if model2_path.exists():
                self.model2, self.class_labels = self._load_model2(model_ref)
                self.model2_transforms = self._build_model2_transforms()
                return

            config_path = Path(model_ref) / "config.json"
            if config_path.exists():
                with config_path.open("r", encoding="utf-8") as f:
                    cfg = json.load(f)
                if cfg.get("model_type") == "ccaim":
                    self.ccaim_model, self.class_labels = self._load_ccaim_model(model_ref)
                    return
        from transformers import pipeline

        self.classifier = pipeline("image-classification", model=model_ref, device=DEVICE, trust_remote_code=True)

    def health(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "model_dir": MODEL_DIR,
            "model_repo_fallback": MODEL_REPO,
            "model_dir_exists": os.path.isdir(MODEL_DIR),
        }

    def predict_image_bytes(self, payload: bytes, content_type: str | None = None) -> dict[str, Any]:
        if content_type is not None:
            normalized = content_type.split(";")[0].strip().lower()
            if normalized not in {"image/jpeg", "image/png"}:
                raise ValueError("Content-Type must be image/jpeg or image/png")
        if not payload:
            raise ValueError("Empty file")

        try:
            image = Image.open(io.BytesIO(payload)).convert("RGB")
        except UnidentifiedImageError as exc:
            raise ValueError("Invalid image format") from exc

        if self.model2 is not None:
            image_np = np.asarray(image)
            tensor = self.model2_transforms(image_np).unsqueeze(0).float()
            with torch.no_grad():
                logits = self.model2(tensor)
                probs = torch.softmax(logits, dim=-1).squeeze(0)
            top_scores, top_indices = torch.topk(probs, k=min(TOP_K, probs.shape[0]))
            normalized = [
                {"label": self.class_labels[int(idx)], "score": float(score), "class_index": int(idx)}
                for score, idx in zip(top_scores.tolist(), top_indices.tolist())
            ]
        elif self.ccaim_model is not None:
            image = image.resize((224, 224))
            image_np = np.asarray(image, dtype=np.float32) / 255.0
            tensor = torch.from_numpy(image_np).permute(2, 0, 1)
            mean = torch.tensor([0.485, 0.456, 0.406]).view(3, 1, 1)
            std = torch.tensor([0.229, 0.224, 0.225]).view(3, 1, 1)
            tensor = ((tensor - mean) / std).unsqueeze(0)
            with torch.no_grad():
                logits = self.ccaim_model(tensor)
                probs = torch.softmax(logits, dim=-1).squeeze(0)
            top_scores, top_indices = torch.topk(probs, k=min(TOP_K, probs.shape[0]))
            normalized = [
                {"label": self.class_labels[int(idx)], "score": float(score), "class_index": int(idx)}
                for score, idx in zip(top_scores.tolist(), top_indices.tolist())
            ]
        else:
            predictions = self.classifier(image, top_k=TOP_K)
            normalized = [{"label": pred["label"], "score": float(pred["score"])} for pred in predictions]

        best = normalized[0] if normalized else None
        return {"classification": best, "predictions": normalized}
