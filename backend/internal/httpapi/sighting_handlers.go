package httpapi

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	_ "image/gif" // registered so unsupported-but-decodable uploads are detected
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
	"muraka/backend/internal/storage"
	"muraka/backend/internal/store"
)

type createSightingRequest struct {
	ID                    string    `json:"id"` // client-generated UUIDv7 (idempotency key)
	Lat                   float64   `json:"lat"`
	Lon                   float64   `json:"lon"`
	LocationSource        string    `json:"locationSource"`
	LocationAccuracyM     *float64  `json:"locationAccuracyM,omitempty"`
	DepthM                *float64  `json:"depthM,omitempty"`
	CapturedAt            time.Time `json:"capturedAt"`
	Note                  *string   `json:"note,omitempty"`
	SelfAssessedCondition *string   `json:"selfAssessedCondition,omitempty"`
}

// handleCreateSighting ingests sighting metadata. Idempotent on the client id so
// a mobile app may safely retry a sync forever (FR3/FR4).
func (a *API) handleCreateSighting(w http.ResponseWriter, r *http.Request) {
	var req createSightingRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	fields := map[string]string{}

	id, err := uuid.Parse(req.ID)
	if err != nil {
		fields["id"] = "must be a client-generated UUID (v7 recommended)"
	}

	point := domain.Point{Lat: req.Lat, Lon: req.Lon}
	if !point.Valid() {
		fields["lat"] = "lat/lon must be a valid WGS84 coordinate"
	}

	source := domain.LocationSource(req.LocationSource)
	if req.LocationSource == "" {
		source = domain.LocationGPS
	} else if !source.Valid() {
		fields["locationSource"] = "must be 'gps' or 'manual_pin'"
	}

	if req.CapturedAt.IsZero() {
		fields["capturedAt"] = "is required"
	} else if req.CapturedAt.After(time.Now().Add(24 * time.Hour)) {
		// Tolerates device clock skew, rejects nonsense.
		fields["capturedAt"] = "cannot be in the future"
	}

	if req.DepthM != nil && (*req.DepthM < 0 || *req.DepthM > 200) {
		fields["depthM"] = "must be between 0 and 200 metres"
	}

	var selfCondition *domain.Condition
	if req.SelfAssessedCondition != nil && *req.SelfAssessedCondition != "" {
		c := domain.Condition(*req.SelfAssessedCondition)
		if !c.Valid() {
			fields["selfAssessedCondition"] = "must be 'healthy' or 'bleached'"
		} else {
			selfCondition = &c
		}
	}

	if len(fields) > 0 {
		writeFieldErrors(w, fields)
		return
	}

	sighting, created, err := a.store.UpsertSighting(r.Context(), store.CreateSightingInput{
		ID:                    id,
		ContributorID:         callerID(r),
		Location:              point,
		LocationSource:        source,
		LocationAccuracyM:     req.LocationAccuracyM,
		DepthM:                req.DepthM,
		CapturedAt:            req.CapturedAt.UTC(),
		Note:                  req.Note,
		SelfAssessedCondition: selfCondition,
	})
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "id_owned_by_another_user",
				"this sighting id already belongs to a different account")
			return
		}
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}

	status := http.StatusOK // replay
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, sighting)
}

// handleUploadPhoto accepts one image for a sighting. Idempotent on the
// client-supplied photo id; re-uploads return 200 without duplicating work.
func (a *API) handleUploadPhoto(w http.ResponseWriter, r *http.Request) {
	sightingID, ok := pathUUID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_id", "sighting id must be a UUID")
		return
	}

	sighting, err := a.store.SightingByID(r.Context(), sightingID)
	if err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}
	// Only the owner may attach photos; researchers read but never author data.
	if sighting.ContributorID != callerID(r) {
		writeError(w, http.StatusForbidden, "forbidden", "this sighting belongs to another contributor")
		return
	}

	if err := r.ParseMultipartForm(a.cfg.MaxUploadBytes); err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "upload_too_large",
			fmt.Sprintf("upload must be at most %d bytes", a.cfg.MaxUploadBytes))
		return
	}

	photoID, err := uuid.Parse(r.FormValue("photoId"))
	if err != nil {
		writeFieldErrors(w, map[string]string{"photoId": "must be a client-generated UUID"})
		return
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeFieldErrors(w, map[string]string{"file": "an image file is required"})
		return
	}
	defer file.Close()

	if header.Size > a.cfg.MaxUploadBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "upload_too_large",
			fmt.Sprintf("image must be at most %d bytes", a.cfg.MaxUploadBytes))
		return
	}

	raw, err := io.ReadAll(io.LimitReader(file, a.cfg.MaxUploadBytes+1))
	if err != nil {
		writeError(w, http.StatusBadRequest, "read_failed", "could not read the uploaded file")
		return
	}
	if int64(len(raw)) > a.cfg.MaxUploadBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "upload_too_large", "image exceeds the size limit")
		return
	}

	// Read the header alone first. Decoding straight to pixels would let a
	// well-formed file whose IHDR claims 30000x30000 cost 3.6 GB of allocation
	// while weighing 77 bytes on the wire - every byte-count check above passes
	// it, because by every measure they take it is a small file. The header is
	// the only part cheap enough to trust before committing memory.
	cfg, format, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		writeFieldErrors(w, map[string]string{"file": "must be a decodable JPEG or PNG image"})
		return
	}
	if format != "jpeg" && format != "png" {
		writeFieldErrors(w, map[string]string{"file": "must be a JPEG or PNG image"})
		return
	}
	if cfg.Width <= 0 || cfg.Height <= 0 ||
		cfg.Width > a.cfg.MaxImageDimension || cfg.Height > a.cfg.MaxImageDimension {
		writeError(w, http.StatusUnprocessableEntity, "image_too_large",
			fmt.Sprintf("image must be at most %d pixels on a side", a.cfg.MaxImageDimension))
		return
	}
	if cfg.Width*cfg.Height > a.cfg.MaxImagePixels {
		writeError(w, http.StatusUnprocessableEntity, "image_too_large",
			fmt.Sprintf("image must be at most %d pixels in total", a.cfg.MaxImagePixels))
		return
	}

	// Only now, with the cost known and bounded, decode the pixels.
	img, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		writeFieldErrors(w, map[string]string{"file": "must be a decodable JPEG or PNG image"})
		return
	}

	// Re-encode as JPEG: strips EXIF (including any GPS the contributor did not
	// intend to share) while keeping the pixels we need for inference.
	var normalised bytes.Buffer
	if err := jpeg.Encode(&normalised, img, &jpeg.Options{Quality: 88}); err != nil {
		a.log.ErrorContext(r.Context(), "re-encode image", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "could not process the image")
		return
	}
	stored := normalised.Bytes()

	contentHash := storage.HashBytes(stored)
	key := storage.Key(photoID.String(), contentHash, ".jpg")

	if err := a.images.Put(r.Context(), key, bytes.NewReader(stored)); err != nil {
		a.log.ErrorContext(r.Context(), "store image", "error", err, "key", key)
		writeError(w, http.StatusInternalServerError, "internal_error", "could not store the image")
		return
	}

	bounds := img.Bounds()
	created, err := a.store.CreatePhotoWithJob(r.Context(), store.CreatePhotoInput{
		ID:          photoID,
		SightingID:  sightingID,
		StorageKey:  key,
		ContentHash: contentHash,
		Width:       bounds.Dx(),
		Height:      bounds.Dy(),
		Bytes:       len(stored),
	})
	if err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}

	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, map[string]any{
		"photoId":    photoID,
		"sightingId": sightingID,
		"width":      bounds.Dx(),
		"height":     bounds.Dy(),
		"bytes":      len(stored),
		"queued":     created,
	})
}

type listResponse struct {
	Items  any `json:"items"`
	Total  int `json:"total"`
	Limit  int `json:"limit"`
	Offset int `json:"offset"`
}

func (a *API) handleListSightings(w http.ResponseWriter, r *http.Request) {
	filter := sightingFilterFromQuery(r)

	// Contributors only ever see their own submissions; verifiers see everything.
	if !callerRole(r).CanVerify() {
		id := callerID(r)
		filter.ContributorID = &id
	} else if mine := queryUUID(r, "contributor"); mine != nil {
		filter.ContributorID = mine
	}

	items, total, err := a.store.ListSightings(r.Context(), filter)
	if err != nil {
		a.writeStoreError(w, r, err, "sightings not found")
		return
	}
	if items == nil {
		items = []domain.Sighting{}
	}
	writeJSON(w, http.StatusOK, listResponse{
		Items: items, Total: total, Limit: filter.Limit, Offset: filter.Offset,
	})
}

type sightingDetailResponse struct {
	Sighting      domain.Sighting       `json:"sighting"`
	Photos        []domain.Photo        `json:"photos"`
	Verifications []domain.Verification `json:"verifications"`
}

func (a *API) handleGetSighting(w http.ResponseWriter, r *http.Request) {
	id, ok := pathUUID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_id", "sighting id must be a UUID")
		return
	}

	sighting, err := a.store.SightingByID(r.Context(), id)
	if err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}
	if !callerRole(r).CanVerify() && sighting.ContributorID != callerID(r) {
		writeError(w, http.StatusForbidden, "forbidden", "this sighting belongs to another contributor")
		return
	}

	photos, err := a.store.PhotosForSighting(r.Context(), id)
	if err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}
	verifications, err := a.store.VerificationsForSighting(r.Context(), id)
	if err != nil {
		a.writeStoreError(w, r, err, "sighting not found")
		return
	}
	if photos == nil {
		photos = []domain.Photo{}
	}
	if verifications == nil {
		verifications = []domain.Verification{}
	}

	writeJSON(w, http.StatusOK, sightingDetailResponse{
		Sighting: sighting, Photos: photos, Verifications: verifications,
	})
}

func (a *API) handleGetPhotoImage(w http.ResponseWriter, r *http.Request) {
	photoID, ok := pathUUID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_id", "photo id must be a UUID")
		return
	}

	key, sightingID, err := a.store.PhotoStorageKey(r.Context(), photoID)
	if err != nil {
		a.writeStoreError(w, r, err, "photo not found")
		return
	}

	if !callerRole(r).CanVerify() {
		sighting, err := a.store.SightingByID(r.Context(), sightingID)
		if err != nil {
			a.writeStoreError(w, r, err, "photo not found")
			return
		}
		if sighting.ContributorID != callerID(r) {
			writeError(w, http.StatusForbidden, "forbidden", "this photo belongs to another contributor")
			return
		}
	}

	reader, err := a.images.Get(r.Context(), key)
	if err != nil {
		a.log.ErrorContext(r.Context(), "read image", "error", err, "key", key)
		writeError(w, http.StatusNotFound, "not_found", "image data is unavailable")
		return
	}
	defer reader.Close()

	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	if _, err := io.Copy(w, reader); err != nil {
		a.log.WarnContext(r.Context(), "stream image aborted", "error", err)
	}
}
