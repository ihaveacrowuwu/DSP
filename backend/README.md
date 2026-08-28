# Muraka API (Go)

REST API, classification worker and seed loader.

## Layout

```
cmd/api        HTTP server; also runs the worker in-process unless disabled
cmd/worker     the worker alone, for scaling inference separately
cmd/seed       demo and performance-test data (feature M11)
internal/
  config       environment-driven settings
  database     connection pool + embedded SQL migrations
  domain       shared enums and API types
  auth         argon2id hashing, JWT issuing, refresh tokens
  store        data access: one method per query, plain SQL
  storage      image blob storage behind an interface
  mlclient     HTTP client for the Python inference service
  httpapi      router, middleware, handlers
  worker       drains the classification queue
```

## Running

Normally via the stack:

```bash
docker compose -f deploy/docker-compose.yml up -d
```

Directly, against the containerised database:

```bash
export DATABASE_URL='postgres://muraka:muraka@localhost:5433/muraka?sslmode=disable'
export ML_SERVICE_URL='http://localhost:8010'
go run ./cmd/api
```

Migrations apply automatically on boot, so there is no separate step.

## Tests

```bash
go test ./...
```

These are unit tests with no database dependency: password hashing, token
handling, blob storage safety, and the SQL filter builder. The end-to-end
pipeline is covered by the smoke script (see the root README).

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `MURAKA_ENV` | `development` | `production` refuses the default JWT secret |
| `HTTP_ADDR` | `:8080` | |
| `DATABASE_URL` | local postgres | |
| `JWT_SECRET` | dev-only value | Must be set outside development; 16 bytes minimum |
| `ACCESS_TOKEN_TTL` | `15m` | |
| `REFRESH_TOKEN_TTL` | `720h` | Single-use, rotated on every refresh |
| `STORAGE_DIR` | `./data/images` | |
| `MAX_UPLOAD_BYTES` | `12582912` | 12 MiB |
| `ML_SERVICE_URL` | `http://localhost:8000` | |
| `WORKER_ENABLED` | `true` | Set false when running `cmd/worker` separately |
| `WORKER_BATCH_SIZE` | `4` | Jobs claimed per poll |
| `WORKER_MAX_ATTEMPTS` | `5` | Then the job is marked failed |
| `CORS_ORIGINS` | `http://localhost:5173` | Comma-separated |

## Notes on the design

**The job queue lives in PostgreSQL**, claimed with `FOR UPDATE SKIP LOCKED`
rather than introducing Redis or a broker. At this scale that removes a whole
deployable component while still giving at-least-once delivery, crash recovery
(stale `running` jobs are reclaimed after a timeout) and horizontal scalability.

**Predictions and verifications are append-only.** A sighting's effective
condition is derived - the latest expert verdict wins over the model - so the full
provenance chain survives and the dashboard can always show who decided what.

**Bounding boxes are inlined into SQL as literals, not bound as parameters.**
With bind parameters PostgreSQL cannot fold `ST_MakeEnvelope` into a constant, so
after five executions it switches to a generic plan, abandons the GiST index and
evaluates spheroid comparisons per row. Measured on 2,000 sightings: 7 ms with
literals against 1.4 s once the generic plan took over. The values are parsed
float64s, never raw request text, so inlining cannot inject SQL.
