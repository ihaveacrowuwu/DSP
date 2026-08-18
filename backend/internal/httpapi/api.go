// Package httpapi wires the HTTP surface: router, middleware and handlers.
package httpapi

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"muraka/backend/internal/auth"
	"muraka/backend/internal/config"
	"muraka/backend/internal/domain"
	"muraka/backend/internal/mlclient"
	"muraka/backend/internal/storage"
	"muraka/backend/internal/store"
)

type API struct {
	cfg     config.Config
	log     *slog.Logger
	store   *store.Store
	tokens  *auth.TokenIssuer
	images  storage.Store
	ml      *mlclient.Client
	started time.Time
}

func New(cfg config.Config, log *slog.Logger, st *store.Store, tokens *auth.TokenIssuer, images storage.Store, ml *mlclient.Client) *API {
	return &API{cfg: cfg, log: log, store: st, tokens: tokens, images: images, ml: ml, started: time.Now()}
}

func (a *API) Routes() http.Handler {
	r := chi.NewRouter()

	r.Use(requestID)
	r.Use(recoverer(a.log))
	r.Use(requestLogger(a.log))
	r.Use(cors(a.cfg.CORSOrigins))

	r.Get("/healthz", a.handleHealthz)
	r.Get("/readyz", a.handleReadyz)

	r.Route("/v1", func(r chi.Router) {
		// --- public
		r.Route("/auth", func(r chi.Router) {
			r.Post("/register", a.handleRegister)
			r.Post("/login", a.handleLogin)
			r.Post("/refresh", a.handleRefresh)
			r.Post("/logout", a.handleLogout)
		})
		r.Get("/atolls", a.handleListAtolls)

		// --- authenticated
		r.Group(func(r chi.Router) {
			r.Use(a.requireAuth)

			r.Get("/me", a.handleMe)
			r.Delete("/me", a.handleDeleteMe)

			r.Post("/sightings", a.handleCreateSighting)
			r.Post("/sightings/{id}/photos", a.handleUploadPhoto)
			r.Get("/sightings", a.handleListSightings)
			r.Get("/sightings/{id}", a.handleGetSighting)
			r.Get("/photos/{id}/image", a.handleGetPhotoImage)

			r.Get("/sites", a.handleListSites)

			// --- researcher & admin
			r.Group(func(r chi.Router) {
				r.Use(a.requireRole(domain.RoleResearcher, domain.RoleAdmin))

				r.Get("/verifications/queue", a.handleVerificationQueue)
				r.Post("/sightings/{id}/verification", a.handleCreateVerification)
				r.Get("/map/points", a.handleMapPoints)
				r.Get("/trends", a.handleTrends)
				r.Get("/export/sightings.csv", a.handleExportCSV)
			})

			// --- admin only
			r.Group(func(r chi.Router) {
				r.Use(a.requireRole(domain.RoleAdmin))

				r.Get("/admin/users", a.handleListUsers)
				r.Put("/admin/users/{id}/role", a.handleUpdateUserRole)
				r.Put("/admin/users/{id}/status", a.handleUpdateUserStatus)
				r.Post("/admin/sites", a.handleCreateSite)
				r.Post("/admin/atolls", a.handleUpsertAtoll)
				r.Get("/admin/models", a.handleListModels)
				r.Post("/admin/models/{version}/activate", a.handleActivateModel)
				r.Get("/admin/queue", a.handleQueueDepth)
			})
		})
	})

	return r
}

// ---------------------------------------------------------------- responses

type errorBody struct {
	Error   string            `json:"error"`
	Message string            `json:"message,omitempty"`
	Fields  map[string]string `json:"fields,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	if err := json.NewEncoder(w).Encode(v); err != nil {
		// Response already committed; nothing useful left to do.
		return
	}
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorBody{Error: code, Message: message})
}

func writeFieldErrors(w http.ResponseWriter, fields map[string]string) {
	writeJSON(w, http.StatusUnprocessableEntity, errorBody{
		Error:   "validation_failed",
		Message: "one or more fields are invalid",
		Fields:  fields,
	})
}

// writeStoreError maps data-layer errors onto status codes so handlers stay thin.
func (a *API) writeStoreError(w http.ResponseWriter, r *http.Request, err error, notFoundMsg string) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", notFoundMsg)
	case errors.Is(err, store.ErrConflict):
		writeError(w, http.StatusConflict, "conflict", "resource conflicts with existing data")
	default:
		a.log.ErrorContext(r.Context(), "unhandled store error", "error", err, "path", r.URL.Path)
		writeError(w, http.StatusInternalServerError, "internal_error", "unexpected server error")
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", err.Error())
		return false
	}
	return true
}

// ---------------------------------------------------------------- param helpers

func pathUUID(r *http.Request, key string) (uuid.UUID, bool) {
	id, err := uuid.Parse(chi.URLParam(r, key))
	if err != nil {
		return uuid.Nil, false
	}
	return id, true
}

func queryInt(r *http.Request, key string, def int) int {
	if v, err := strconv.Atoi(r.URL.Query().Get(key)); err == nil {
		return v
	}
	return def
}

func queryTime(r *http.Request, key string) *time.Time {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return nil
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02"} {
		if t, err := time.Parse(layout, raw); err == nil {
			return &t
		}
	}
	return nil
}

func queryUUID(r *http.Request, key string) *uuid.UUID {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return nil
	}
	if id, err := uuid.Parse(raw); err == nil {
		return &id
	}
	return nil
}

// parseBBox reads a "minLon,minLat,maxLon,maxLat" viewport parameter.
func parseBBox(raw string) *store.BBox {
	parts := strings.Split(raw, ",")
	if len(parts) != 4 {
		return nil
	}
	vals := make([]float64, 4)
	for i, p := range parts {
		v, err := strconv.ParseFloat(strings.TrimSpace(p), 64)
		if err != nil {
			return nil
		}
		vals[i] = v
	}
	return &store.BBox{MinLon: vals[0], MinLat: vals[1], MaxLon: vals[2], MaxLat: vals[3]}
}

// sightingFilterFromQuery builds the shared filter from query parameters.
func sightingFilterFromQuery(r *http.Request) store.SightingFilter {
	q := r.URL.Query()
	f := store.SightingFilter{
		From:   queryTime(r, "from"),
		To:     queryTime(r, "to"),
		SiteID: queryUUID(r, "site"),
		Limit:  queryInt(r, "limit", 50),
		Offset: queryInt(r, "offset", 0),
	}
	if bbox := parseBBox(q.Get("bbox")); bbox != nil {
		f.BBox = bbox
	}
	if s := q.Get("status"); s != "" {
		f.Status = strings.Split(s, ",")
	}
	if c := domain.Condition(q.Get("condition")); c.Valid() {
		f.Condition = &c
	}
	f.VerifiedOnly = q.Get("verified") == "true"
	return f
}
