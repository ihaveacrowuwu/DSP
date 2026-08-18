"""FastAPI inference service.

Contract with the Go API is deliberately narrow: image bytes in, structured
assessment out. Nothing here knows about users, sightings or the database.
"""

from __future__ import annotations

import logging

from fastapi import FastAPI, File, Header, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

from .config import settings
from .inference import Classifier

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("muraka.ml")

app = FastAPI(
    title="Muraka ML service",
    version="0.1.0",
    description="Coral condition assessment via patch-grid image classification.",
)

classifier = Classifier(settings)


@app.get("/healthz")
def healthz() -> dict[str, object]:
    """Liveness plus enough detail for the Go API's readiness report."""
    return {
        "status": "ok" if classifier.ready else "degraded",
        "model_version": classifier.model_version,
        "fake_mode": classifier.is_fake,
        "patch_grid": settings.patch_grid,
        "input_size": settings.input_size,
    }


@app.post("/classify")
async def classify(
    request: Request,
    file: UploadFile = File(...),
    x_request_id: str | None = Header(default=None, alias="X-Request-ID"),
) -> JSONResponse:
    """Assess one photo and return per-patch results plus a severity score."""
    payload = await file.read()

    if not payload:
        raise HTTPException(status_code=422, detail="empty upload")
    if len(payload) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"image exceeds {settings.max_upload_bytes} bytes",
        )

    try:
        assessment = classifier.classify(payload)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:  # noqa: BLE001 - surfaced as 500 with correlation id
        log.exception("classification failed request_id=%s", x_request_id)
        raise HTTPException(status_code=500, detail="inference failed") from exc

    log.info(
        "classified request_id=%s filename=%s label=%s severity=%.3f ms=%d fake=%s",
        x_request_id,
        file.filename,
        assessment.label,
        assessment.severity,
        assessment.inference_ms,
        assessment.fake,
    )

    response = JSONResponse(content=assessment.to_dict())
    if x_request_id:
        # Echo the correlation id so Go and Python logs can be joined (NFR12).
        response.headers["X-Request-ID"] = x_request_id
    return response
