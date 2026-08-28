"""Performance harness for the Muraka API - NFR1, NFR2, NFR3 and NFR11.

Four of the project's non-functional requirements are numbers, and until now all four
were carried in prose: "measured ~1.5s", "22ms", "320ms". A remembered figure is not
evidence, and the project's evaluation chapter needs a command that prints the number
and a recorded run that produced it. This is that command.

    python3 scripts/perf_test.py                 # every check
    python3 scripts/perf_test.py --only nfr11    # one of them
    python3 scripts/perf_test.py --json out.json # machine-readable, for the project

Deliberately standard library only - `concurrent.futures` and `urllib` - because NFR9
forbids depending on a service that needs an account, and reaching for k6 or Locust
would mean either a new toolchain dependency or a hosted runner. Fifty concurrent
submissions do not need a load-testing framework; they need fifty threads.

What each check measures:

  NFR11  50 concurrent sighting submissions, no error and no data loss. Both halves
         are asserted: every response is a success AND the database ends up with
         exactly the rows that were submitted, no more and no fewer.
  NFR1   Time from a synced sighting to its ML label being readable. The requirement
         says 30 seconds; the pipeline is a worker polling a queue, so this is mostly
         a measure of poll interval plus inference.
  NFR2   Inference time per image, read from what the service actually reported rather
         than timed from outside, so network and queueing are excluded - which is what
         "inference shall run <=500ms" means.
  NFR3   Map viewport latency at scale. Needs the database seeded to 10,000 sightings
         to be a real test of the requirement; it reports the count it actually found
         so a run against 200 rows cannot be mistaken for a pass.
  AUTH   Latency of an authenticated request. Not a requirement of its own - it is
         here because D45 added a primary-key lookup to every authenticated request,
         and that trade was made on the promise of being measured rather than assumed.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import io
import json
import os
import statistics
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

CONTRIBUTOR = ("diver@muraka.test", "muraka-diver-2026")
RESEARCHER = ("researcher@muraka.test", "muraka-research-2026")

# NFR thresholds, from docs/07-requirements.md.
NFR1_SECONDS = 30.0
NFR2_MS = 500.0
NFR3_SECONDS = 2.0
NFR11_CONCURRENCY = 50


class Failure(Exception):
    """A check failed. Carries the message the project should quote."""


def call(
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: dict | None = None,
    multipart: tuple[bytes, str] | None = None,
    timeout: float = 60.0,
) -> tuple[int, object]:
    """Return (status, parsed body). Never raises on an HTTP status."""
    data = None
    headers: dict[str, str] = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    if multipart is not None:
        file_bytes, photo_id = multipart
        boundary = "----murakaperf"
        buf = io.BytesIO()
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(b'Content-Disposition: form-data; name="photoId"\r\n\r\n')
        buf.write(photo_id.encode() + b"\r\n")
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(
            b'Content-Disposition: form-data; name="file"; filename="reef.jpg"\r\n'
            b"Content-Type: image/jpeg\r\n\r\n"
        )
        buf.write(file_bytes + b"\r\n")
        buf.write(f"--{boundary}--\r\n".encode())
        data = buf.getvalue()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_TLS_CONTEXT) as res:
            raw = res.read()
            return res.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return err.code, raw.decode(errors="replace")
    except urllib.error.URLError as err:
        raise Failure(f"cannot reach {API}: {err.reason} - is the stack up?") from err


def login(email: str, password: str) -> str:
    status, body = call("POST", "/v1/auth/login", body={"email": email, "password": password})
    if status != 200 or not isinstance(body, dict):
        raise Failure(f"login as {email} failed with {status}: {body} - run `make seed`")
    return body["accessToken"]


def jpeg(size: int = 640) -> bytes:
    """A real JPEG, because the API validates and re-encodes uploads (NFR5)."""
    try:
        from PIL import Image
    except ImportError as err:  # pragma: no cover - depends on the host
        raise Failure(
            "Pillow is needed to build a test image: python3 -m pip install pillow"
        ) from err
    buf = io.BytesIO()
    Image.new("RGB", (size, size), (18, 96, 88)).save(buf, format="JPEG", quality=80)
    return buf.getvalue()


def percentiles(samples: list[float]) -> dict[str, float]:
    ordered = sorted(samples)
    def at(fraction: float) -> float:
        if not ordered:
            return 0.0
        index = min(len(ordered) - 1, int(fraction * len(ordered)))
        return ordered[index]
    return {
        "min": round(ordered[0], 1) if ordered else 0.0,
        "p50": round(statistics.median(ordered), 1) if ordered else 0.0,
        "p95": round(at(0.95), 1),
        "max": round(ordered[-1], 1) if ordered else 0.0,
    }


# ---------------------------------------------------------------- NFR11


def check_nfr11() -> dict:
    """50 concurrent submissions, no error and no data loss."""
    token = login(*CONTRIBUTOR)

    # Client-generated ids, exactly as a mobile client does - which is also what makes
    # the data-loss half checkable: every id is known before anything is sent.
    ids = [str(uuid.uuid4()) for _ in range(NFR11_CONCURRENCY)]
    captured = dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    def submit(index: int, sighting_id: str) -> tuple[int, float]:
        payload = {
            "id": sighting_id,
            "lat": 3.0 + index * 0.001,
            "lon": 73.0 + index * 0.001,
            "locationSource": "gps",
            "capturedAt": captured,
        }
        started = time.perf_counter()
        status, _ = call("POST", "/v1/sightings", token=token, body=payload)
        return status, (time.perf_counter() - started) * 1000

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=NFR11_CONCURRENCY) as pool:
        results = list(pool.map(lambda pair: submit(*pair), enumerate(ids)))
    wall = time.perf_counter() - started

    statuses = [status for status, _ in results]
    latencies = [ms for _, ms in results]
    errors = [status for status in statuses if status not in (200, 201)]

    # Data loss is the half a status-code check cannot see: read every id back.
    missing = []
    for sighting_id in ids:
        status, _ = call("GET", f"/v1/sightings/{sighting_id}", token=token)
        if status != 200:
            missing.append(sighting_id)

    ok = not errors and not missing
    return {
        "requirement": "NFR11",
        "claim": f"{NFR11_CONCURRENCY} concurrent submissions without error or data loss",
        "passed": ok,
        "concurrency": NFR11_CONCURRENCY,
        "errors": len(errors),
        "error_statuses": sorted(set(errors)),
        "missing_after_write": len(missing),
        "wall_seconds": round(wall, 2),
        "throughput_per_second": round(NFR11_CONCURRENCY / wall, 1) if wall else 0.0,
        "latency_ms": percentiles(latencies),
    }


# ---------------------------------------------------------------- NFR1 / NFR2


def check_nfr1_and_nfr2() -> list[dict]:
    """Time a sighting from submitted to labelled, and read the inference cost."""
    token = login(*CONTRIBUTOR)

    sighting_id = str(uuid.uuid4())
    photo_id = str(uuid.uuid4())
    captured = dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    status, _ = call("POST", "/v1/sightings", token=token, body={
        "id": sighting_id, "lat": 4.0, "lon": 73.4,
        "locationSource": "gps", "capturedAt": captured,
    })
    if status not in (200, 201):
        raise Failure(f"could not create a sighting: {status}")

    started = time.perf_counter()
    status, _ = call("POST", f"/v1/sightings/{sighting_id}/photos",
                     token=token, multipart=(jpeg(), photo_id))
    if status not in (200, 201):
        raise Failure(f"could not upload a photo: {status}")

    # Poll until a label appears. The clock starts at the upload, because that is the
    # moment the contributor's phone has finished its side of the sync.
    label = None
    inference_ms = None
    elapsed = 0.0
    deadline = started + NFR1_SECONDS * 2  # overshoot, so a miss is reported not hidden
    while time.perf_counter() < deadline:
        status, body = call("GET", f"/v1/sightings/{sighting_id}", token=token)
        if status == 200 and isinstance(body, dict):
            photos = body.get("photos") or []
            for photo in photos:
                prediction = photo.get("prediction")
                if prediction:
                    label = prediction.get("label")
                    inference_ms = prediction.get("inferenceMs")
                    break
        if label:
            elapsed = time.perf_counter() - started
            break
        time.sleep(0.25)

    if not label:
        return [{
            "requirement": "NFR1",
            "claim": f"an ML label is readable within {NFR1_SECONDS:.0f}s of sync",
            "passed": False,
            "detail": "no label appeared before the deadline - is the worker running?",
        }]

    results = [{
        "requirement": "NFR1",
        "claim": f"an ML label is readable within {NFR1_SECONDS:.0f}s of sync",
        "passed": elapsed <= NFR1_SECONDS,
        "seconds": round(elapsed, 2),
        "threshold_seconds": NFR1_SECONDS,
        "label": label,
    }]

    if inference_ms is None:
        results.append({
            "requirement": "NFR2",
            "claim": f"CPU inference ≤{NFR2_MS:.0f}ms per image",
            "passed": False,
            "detail": "the prediction carried no inferenceMs",
        })
    else:
        results.append({
            "requirement": "NFR2",
            "claim": f"CPU inference ≤{NFR2_MS:.0f}ms per image",
            "passed": inference_ms <= NFR2_MS,
            "inference_ms": inference_ms,
            "threshold_ms": NFR2_MS,
            # The service ships in fake mode until the training track produces a model,
            # so this figure says nothing about the real one. Stated, not buried.
            "caveat": "measured against the service's fake mode - no trained model yet",
        })
    return results


# ---------------------------------------------------------------- NFR3


def check_nfr3() -> dict:
    """Map viewport latency, and the corpus size it was measured against."""
    token = login(*RESEARCHER)

    status, me = call("GET", "/v1/trends", token=token)
    if status != 200:
        raise Failure(f"trends returned {status}")

    # The whole-country bounding box at low zoom: the heaviest read the dashboard makes.
    path = "/v1/map/points?zoom=6&bbox=72.0,-1.0,74.5,7.5"
    samples = []
    for _ in range(5):
        started = time.perf_counter()
        status, body = call("GET", path, token=token)
        samples.append((time.perf_counter() - started) * 1000)
        if status != 200:
            raise Failure(f"map returned {status}: {body}")

    features = 0
    if isinstance(body, dict):
        features = len(body.get("features") or body.get("points") or [])

    total = corpus_size(token)
    slowest = max(samples) / 1000
    return {
        "requirement": "NFR3",
        "claim": f"map viewport ≤{NFR3_SECONDS:.0f}s with 10,000 sightings loaded",
        "passed": slowest <= NFR3_SECONDS and total >= 10_000,
        "seconds_worst_of_5": round(slowest, 3),
        "threshold_seconds": NFR3_SECONDS,
        "latency_ms": percentiles(samples),
        "features_returned": features,
        "sightings_in_database": total,
        # A fast query against 200 rows is not evidence for a requirement about 10,000.
        "caveat": None if total >= 10_000 else (
            f"only {total} sightings present - seed 10,000 with `make seed N=10000` "
            "before quoting this as NFR3 evidence"
        ),
    }


def corpus_size(token: str) -> int:
    """Total sightings visible to a researcher, read from the trends aggregation."""
    status, body = call("GET", "/v1/trends", token=token)
    if status != 200 or not isinstance(body, dict):
        return 0
    return sum(bucket.get("total", 0) for bucket in body.get("buckets") or [])


# ---------------------------------------------------------------- AUTH overhead


def check_auth_overhead() -> dict:
    """Authenticated request latency, which D45 made more expensive on purpose."""
    token = login(*CONTRIBUTOR)

    unauth, auth = [], []
    for _ in range(40):
        started = time.perf_counter()
        call("GET", "/healthz")
        unauth.append((time.perf_counter() - started) * 1000)

        started = time.perf_counter()
        status, _ = call("GET", "/v1/me", token=token)
        auth.append((time.perf_counter() - started) * 1000)
        if status != 200:
            raise Failure(f"/v1/me returned {status}")

    return {
        "requirement": "D45",
        "claim": "the per-request user lookup added by D45 is affordable",
        # Informational: there is no threshold in the requirements to pass or fail.
        "passed": None,
        "healthz_ms": percentiles(unauth),
        "authenticated_me_ms": percentiles(auth),
        "note": (
            "/v1/me does its own aggregate query, so the gap is an upper bound on the "
            "auth lookup rather than a measurement of it"
        ),
    }


# ---------------------------------------------------------------- runner

CHECKS = {
    "nfr11": ("NFR11 - concurrent submissions", lambda: [check_nfr11()]),
    "nfr1": ("NFR1/NFR2 - sync to label, inference cost", check_nfr1_and_nfr2),
    "nfr3": ("NFR3 - map at scale", lambda: [check_nfr3()]),
    "auth": ("D45 - authenticated request overhead", lambda: [check_auth_overhead()]),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", choices=sorted(CHECKS), action="append",
                        help="run one check (repeatable); default is all of them")
    parser.add_argument("--json", metavar="PATH", help="also write the results as JSON")
    args = parser.parse_args()

    selected = args.only or list(CHECKS)
    results: list[dict] = []
    for key in selected:
        title, run = CHECKS[key]
        print(f"\n── {title} ──")
        try:
            for result in run():
                results.append(result)
                report(result)
        except Failure as err:
            print(f"  ERROR  {err}")
            results.append({"requirement": key, "passed": False, "detail": str(err)})

    graded = [r for r in results if r.get("passed") is not None]
    failures = [r for r in graded if not r["passed"]]
    print()
    if failures:
        print(f"{len(graded) - len(failures)}/{len(graded)} checks passed, "
              f"FAILED: {', '.join(r['requirement'] for r in failures)}")
    else:
        print(f"all {len(graded)} graded checks passed")

    if args.json:
        stamped = {
            # Passed in rather than computed inside a check, so a result file always
            # records when it was produced.
            "measured_at": dt.datetime.now(dt.UTC).isoformat(),
            "api": API,
            "results": results,
        }
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(stamped, handle, indent=2)
            handle.write("\n")
        print(f"wrote {args.json}")

    return 1 if failures else 0


def report(result: dict) -> None:
    passed = result.get("passed")
    mark = "PASS" if passed else ("----" if passed is None else "FAIL")
    print(f"  {mark}  {result['requirement']}: {result.get('claim', '')}")
    for key, value in result.items():
        if key in {"requirement", "claim", "passed"} or value is None:
            continue
        print(f"          {key}: {value}")


if __name__ == "__main__":
    sys.exit(main())
