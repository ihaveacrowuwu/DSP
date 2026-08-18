package store

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// Job is a claimed unit of classification work.
type Job struct {
	ID         int64
	PhotoID    uuid.UUID
	StorageKey string
	Attempts   int
}

// ClaimJobs atomically leases up to n queued jobs using FOR UPDATE SKIP LOCKED,
// so multiple workers never collide without needing an external broker.
// Jobs stuck in 'running' past claimTimeout are reclaimed (crash recovery);
// predictions are append-only, so a re-run is harmless.
func (s *Store) ClaimJobs(ctx context.Context, n int, claimTimeout time.Duration) ([]Job, error) {
	rows, err := s.pool.Query(ctx, `
		WITH claimed AS (
			SELECT id
			FROM classification_job
			WHERE status = 'queued'
			   OR (status = 'running' AND claimed_at < now() - $2::interval)
			ORDER BY id
			LIMIT $1
			FOR UPDATE SKIP LOCKED
		)
		UPDATE classification_job j
		SET status = 'running', claimed_at = now(), attempts = j.attempts + 1
		FROM claimed
		WHERE j.id = claimed.id
		RETURNING j.id, j.photo_id, j.attempts,
		          (SELECT storage_key FROM photo WHERE id = j.photo_id)`,
		n, claimTimeout.String())
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []Job
	for rows.Next() {
		var j Job
		if err := rows.Scan(&j.ID, &j.PhotoID, &j.Attempts, &j.StorageKey); err != nil {
			return nil, err
		}
		out = append(out, j)
	}
	return out, rows.Err()
}

// PredictionInput is the ML service's verdict, ready to persist.
type PredictionInput struct {
	PhotoID      uuid.UUID
	ModelVersion string
	Label        domain.Condition
	Confidence   float64
	Severity     float64
	PatchGrid    int
	Patches      []domain.Patch
	InferenceMS  int
}

// CompleteJob records the prediction, closes the job and advances the sighting
// to awaiting_verification once none of its photos are still pending. All in one
// transaction so the pipeline cannot half-finish.
func (s *Store) CompleteJob(ctx context.Context, jobID int64, in PredictionInput) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return mapErr(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	// Register the reporting model version on first sight so predictions always
	// have a resolvable provenance row.
	var modelID uuid.UUID
	if err := tx.QueryRow(ctx, `
		INSERT INTO model_version (version, task)
		VALUES ($1, 'patch_classification')
		ON CONFLICT (version) DO UPDATE SET version = EXCLUDED.version
		RETURNING id`, in.ModelVersion).Scan(&modelID); err != nil {
		return mapErr(err)
	}

	patchesJSON, err := json.Marshal(in.Patches)
	if err != nil {
		return err
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO prediction (
			photo_id, model_version_id, label, confidence, severity,
			patch_grid, patches, inference_ms
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
		in.PhotoID, modelID, in.Label, in.Confidence, in.Severity,
		in.PatchGrid, patchesJSON, in.InferenceMS); err != nil {
		return mapErr(err)
	}

	if _, err := tx.Exec(ctx, `
		UPDATE classification_job
		SET status = 'done', finished_at = now(), last_error = NULL
		WHERE id = $1`, jobID); err != nil {
		return mapErr(err)
	}

	// Advance the sighting only when every photo has at least one prediction.
	if _, err := tx.Exec(ctx, `
		UPDATE sighting s
		SET status = 'awaiting_verification', updated_at = now()
		WHERE s.id = (SELECT sighting_id FROM photo WHERE id = $1)
		  AND s.status IN ('pending_photos', 'processing')
		  AND NOT EXISTS (
			SELECT 1 FROM photo p
			WHERE p.sighting_id = s.id
			  AND NOT EXISTS (SELECT 1 FROM prediction pr WHERE pr.photo_id = p.id)
		  )`, in.PhotoID); err != nil {
		return mapErr(err)
	}

	return mapErr(tx.Commit(ctx))
}

// FailJob returns the job to the queue, or marks it failed once attempts are
// exhausted so a poison message cannot spin forever.
func (s *Store) FailJob(ctx context.Context, jobID int64, reason string, maxAttempts int) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE classification_job
		SET status = CASE WHEN attempts >= $3 THEN 'failed'::job_status ELSE 'queued'::job_status END,
		    last_error = $2,
		    finished_at = CASE WHEN attempts >= $3 THEN now() ELSE NULL END,
		    claimed_at = NULL
		WHERE id = $1`, jobID, reason, maxAttempts)
	return mapErr(err)
}

type QueueDepth struct {
	Queued  int `json:"queued"`
	Running int `json:"running"`
	Failed  int `json:"failed"`
	Done    int `json:"done"`
}

func (s *Store) QueueDepth(ctx context.Context) (QueueDepth, error) {
	var d QueueDepth
	err := s.pool.QueryRow(ctx, `
		SELECT count(*) FILTER (WHERE status = 'queued'),
		       count(*) FILTER (WHERE status = 'running'),
		       count(*) FILTER (WHERE status = 'failed'),
		       count(*) FILTER (WHERE status = 'done')
		FROM classification_job`).Scan(&d.Queued, &d.Running, &d.Failed, &d.Done)
	return d, mapErr(err)
}
