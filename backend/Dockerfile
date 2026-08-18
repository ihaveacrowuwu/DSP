# Multi-stage build: compile with the full toolchain, ship a minimal image.
FROM golang:1.26-alpine AS build

WORKDIR /src

# Dependencies first so code edits do not invalidate the module cache layer.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# CGO off => a static binary that runs on a scratch/alpine base.
ENV CGO_ENABLED=0
RUN go build -ldflags="-s -w" -o /out/api    ./cmd/api \
 && go build -ldflags="-s -w" -o /out/worker ./cmd/worker \
 && go build -ldflags="-s -w" -o /out/seed   ./cmd/seed

FROM alpine:3.21

# wget backs the container healthcheck; ca-certificates for any outbound TLS.
RUN apk add --no-cache ca-certificates wget \
 && adduser -D -u 10001 muraka

WORKDIR /app
COPY --from=build /out/api    /usr/local/bin/api
COPY --from=build /out/worker /usr/local/bin/worker
COPY --from=build /out/seed   /usr/local/bin/seed

# Image storage lives on a volume; the app user must own it.
RUN mkdir -p /data/images && chown -R muraka:muraka /data
USER muraka

ENV HTTP_ADDR=:8080 \
    STORAGE_DIR=/data/images

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1

CMD ["api"]
