package httpapi

import (
	"encoding/csv"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"muraka/backend/internal/domain"
	"muraka/backend/internal/store"
)

func (a *API) handleVerificationQueue(w http.ResponseWriter, r *http.Request) {
	limit := queryInt(r, "limit", 25)
	offset := queryInt(r, "offset", 0)

	items, total, err := a.store.VerificationQueue(r.Context(), limit, offset)
	if err != nil {
		a.writeStoreError(w, r, err, "queue unavailable")
		return
	}
	if items == nil {
		items = []domain.Sighting{}
	}
	writeJSON(w, http.StatusOK, listResponse{Items: items, Total: total, Limit: limit, Offset: offset})
}

type verificationRequest struct {
	Decision     string  `json:"decision"`
	Label        *string `json:"label,omitempty"`
	RejectReason *string `json:"rejectReason,omitempty"`
	Comment      *string `json:"comment,omitempty"`
}

func (a *API) handleCreateVerification(w http.ResponseWriter, r *http.Request) {
	sightingID, ok := pathUUID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_id", "sighting id must be a UUID")
		return
	}

	var req verificationRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	fields := map[string]string{}

	decision := domain.Decision(req.Decision)
	if !decision.Valid() {
		fields["decision"] = "must be 'confirmed', 'corrected' or 'rejected'"
	}

	var label *domain.Condition
	if req.Label != nil && *req.Label != "" {
		c := domain.Condition(*req.Label)
		if !c.Valid() {
			fields["label"] = "must be 'healthy' or 'bleached'"
		} else {
			label = &c
		}
	}

	var reason *domain.RejectReason
	if req.RejectReason != nil && *req.RejectReason != "" {
		rr := domain.RejectReason(*req.RejectReason)
		if !rr.Valid() {
			fields["rejectReason"] = "must be blurry, not_coral, duplicate, spam or other"
		} else {
			reason = &rr
		}
	}

	// Mirrors the DB constraint so clients get a helpful 422 instead of a 500.
	switch decision {
	case domain.DecisionConfirmed, domain.DecisionCorrected:
		if label == nil {
			fields["label"] = "is required when confirming or correcting"
		}
	case domain.DecisionRejected:
		// Only claim it is missing when it really is. A value that was supplied but is
		// not one of the five reasons already reported "must be blurry, not_coral, ...",
		// and overwriting that with "is required" sends a client looking for a field
		// they already sent - which is exactly how this was found.
		if reason == nil && fields["rejectReason"] == "" {
			fields["rejectReason"] = "is required when rejecting"
		}
	}

	if len(fields) > 0 {
		writeFieldErrors(w, fields)
		return
	}

	if _, err := a.store.SightingByID(r.Context(), sightingID); err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}

	verifierID := callerID(r)
	verification, err := a.store.CreateVerification(r.Context(), store.CreateVerificationInput{
		SightingID:   sightingID,
		VerifierID:   verifierID,
		Decision:     decision,
		Label:        label,
		RejectReason: reason,
		Comment:      req.Comment,
	})
	if err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}

	a.store.WriteAudit(r.Context(), &verifierID, "sighting.verified", sightingID.String(),
		map[string]any{"decision": decision, "label": label, "rejectReason": reason})

	writeJSON(w, http.StatusCreated, verification)
}

func (a *API) handleMapPoints(w http.ResponseWriter, r *http.Request) {
	filter := sightingFilterFromQuery(r)
	filter.Limit = 0 // map queries are bounded server-side, not by page size

	zoom := queryInt(r, "zoom", 8)
	if zoom < 1 {
		zoom = 1
	}
	if zoom > 20 {
		zoom = 20
	}

	points, err := a.store.MapPoints(r.Context(), filter, zoom)
	if err != nil {
		a.writeStoreError(w, r, err, "map data unavailable")
		return
	}
	if points == nil {
		points = []store.MapPoint{}
	}
	clustered := zoom < 11
	writeJSON(w, http.StatusOK, map[string]any{
		"points":    points,
		"zoom":      zoom,
		"clustered": clustered,
	})
}

func (a *API) handleTrends(w http.ResponseWriter, r *http.Request) {
	filter := sightingFilterFromQuery(r)
	buckets, err := a.store.Trends(r.Context(), filter, r.URL.Query().Get("bucket"))
	if err != nil {
		a.writeStoreError(w, r, err, "trend data unavailable")
		return
	}
	if buckets == nil {
		buckets = []store.TrendBucket{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"buckets": buckets})
}

// handleExportCSV streams the filtered result set with full provenance columns
// so researchers can analyse it in their own tools (FR13).
func (a *API) handleExportCSV(w http.ResponseWriter, r *http.Request) {
	filter := sightingFilterFromQuery(r)
	filter.Limit = 200
	filter.Offset = 0

	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("attachment; filename=\"muraka-sightings-%s.csv\"",
			time.Now().UTC().Format("20060102-150405")))

	cw := csv.NewWriter(w)
	defer cw.Flush()

	_ = cw.Write([]string{
		"sighting_id", "captured_at", "latitude", "longitude", "location_source",
		"depth_m", "site", "status", "condition", "severity", "confidence",
		"verified", "photo_count", "contributor", "note",
	})

	// Page through so a large export never materialises fully in memory.
	for {
		items, _, err := a.store.ListSightings(r.Context(), filter)
		if err != nil {
			a.log.ErrorContext(r.Context(), "csv export failed", "error", err)
			return
		}
		for _, s := range items {
			_ = cw.Write([]string{
				s.ID.String(),
				s.CapturedAt.UTC().Format(time.RFC3339),
				strconv.FormatFloat(s.Location.Lat, 'f', 6, 64),
				strconv.FormatFloat(s.Location.Lon, 'f', 6, 64),
				string(s.LocationSource),
				optionalFloat(s.DepthM, 1),
				optionalString(s.SiteName),
				string(s.Status),
				conditionString(s.Condition),
				optionalFloat(s.Severity, 4),
				optionalFloat(s.Confidence, 4),
				strconv.FormatBool(s.Verified),
				strconv.Itoa(s.PhotoCount),
				s.ContributorName,
				optionalString(s.Note),
			})
		}
		cw.Flush()

		if len(items) < filter.Limit {
			return
		}
		filter.Offset += filter.Limit
	}
}

func optionalFloat(v *float64, precision int) string {
	if v == nil {
		return ""
	}
	return strconv.FormatFloat(*v, 'f', precision, 64)
}

func optionalString(v *string) string {
	if v == nil {
		return ""
	}
	return *v
}

func conditionString(c *domain.Condition) string {
	if c == nil {
		return ""
	}
	return string(*c)
}
