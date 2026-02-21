import os

import uvicorn
from fastapi import FastAPI, HTTPException, Request

from general_classifier_service import GeneralClassifierService

PORT = int(os.getenv("PORT", "8000"))

app = FastAPI(title="General YOLO Classification Service", version="0.1.0")
service = GeneralClassifierService()


@app.on_event("startup")
def startup() -> None:
    service.load()


@app.get("/health")
def health() -> dict:
    return service.health()


@app.post("/predict")
async def predict(request: Request) -> dict:
    content_type = request.headers.get("content-type")
    payload = await request.body()
    try:
        return service.predict_image_bytes(payload, content_type=content_type)
    except ValueError as exc:
        message = str(exc)
        if message in {"Content-Type must be image/jpeg, image/png, or image/webp", "Empty file", "Invalid image format"}:
            status = 415 if message.startswith("Content-Type") else 400
            raise HTTPException(status_code=status, detail=message) from exc
        raise HTTPException(status_code=400, detail=message) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Inference failed: {exc}") from exc


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT)
