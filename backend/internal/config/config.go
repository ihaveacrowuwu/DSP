// Package config loads runtime configuration from the environment.
//
// Every setting has a development default so the stack starts with no env file,
// but production-sensitive values (JWT secret) refuse unsafe defaults when
// MURAKA_ENV=production.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Env      string
	HTTPAddr string

	DatabaseURL string

	JWTSecret       []byte
	JWTIssuer       string
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration

	StorageDir     string
	MaxUploadBytes int64

	MLServiceURL string
	MLTimeout    time.Duration

	WorkerEnabled      bool
	WorkerPollInterval time.Duration
	WorkerBatchSize    int
	WorkerMaxAttempts  int
	WorkerClaimTimeout time.Duration

	CORSOrigins []string
	LogLevel    string
}

const devJWTSecret = "dev-only-insecure-secret-change-me"

func Load() (Config, error) {
	c := Config{
		Env:      env("MURAKA_ENV", "development"),
		HTTPAddr: env("HTTP_ADDR", ":8080"),

		DatabaseURL: env("DATABASE_URL",
			"postgres://muraka:muraka@localhost:5432/muraka?sslmode=disable"),

		JWTSecret:       []byte(env("JWT_SECRET", devJWTSecret)),
		JWTIssuer:       env("JWT_ISSUER", "muraka"),
		AccessTokenTTL:  duration("ACCESS_TOKEN_TTL", 15*time.Minute),
		RefreshTokenTTL: duration("REFRESH_TOKEN_TTL", 30*24*time.Hour),

		StorageDir:     env("STORAGE_DIR", "./data/images"),
		MaxUploadBytes: int64(intVal("MAX_UPLOAD_BYTES", 12<<20)), // 12 MiB

		MLServiceURL: env("ML_SERVICE_URL", "http://localhost:8000"),
		MLTimeout:    duration("ML_TIMEOUT", 60*time.Second),

		WorkerEnabled:      boolVal("WORKER_ENABLED", true),
		WorkerPollInterval: duration("WORKER_POLL_INTERVAL", 2*time.Second),
		WorkerBatchSize:    intVal("WORKER_BATCH_SIZE", 4),
		WorkerMaxAttempts:  intVal("WORKER_MAX_ATTEMPTS", 5),
		WorkerClaimTimeout: duration("WORKER_CLAIM_TIMEOUT", 5*time.Minute),

		CORSOrigins: list("CORS_ORIGINS", []string{"http://localhost:5173"}),
		LogLevel:    env("LOG_LEVEL", "info"),
	}

	if c.IsProduction() && string(c.JWTSecret) == devJWTSecret {
		return Config{}, fmt.Errorf("JWT_SECRET must be set when MURAKA_ENV=production")
	}
	if len(c.JWTSecret) < 16 {
		return Config{}, fmt.Errorf("JWT_SECRET must be at least 16 bytes")
	}
	return c, nil
}

func (c Config) IsProduction() bool { return c.Env == "production" }

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func intVal(key string, def int) int {
	if v, err := strconv.Atoi(env(key, "")); err == nil {
		return v
	}
	return def
}

func boolVal(key string, def bool) bool {
	if v, err := strconv.ParseBool(env(key, "")); err == nil {
		return v
	}
	return def
}

func duration(key string, def time.Duration) time.Duration {
	if v, err := time.ParseDuration(env(key, "")); err == nil {
		return v
	}
	return def
}

func list(key string, def []string) []string {
	raw := env(key, "")
	if raw == "" {
		return def
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return def
	}
	return out
}
