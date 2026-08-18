// Package worker drains the classification queue.
//
// Design note (for the project): the queue lives in PostgreSQL and is claimed
// with FOR UPDATE SKIP LOCKED rather than introducing Redis or a message broker.
// At this project's scale that removes a whole deployable component while still
// giving at-least-once delivery, crash recovery and horizontal scalability.
package worker

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"time"

	"muraka/backend/internal/config"
	"muraka/backend/internal/domain"
	"muraka/backend/internal/mlclient"
	"muraka/backend/internal/storage"
	"muraka/backend/internal/store"
)

type Worker struct {
	cfg    config.Config
	log    *slog.Logger
	store  *store.Store
	images storage.Store
	ml     *mlclient.Client
}

func New(cfg config.Config, log *slog.Logger, st *store.Store, images storage.Store, ml *mlclient.Client) *Worker {
	return &Worker{cfg: cfg, log: log, store: st, images: images, ml: ml}
}

// Run polls until the context is cancelled. Safe to run in several processes.
func (w *Worker) Run(ctx context.Context) {
	w.log.Info("classification worker started",
		"poll_interval", w.cfg.WorkerPollInterval.String(),
		"batch_size", w.cfg.WorkerBatchSize)

	ticker := time.NewTicker(w.cfg.WorkerPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.log.Info("classification worker stopped")
			return
		case <-ticker.C:
			// Keep draining while work remains, so a burst of uploads is not
			// rate-limited by the poll interval.
			for {
				n, err := w.processBatch(ctx)
				if err != nil {
					if !errors.Is(err, context.Canceled) {
						w.log.Error("claim jobs failed", "error", err)
					}
					break
				}
				if n < w.cfg.WorkerBatchSize {
					break
				}
			}
		}
	}
}

func (w *Worker) processBatch(ctx context.Context) (int, error) {
	jobs, err := w.store.ClaimJobs(ctx, w.cfg.WorkerBatchSize, w.cfg.WorkerClaimTimeout)
	if err != nil {
		return 0, err
	}
	if len(jobs) == 0 {
		return 0, nil
	}

	var wg sync.WaitGroup
	for _, job := range jobs {
		wg.Add(1)
		go func(job store.Job) {
			defer wg.Done()
			w.processJob(ctx, job)
		}(job)
	}
	wg.Wait()

	return len(jobs), nil
}

func (w *Worker) processJob(ctx context.Context, job store.Job) {
	log := w.log.With("job_id", job.ID, "photo_id", job.PhotoID, "attempt", job.Attempts)

	if job.StorageKey == "" {
		w.fail(ctx, job, "photo has no storage key", log)
		return
	}

	reader, err := w.images.Get(ctx, job.StorageKey)
	if err != nil {
		w.fail(ctx, job, "read image: "+err.Error(), log)
		return
	}
	raw, err := io.ReadAll(reader)
	reader.Close()
	if err != nil {
		w.fail(ctx, job, "read image bytes: "+err.Error(), log)
		return
	}

	assessment, err := w.ml.Classify(ctx, job.PhotoID.String()+".jpg", raw)
	if err != nil {
		w.fail(ctx, job, "classify: "+err.Error(), log)
		return
	}

	label := domain.Condition(assessment.Label)
	if !label.Valid() {
		w.fail(ctx, job, "ml service returned unknown label "+assessment.Label, log)
		return
	}

	patches := make([]domain.Patch, 0, len(assessment.Patches))
	for _, p := range assessment.Patches {
		patchLabel := domain.Condition(p.Label)
		if !patchLabel.Valid() {
			continue
		}
		patches = append(patches, domain.Patch{
			Row: p.Row, Col: p.Col, Label: patchLabel, Confidence: p.Confidence,
		})
	}

	if err := w.store.CompleteJob(ctx, job.ID, store.PredictionInput{
		PhotoID:      job.PhotoID,
		ModelVersion: assessment.ModelVersion,
		Label:        label,
		Confidence:   assessment.Confidence,
		Severity:     assessment.Severity,
		PatchGrid:    assessment.PatchGrid,
		Patches:      patches,
		InferenceMS:  assessment.InferenceMS,
	}); err != nil {
		log.Error("persist prediction failed", "error", err)
		w.fail(ctx, job, "persist prediction: "+err.Error(), log)
		return
	}

	log.Info("photo classified",
		"label", assessment.Label,
		"severity", assessment.Severity,
		"confidence", assessment.Confidence,
		"model_version", assessment.ModelVersion,
		"inference_ms", assessment.InferenceMS,
		"fake", assessment.Fake)
}

func (w *Worker) fail(ctx context.Context, job store.Job, reason string, log *slog.Logger) {
	log.Warn("classification job failed", "reason", reason)
	if err := w.store.FailJob(ctx, job.ID, reason, w.cfg.WorkerMaxAttempts); err != nil {
		log.Error("could not record job failure", "error", err)
	}
}
