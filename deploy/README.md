# Deployment

Two configurations, from the same compose file plus an overlay.

```bash
make up                 # development: plain HTTP, api 8090, web 5180, ml 8010, pg 5433
make up-tls             # demo: TLS on 8443, dashboard and API on one origin
make seed N=2000        # demo data, either way
```

## Why the demo is an overlay, not the default

NFR4 requires TLS in the deployed/demo configuration. It is not the base stack because
**both mobile apps talk to the development stack over cleartext HTTP**, through a
debug-only exception scoped to localhost - Android's `usesCleartextTraffic` and iOS's
`NSAppTransportSecurity`, neither of which exists in either app's release build. Making
the base stack HTTPS-only would mean installing a self-signed certificate into the
Android emulator's and the iOS simulator's trust stores before either app could sign in.
That is a worse trade than keeping the development stack plain and the demo stack
correct.

So: `deploy/docker-compose.yml` is what a developer runs, and
`deploy/docker-compose.yml + deploy/docker-compose.tls.yml` is what a demo runs.

## The certificate is self-signed, deliberately

NFR9 forbids depending on any external service that requires an account, which rules
out Let's Encrypt and every managed certificate authority. `deploy/tls/generate.sh`
issues a certificate locally instead:

```bash
./deploy/tls/generate.sh       # idempotent; make up-tls calls it
```

A browser will show a warning the first time. **That warning is the honest consequence
of the key-free constraint, not a defect**, and the project says so rather than hiding it
behind a screenshot taken after clicking through. The certificate carries
`subjectAltName` for `localhost`, `muraka.local`, `127.0.0.1` and `::1` - a certificate
with only a `commonName` is rejected outright by browsers and by Go's TLS stack, not
merely warned about.

`deploy/tls/certs/` is gitignored. The private key must never be committed, and
`scripts/check_tls_config.py` fails if a `.key` under `deploy/tls/` is ever tracked.

## One origin

The proxy serves the dashboard and the API from `https://localhost:8443`, so the browser
makes same-origin requests and CORS is not involved at all. That also means the overlay
has to **rebuild the dashboard**: Vite inlines `VITE_API_BASE_URL` at build time, so a
dashboard built for `http://localhost:8090` and served over HTTPS would load perfectly
and then have every request blocked as mixed content - a failure that looks like an empty
page rather than an error.

## Two settings that are load-bearing, and were verified rather than trusted

| Setting | Why |
|---|---|
| `client_max_body_size 12m` | nginx defaults to **1 MB**. Without this, every photograph above that becomes a 413 the API never sees, so the API's own limit and its NFR5 validation both look broken. Verified: with the line removed, a 6.5 MB upload went from 201 to 413. |
| It must match the API, not a guess | The first version said `8m`, which was assumed rather than read - the API's `MaxUploadBytes` default is **12 MiB**. A 9.5 MiB upload succeeds now and would have been rejected by the proxy alone. `scripts/check_tls_config.py` now compares the two numbers so they cannot drift. |

HSTS is deliberately **not** set. With a self-signed certificate it would teach the
browser to refuse the plain-HTTP development stack on the same host.

## Verifying it

```bash
make lint          # static: TLS versions, body limit vs the API, cert not committed
make smoke-tls     # all 33 end-to-end checks, through TLS
```

Run of 2026-08-21: TLS 1.3, HTTP/2, all 33 smoke checks passed over
`https://localhost:8443`.
