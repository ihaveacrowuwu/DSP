package httpapi

import (
	"context"
	"net/http"
	"time"
)

// handleHealthz is a liveness probe: the process is up. Never touches
// dependencies, so a database blip cannot cause a restart loop.
func (a *API) handleHealthz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":   "ok",
		"uptime_s": int(time.Since(a.started).Seconds()),
		"env":      a.cfg.Env,
	})
}

// handleReadyz is a readiness probe: reports on each dependency. Returns 503 if
// the database is unreachable; a degraded ML service still allows serving reads.
func (a *API) handleReadyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	checks := map[string]any{}
	ready := true

	if err := a.store.Pool().Ping(ctx); err != nil {
		checks["database"] = map[string]any{"status": "down", "error": err.Error()}
		ready = false
	} else {
		checks["database"] = map[string]any{"status": "up"}
	}

	if health, err := a.ml.Health(ctx); err != nil {
		// Degraded, not down: submissions still queue and process later.
		checks["ml_service"] = map[string]any{"status": "down", "error": err.Error()}
	} else {
		checks["ml_service"] = map[string]any{
			"status":        "up",
			"model_version": health.ModelVersion,
			"fake_mode":     health.FakeMode,
			"patch_grid":    health.PatchGrid,
		}
	}

	status := http.StatusOK
	overall := "ready"
	if !ready {
		status = http.StatusServiceUnavailable
		overall = "not_ready"
	}
	writeJSON(w, status, map[string]any{"status": overall, "checks": checks})
}
