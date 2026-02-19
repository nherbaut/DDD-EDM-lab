import os

import uvicorn
from fastapi import FastAPI, HTTPException, Request

from cloud_classifier_service import CloudClassifierService

PORT = int(os.getenv("PORT", "8000"))

app = FastAPI(title="Cloud Classification Service", version="0.2.0")
service = CloudClassifierService()


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
        if message in {"Content-Type must be image/jpeg or image/png", "Empty file", "Invalid image format"}:
            status = 415 if message == "Content-Type must be image/jpeg or image/png" else 400
            raise HTTPException(status_code=status, detail=message) from exc
        raise HTTPException(status_code=400, detail=message) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Inference failed: {exc}") from exc


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT)
