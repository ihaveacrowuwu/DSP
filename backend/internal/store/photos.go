package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

type CreatePhotoInput struct {
	ID             uuid.UUID
	SightingID     uuid.UUID
	StorageKey     string
	ContentHash    string
	Width          int
	Height         int
	Bytes          int
	ExifCapturedAt *time.Time
	ExifLocation   *domain.Point
}

// CreatePhotoWithJob stores photo metadata and enqueues its classification job
// in one transaction: a photo can never exist without work queued for it.
// Idempotent on the client-generated photo id (FR4).
func (s *Store) CreatePhotoWithJob(ctx context.Context, in CreatePhotoInput) (created bool, err error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return false, mapErr(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	var lat, lon *float64
	if in.ExifLocation != nil {
		lat, lon = &in.ExifLocation.Lat, &in.ExifLocation.Lon
	}

	var inserted bool
	err = tx.QueryRow(ctx, `
		INSERT INTO photo (
			id, sighting_id, storage_key, content_hash, width, height, bytes,
			exif_captured_at, exif_location
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8,
			CASE WHEN $9::double precision IS NULL THEN NULL
			     ELSE ST_SetSRID(ST_MakePoint($10, $9), 4326)::geography END)
		ON CONFLICT (id) DO NOTHING
		RETURNING true`,
		in.ID, in.SightingID, in.StorageKey, in.ContentHash,
		in.Width, in.Height, in.Bytes, in.ExifCapturedAt, lat, lon,
	).Scan(&inserted)
	if err != nil {
		if mapErr(err) != ErrNotFound {
			return false, mapErr(err)
		}
		// Replay of an already-stored photo: nothing more to do.
		return false, mapErr(tx.Commit(ctx))
	}

	if _, err := tx.Exec(ctx,
		`INSERT INTO classification_job (photo_id) VALUES ($1)`, in.ID); err != nil {
		return false, mapErr(err)
	}
	if _, err := tx.Exec(ctx,
		`UPDATE sighting SET status = 'processing', updated_at = now()
		 WHERE id = $1 AND status = 'pending_photos'`, in.SightingID); err != nil {
		return false, mapErr(err)
	}

	return true, mapErr(tx.Commit(ctx))
}

func (s *Store) PhotosForSighting(ctx context.Context, sightingID uuid.UUID) ([]domain.Photo, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT p.id, p.sighting_id, p.width, p.height, p.bytes, p.created_at,
		       pr.id, mv.version, pr.label, pr.confidence, pr.severity,
		       pr.patch_grid, pr.patches, pr.inference_ms, pr.created_at
		FROM photo p
		LEFT JOIN LATERAL (
			SELECT * FROM prediction WHERE photo_id = p.id
			ORDER BY created_at DESC LIMIT 1
		) pr ON true
		LEFT JOIN model_version mv ON mv.id = pr.model_version_id
		WHERE p.sighting_id = $1
		ORDER BY p.created_at`, sightingID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []domain.Photo
	for rows.Next() {
		var (
			ph           domain.Photo
			predID       *uuid.UUID
			modelVersion *string
			label        *domain.Condition
			confidence   *float64
			severity     *float64
			patchGrid    *int
			patchesRaw   []byte
			inferenceMS  *int
			predCreated  *time.Time
		)
		if err := rows.Scan(
			&ph.ID, &ph.SightingID, &ph.Width, &ph.Height, &ph.Bytes, &ph.CreatedAt,
			&predID, &modelVersion, &label, &confidence, &severity,
			&patchGrid, &patchesRaw, &inferenceMS, &predCreated,
		); err != nil {
			return nil, err
		}

		ph.URL = "/v1/photos/" + ph.ID.String() + "/image"

		if predID != nil && label != nil {
			pred := &domain.Prediction{
				ID:          *predID,
				PhotoID:     ph.ID,
				Label:       *label,
				InferenceMS: inferenceMS,
			}
			if modelVersion != nil {
				pred.ModelVersion = *modelVersion
			}
			if confidence != nil {
				pred.Confidence = *confidence
			}
			if severity != nil {
				pred.Severity = *severity
			}
			if patchGrid != nil {
				pred.PatchGrid = *patchGrid
			}
			if predCreated != nil {
				pred.CreatedAt = *predCreated
			}
			if len(patchesRaw) > 0 {
				_ = json.Unmarshal(patchesRaw, &pred.Patches)
			}
			ph.Prediction = pred
		}
		out = append(out, ph)
	}
	return out, rows.Err()
}

// PhotoStorageKey resolves a photo's blob location, plus the owning sighting so
// handlers can apply access rules.
func (s *Store) PhotoStorageKey(ctx context.Context, photoID uuid.UUID) (key string, sightingID uuid.UUID, err error) {
	err = s.pool.QueryRow(ctx,
		`SELECT storage_key, sighting_id FROM photo WHERE id = $1`, photoID,
	).Scan(&key, &sightingID)
	return key, sightingID, mapErr(err)
}
