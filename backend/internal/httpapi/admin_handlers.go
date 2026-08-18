package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// ---------------------------------------------------------------- reference data

func (a *API) handleListAtolls(w http.ResponseWriter, r *http.Request) {
	atolls, err := a.store.ListAtolls(r.Context())
	if err != nil {
		a.writeStoreError(w, r, err, "atolls unavailable")
		return
	}
	if atolls == nil {
		atolls = []domain.Atoll{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": atolls})
}

func (a *API) handleListSites(w http.ResponseWriter, r *http.Request) {
	sites, err := a.store.ListReefSites(r.Context())
	if err != nil {
		a.writeStoreError(w, r, err, "sites unavailable")
		return
	}
	if sites == nil {
		sites = []domain.ReefSite{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": sites})
}

type upsertAtollRequest struct {
	Name string  `json:"name"`
	Code string  `json:"code"`
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
}

func (a *API) handleUpsertAtoll(w http.ResponseWriter, r *http.Request) {
	var req upsertAtollRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	fields := map[string]string{}
	if strings.TrimSpace(req.Name) == "" {
		fields["name"] = "is required"
	}
	if strings.TrimSpace(req.Code) == "" {
		fields["code"] = "is required"
	}
	centroid := domain.Point{Lat: req.Lat, Lon: req.Lon}
	if !centroid.Valid() {
		fields["lat"] = "lat/lon must be a valid WGS84 coordinate"
	}
	if len(fields) > 0 {
		writeFieldErrors(w, fields)
		return
	}

	atoll, err := a.store.UpsertAtoll(r.Context(), req.Name, req.Code, centroid)
	if err != nil {
		a.writeStoreError(w, r, err, "atoll not found")
		return
	}
	writeJSON(w, http.StatusOK, atoll)
}

type createSiteRequest struct {
	Name     string          `json:"name"`
	AtollID  *string         `json:"atollId,omitempty"`
	Boundary json.RawMessage `json:"boundary"` // GeoJSON Polygon
}

func (a *API) handleCreateSite(w http.ResponseWriter, r *http.Request) {
	var req createSiteRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	fields := map[string]string{}
	if strings.TrimSpace(req.Name) == "" {
		fields["name"] = "is required"
	}
	if len(req.Boundary) == 0 {
		fields["boundary"] = "a GeoJSON Polygon is required"
	}
	if len(fields) > 0 {
		writeFieldErrors(w, fields)
		return
	}

	atollID := parseOptionalUUID(req.AtollID)
	creator := callerID(r)

	site, err := a.store.CreateReefSite(r.Context(), req.Name, atollID, creator, string(req.Boundary))
	if err != nil {
		a.writeStoreError(w, r, err, "site not created")
		return
	}
	a.store.WriteAudit(r.Context(), &creator, "site.created", site.ID.String(),
		map[string]any{"name": site.Name})

	writeJSON(w, http.StatusCreated, site)
}

// ---------------------------------------------------------------- users

func (a *API) handleListUsers(w http.ResponseWriter, r *http.Request) {
	users, err := a.store.ListUsers(r.Context())
	if err != nil {
		a.writeStoreError(w, r, err, "users unavailable")
		return
	}
	if users == nil {
		users = []domain.User{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": users})
}

type updateRoleRequest struct {
	Role string `json:"role"`
}

func (a *API) handleUpdateUserRole(w http.ResponseWriter, r *http.Request) {
	targetID, ok := pathUUID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_id", "user id must be a UUID")
		return
	}

	var req updateRoleRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	role := domain.Role(req.Role)
	if !role.Valid() {
		writeFieldErrors(w, map[string]string{"role": "must be contributor, researcher or admin"})
		return
	}

	actor := callerID(r)
	// Guard against an admin removing their own last privileges by accident.
	if actor == targetID && role != domain.RoleAdmin {
		writeError(w, http.StatusConflict, "cannot_demote_self", "an admin cannot change their own role")
		return
	}

	if err := a.store.UpdateUserRole(r.Context(), targetID, role); err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}
	a.store.WriteAudit(r.Context(), &actor, "user.role_changed", targetID.String(),
		map[string]any{"role": role})

	w.WriteHeader(http.StatusNoContent)
}

type updateStatusRequest struct {
	Status string `json:"status"`
}

func (a *API) handleUpdateUserStatus(w http.ResponseWriter, r *http.Request) {
	targetID, ok := pathUUID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_id", "user id must be a UUID")
		return
	}

	var req updateStatusRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.Status != "active" && req.Status != "banned" {
		writeFieldErrors(w, map[string]string{"status": "must be 'active' or 'banned'"})
		return
	}

	actor := callerID(r)
	if actor == targetID {
		writeError(w, http.StatusConflict, "cannot_ban_self", "an admin cannot ban themselves")
		return
	}

	if err := a.store.SetUserStatus(r.Context(), targetID, req.Status); err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}
	if req.Status == "banned" {
		// Revoke sessions immediately; a ban must not wait for token expiry.
		if err := a.store.RevokeUserRefreshTokens(r.Context(), targetID); err != nil {
			a.log.WarnContext(r.Context(), "revoke tokens after ban", "error", err)
		}
	}
	a.store.WriteAudit(r.Context(), &actor, "user.status_changed", targetID.String(),
		map[string]any{"status": req.Status})

	w.WriteHeader(http.StatusNoContent)
}

// ---------------------------------------------------------------- models & queue

func (a *API) handleListModels(w http.ResponseWriter, r *http.Request) {
	models, err := a.store.ListModelVersions(r.Context())
	if err != nil {
		a.writeStoreError(w, r, err, "models unavailable")
		return
	}
	if models == nil {
		models = []domain.ModelVersion{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": models})
}

func (a *API) handleActivateModel(w http.ResponseWriter, r *http.Request) {
	version := chi.URLParam(r, "version")
	if strings.TrimSpace(version) == "" {
		writeError(w, http.StatusBadRequest, "invalid_version", "model version is required")
		return
	}

	if err := a.store.ActivateModelVersion(r.Context(), version); err != nil {
		a.writeStoreError(w, r, err, "model version not found")
		return
	}
	actor := callerID(r)
	a.store.WriteAudit(r.Context(), &actor, "model.activated", version, nil)

	w.WriteHeader(http.StatusNoContent)
}

func (a *API) handleQueueDepth(w http.ResponseWriter, r *http.Request) {
	depth, err := a.store.QueueDepth(r.Context())
	if err != nil {
		a.writeStoreError(w, r, err, "queue unavailable")
		return
	}
	writeJSON(w, http.StatusOK, depth)
}

// parseOptionalUUID converts an optional string field into an optional UUID,
// treating blank and malformed values alike as "not supplied".
func parseOptionalUUID(raw *string) *uuid.UUID {
	if raw == nil || strings.TrimSpace(*raw) == "" {
		return nil
	}
	id, err := uuid.Parse(strings.TrimSpace(*raw))
	if err != nil {
		return nil
	}
	return &id
}
