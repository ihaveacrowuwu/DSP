#!/usr/bin/env python3
"""Static checks on the demo TLS configuration — NFR4's "config inspection" half.

NFR4 requires argon2id password hashing, TLS in the deployed/demo configuration, and
access tokens expiring within 15 minutes. The hashing and the token TTL are covered by
the Go test suite. TLS is a *configuration*, and a configuration is exactly the kind of
thing that is correct on the day it is written and quietly wrong six months later — so
the parts that can be checked without running anything are checked here.

Run directly, or through `make lint`:

    python3 scripts/check_tls_config.py

What it will not do is verify a live handshake; that needs the stack up, and it is what
`make smoke-tls` is for.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def ok(message: str) -> None:
    notes.append(message)


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.exists():
        fail(f"{relative} is missing")
        return ""
    return path.read_text()


def main() -> int:
    nginx = read("deploy/tls/nginx.conf")
    overlay = read("deploy/docker-compose.tls.yml")
    generator = read("deploy/tls/generate.sh")

    # ── TLS versions ────────────────────────────────────────────────────────
    protocols = re.search(r"^\s*ssl_protocols\s+([^;]+);", nginx, re.M)
    if not protocols:
        fail("deploy/tls/nginx.conf sets no ssl_protocols")
    else:
        enabled = protocols.group(1).split()
        forbidden = [v for v in enabled if v in {"TLSv1", "TLSv1.1", "SSLv2", "SSLv3"}]
        if forbidden:
            fail(f"deprecated TLS versions are enabled: {', '.join(forbidden)}")
        elif "TLSv1.3" not in enabled:
            fail(f"TLS 1.3 is not enabled (found: {', '.join(enabled)})")
        else:
            ok(f"ssl_protocols = {' '.join(enabled)}")

    # ── Upload body limit ───────────────────────────────────────────────────
    # The reason this is checked rather than trusted: nginx defaults to 1 MB, and the
    # API accepts 8 MB. Without an explicit limit the proxy returns 413 before the API
    # sees the request, so photo uploads fail in the demo and nowhere else — and the
    # API's own validation looks broken. Confirmed by removing the line: a 6.5 MB
    # upload went from 201 to 413.
    limit = re.search(r"^\s*client_max_body_size\s+(\d+)([kKmMgG])?;", nginx, re.M)
    api_limit = api_max_upload_bytes()
    if not limit:
        fail("deploy/tls/nginx.conf sets no client_max_body_size — nginx defaults to "
             "1 MB and will reject photo uploads with 413 before the API sees them")
    else:
        scale = {"k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}
        size = int(limit.group(1)) * scale.get((limit.group(2) or "").lower(), 1)
        if api_limit and size < api_limit:
            fail(f"client_max_body_size is {size} bytes but the API accepts {api_limit}; "
                 "the proxy would reject uploads the API would have allowed")
        else:
            ok(f"client_max_body_size = {size} bytes, API limit = {api_limit or 'unknown'}")

    # ── One origin, so the dashboard is not blocked as mixed content ─────────
    # Vite inlines the API base URL at build time. A dashboard built against
    # http://localhost:8090 and served over HTTPS loads fine and then silently fails
    # every request, which is a worse failure than not loading at all.
    if "VITE_API_BASE_URL" not in overlay:
        fail("the TLS overlay does not rebuild the dashboard against the HTTPS origin; "
             "its API calls would be blocked as mixed content")
    elif not re.search(r"VITE_API_BASE_URL:\s*https://", overlay):
        fail("the TLS overlay builds the dashboard against a non-HTTPS API base URL")
    else:
        ok("the dashboard is built against the HTTPS origin")

    if re.search(r"CORS_ORIGINS:\s*http://", overlay):
        fail("the TLS overlay allows a cleartext CORS origin")

    # ── The certificate must never be committed ─────────────────────────────
    gitignore = read(".gitignore")
    if "deploy/tls/certs" not in gitignore:
        fail("deploy/tls/certs/ is not gitignored — the private key could be committed")
    else:
        ok("deploy/tls/certs/ is gitignored")

    for stray in ROOT.glob("deploy/tls/**/*.key"):
        import subprocess
        tracked = subprocess.run(
            ["git", "ls-files", "--error-unmatch", str(stray.relative_to(ROOT))],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        if tracked.returncode == 0:
            fail(f"{stray.relative_to(ROOT)} is TRACKED BY GIT — it is a private key")

    # ── The certificate needs a SAN, not just a commonName ──────────────────
    if generator and "subjectAltName" not in generator:
        fail("deploy/tls/generate.sh creates a certificate with no subjectAltName; "
             "browsers and Go's TLS stack reject those outright")
    elif generator:
        ok("the generated certificate carries subjectAltName")

    # ── Report ──────────────────────────────────────────────────────────────
    for note in notes:
        print(f"  ok    {note}")
    for problem in failures:
        print(f"  FAIL  {problem}", file=sys.stderr)

    if failures:
        print(f"\n{len(failures)} TLS configuration problem(s)", file=sys.stderr)
        return 1
    print(f"\ndemo TLS configuration passes {len(notes)} checks (NFR4)")
    return 0


def api_max_upload_bytes() -> int | None:
    """The API's own upload cap, so the two limits can be compared rather than guessed."""
    config = read("backend/internal/config/config.go")
    # e.g. `MaxUploadBytes: 8 << 20` or an env default of "8388608".
    shift = re.search(r"MaxUploadBytes[^\n]*?(\d+)\s*<<\s*(\d+)", config)
    if shift:
        return int(shift.group(1)) << int(shift.group(2))
    plain = re.search(r'envInt64\(\s*"MAX_UPLOAD_BYTES"\s*,\s*(\d+)', config)
    if plain:
        return int(plain.group(1))
    return None


if __name__ == "__main__":
    sys.exit(main())
