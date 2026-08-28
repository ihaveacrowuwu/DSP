"""End-to-end smoke test of the Muraka pipeline against the running stack.

Exercises the whole path a mobile client will take: register, submit metadata,
upload a photo, wait for the worker to classify it, then review it as a
researcher and confirm the expert label wins.
"""

from __future__ import annotations

import datetime as dt
import io
import json
import os
import subprocess
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

# The demo configuration terminates TLS (NFR4), so this has to be able to talk to
# https://localhost:8443 as well as the development stack's plain HTTP. The certificate
# is self-signed because NFR9 rules out a certificate authority, so verification is
# skipped **only** when explicitly asked for - a script that silently accepted any
# certificate would be a worse example than one that cannot reach the demo at all.
API = os.environ.get("MURAKA_API", "http://localhost:8090").rstrip("/")

_TLS_CONTEXT = None
if os.environ.get("MURAKA_TLS_INSECURE") == "1":
    _TLS_CONTEXT = ssl.create_default_context()
    _TLS_CONTEXT.check_hostname = False
    _TLS_CONTEXT.verify_mode = ssl.CERT_NONE

EMAIL = "smoke@muraka.test"
PASSWORD = "smoke-test-password"

passed = 0
failed: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    global passed
    if condition:
        passed += 1
        print(f"  PASS  {name}" + (f" — {detail}" if detail else ""))
    else:
        failed.append(name)
        print(f"  FAIL  {name}" + (f" — {detail}" if detail else ""))


def call(
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: dict | None = None,
    multipart: tuple[str, bytes, str] | None = None,
    raw: bool = False,
) -> tuple[int, object]:
    """Return (status, parsed body). Never raises on HTTP errors."""
    url = API + path
    data = None
    headers: dict[str, str] = {}

    if token:
        headers["Authorization"] = f"Bearer {token}"

    if multipart is not None:
        field_id, file_bytes, photo_id = multipart
        boundary = "----murakasmoke"
        buf = io.BytesIO()
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(b'Content-Disposition: form-data; name="photoId"\r\n\r\n')
        buf.write(photo_id.encode() + b"\r\n")
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(
            f'Content-Disposition: form-data; name="file"; filename="{field_id}"\r\n'.encode()
        )
        buf.write(b"Content-Type: application/octet-stream\r\n\r\n")
        buf.write(file_bytes + b"\r\n")
        buf.write(f"--{boundary}--\r\n".encode())
        data = buf.getvalue()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, context=_TLS_CONTEXT) as response:
            payload = response.read()
            if raw:
                return response.status, payload
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as err:
        payload = err.read()
        try:
            return err.code, json.loads(payload) if payload else None
        except json.JSONDecodeError:
            return err.code, payload


def jpeg(width: int, height: int, colour: tuple[int, int, int]) -> bytes:
    from PIL import Image

    buf = io.BytesIO()
    Image.new("RGB", (width, height), colour).save(buf, format="JPEG")
    return buf.getvalue()


# The run promotes this account to researcher part-way through, so reset it
# first: the script must be repeatable rather than passing only on a clean database.
subprocess.run(
    [
        "docker", "exec", "muraka-postgres-1", "psql", "-U", "muraka", "-d", "muraka", "-q",
        "-c", f"UPDATE app_user SET role='contributor' WHERE email='{EMAIL}';",
    ],
    check=False,
    capture_output=True,
)

print("1. authenticate")
status, session = call(
    "POST",
    "/v1/auth/register",
    body={"email": EMAIL, "password": PASSWORD, "displayName": "Smoke Diver"},
)
if status != 200:
    status, session = call("POST", "/v1/auth/login", body={"email": EMAIL, "password": PASSWORD})
token = session.get("accessToken", "") if isinstance(session, dict) else ""
check("authenticated", bool(token))

print("2. validation rejects a weak password")
status, _ = call(
    "POST",
    "/v1/auth/register",
    body={"email": "weak@muraka.test", "password": "short", "displayName": "Weak"},
)
check("422 on short password", status == 422, f"got {status}")

print("3. unauthenticated requests are refused")
status, _ = call("GET", "/v1/sightings")
check("401 without a token", status == 401, f"got {status}")

print("4. role guard keeps contributors out of the review queue")
status, _ = call("GET", "/v1/verifications/queue", token=token)
check("403 for contributor", status == 403, f"got {status}")

print("5. create a sighting")
sighting_id = str(uuid.uuid4())
captured_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
payload = {
    "id": sighting_id,
    "lat": 4.1755,
    "lon": 73.5093,
    "locationSource": "gps",
    "depthM": 7.5,
    "capturedAt": captured_at,
    "note": "Smoke test dive",
}
status, created = call("POST", "/v1/sightings", token=token, body=payload)
check("201 created", status == 201, f"got {status}: {created}")

print("6. replaying the same submission is idempotent (FR4)")
status, replay = call("POST", "/v1/sightings", token=token, body=payload)
check("200 on replay", status == 200, f"got {status}")
check(
    "no duplicate created",
    isinstance(replay, dict) and replay.get("id") == sighting_id,
)

print("7. upload a photo")
photo_id = str(uuid.uuid4())
status, uploaded = call(
    "POST",
    f"/v1/sightings/{sighting_id}/photos",
    token=token,
    multipart=("reef.jpg", jpeg(900, 700, (46, 120, 130)), photo_id),
)
check("201 photo stored and queued", status == 201, f"got {status}: {uploaded}")

print("8. a non-image upload is refused (NFR5)")
status, _ = call(
    "POST",
    f"/v1/sightings/{sighting_id}/photos",
    token=token,
    multipart=("bad.txt", b"this is definitely not an image", str(uuid.uuid4())),
)
check("422 on non-image", status == 422, f"got {status}")

print("9. the worker classifies it")
sighting_status = "unknown"
elapsed = 0.0
for attempt in range(30):
    status, detail = call("GET", f"/v1/sightings/{sighting_id}", token=token)
    if isinstance(detail, dict):
        sighting_status = detail["sighting"]["status"]
    if sighting_status == "awaiting_verification":
        break
    time.sleep(0.5)
    elapsed += 0.5
check(
    "classified end to end",
    sighting_status == "awaiting_verification",
    f"status={sighting_status} after {elapsed:.1f}s",
)

print("10. the prediction carries per-patch results")
status, detail = call("GET", f"/v1/sightings/{sighting_id}", token=token)
prediction = None
if isinstance(detail, dict) and detail["photos"]:
    prediction = detail["photos"][0].get("prediction")
check("prediction recorded", prediction is not None)
if prediction:
    check("25 patches returned", len(prediction["patches"]) == 25, f"got {len(prediction['patches'])}")
    check("severity in range", 0 <= prediction["severity"] <= 1, str(prediction["severity"]))
    check("label is valid", prediction["label"] in ("healthy", "bleached"), prediction["label"])
    check(
        "sighting reflects the model label",
        detail["sighting"]["condition"] == prediction["label"],
    )
    check("not yet marked verified", detail["sighting"]["verified"] is False)
    print(
        f"        model={prediction['modelVersion']} label={prediction['label']} "
        f"severity={prediction['severity']} confidence={prediction['confidence']} "
        f"grid={prediction['patchGrid']}x{prediction['patchGrid']} "
        f"inference={prediction.get('inferenceMs')}ms"
    )

print("11. promote to researcher and review")
subprocess.run(
    [
        "docker", "exec", "muraka-postgres-1", "psql", "-U", "muraka", "-d", "muraka", "-q",
        "-c", f"UPDATE app_user SET role='researcher' WHERE email='{EMAIL}';",
    ],
    check=True,
    capture_output=True,
)
status, session = call("POST", "/v1/auth/login", body={"email": EMAIL, "password": PASSWORD})
rtoken = session["accessToken"] if isinstance(session, dict) else ""
check("re-authenticated as researcher", session.get("user", {}).get("role") == "researcher")

status, queue = call("GET", "/v1/verifications/queue", token=rtoken)
check("queue is readable", status == 200 and isinstance(queue, dict), f"got {status}")
if isinstance(queue, dict):
    check("queue holds the sighting", queue["total"] >= 1, f"total={queue['total']}")

print("12. record an expert correction")
status, verification = call(
    "POST",
    f"/v1/sightings/{sighting_id}/verification",
    token=rtoken,
    body={
        "decision": "corrected",
        "label": "bleached",
        "comment": "Expert override for smoke test",
    },
)
check("201 verification recorded", status == 201, f"got {status}: {verification}")

print("13. the expert label wins, the prediction survives")
status, detail = call("GET", f"/v1/sightings/{sighting_id}", token=rtoken)
if isinstance(detail, dict):
    sighting = detail["sighting"]
    check("status is verified", sighting["status"] == "verified", sighting["status"])
    check("verified flag set", sighting["verified"] is True)
    check("effective label is the expert's", sighting["condition"] == "bleached", str(sighting["condition"]))
    check("verification history recorded", len(detail["verifications"]) == 1)
    check(
        "prediction not mutated",
        detail["photos"][0]["prediction"] is not None,
        f"model still says {detail['photos'][0]['prediction']['label']}",
    )

print("14. a verification missing its required field is refused")
status, _ = call(
    "POST",
    f"/v1/sightings/{sighting_id}/verification",
    token=rtoken,
    body={"decision": "rejected"},
)
check("422 without a reject reason", status == 422, f"got {status}")

print("15. map, trends and export")
status, map_data = call("GET", "/v1/map/points?zoom=7", token=rtoken)
check("map returns clusters at low zoom", status == 200 and map_data.get("clustered") is True)
if isinstance(map_data, dict):
    check("map has points", len(map_data["points"]) >= 1, f"{len(map_data['points'])} cluster(s)")

status, map_detail = call("GET", "/v1/map/points?zoom=14", token=rtoken)
check(
    "map returns individual points at high zoom",
    status == 200 and map_detail.get("clustered") is False,
)

status, trends = call("GET", "/v1/trends?bucket=month", token=rtoken)
check("trends returns buckets", status == 200 and len(trends["buckets"]) >= 1)

status, csv_bytes = call("GET", "/v1/export/sightings.csv", token=rtoken, raw=True)
lines = csv_bytes.decode().strip().splitlines() if isinstance(csv_bytes, bytes) else []
check("CSV export has header and rows", status == 200 and len(lines) >= 2, f"{len(lines)} lines")
if lines:
    check("CSV carries provenance columns", "verified" in lines[0] and "severity" in lines[0])

print("16. photo image is served to authorised callers")
status, _ = call("GET", f"/v1/photos/{photo_id}/image", token=rtoken, raw=True)
check("200 image served", status == 200, f"got {status}")

print()
if failed:
    print(f"{passed} passed, {len(failed)} FAILED: {', '.join(failed)}")
    sys.exit(1)
print(f"ALL {passed} SMOKE CHECKS PASSED")
