package store

import (
	"context"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// VerificationQueue returns sightings awaiting expert review, lowest model
// confidence first (FR14/S3): expert attention goes where the model is weakest.
func (s *Store) VerificationQueue(ctx context.Context, limit, offset int) ([]domain.Sighting, int, error) {
	if limit <= 0 || limit > 100 {
		limit = 25
	}

	var total int
	if err := s.pool.QueryRow(ctx,
		`SELECT count(*) FROM sighting WHERE status = 'awaiting_verification'`,
	).Scan(&total); err != nil {
		return nil, 0, mapErr(err)
	}

	rows, err := s.pool.Query(ctx, effectiveLabelCTE+sightingSelect+`
		WHERE s.status = 'awaiting_verification'
		ORDER BY pp.confidence ASC NULLS FIRST, s.created_at ASC
		LIMIT $1 OFFSET $2`, limit, offset)
	if err != nil {
		return nil, 0, mapErr(err)
	}
	defer rows.Close()

	var out []domain.Sighting
	for rows.Next() {
		item, err := scanSighting(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, item)
	}
	return out, total, rows.Err()
}

type CreateVerificationInput struct {
	SightingID   uuid.UUID
	VerifierID   uuid.UUID
	Decision     domain.Decision
	Label        *domain.Condition
	RejectReason *domain.RejectReason
	Comment      *string
}

// CreateVerification appends an audit row and moves the sighting to its resolved
// status. Predictions are never mutated (docs/05 provenance rule).
func (s *Store) CreateVerification(ctx context.Context, in CreateVerificationInput) (domain.Verification, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return domain.Verification{}, mapErr(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	var v domain.Verification
	err = tx.QueryRow(ctx, `
		INSERT INTO verification (sighting_id, verifier_id, decision, label, reject_reason, comment)
		VALUES ($1, $2, $3, $4, $5, $6)
		RETURNING id, sighting_id, verifier_id, decision, label, reject_reason, comment, created_at`,
		in.SightingID, in.VerifierID, in.Decision, in.Label, in.RejectReason, in.Comment,
	).Scan(&v.ID, &v.SightingID, &v.VerifierID, &v.Decision, &v.Label,
		&v.RejectReason, &v.Comment, &v.CreatedAt)
	if err != nil {
		return domain.Verification{}, mapErr(err)
	}

	status := domain.StatusVerified
	if in.Decision == domain.DecisionRejected {
		status = domain.StatusRejected
	}
	if _, err := tx.Exec(ctx,
		`UPDATE sighting SET status = $2, updated_at = now() WHERE id = $1`,
		in.SightingID, status); err != nil {
		return domain.Verification{}, mapErr(err)
	}

	if err := tx.Commit(ctx); err != nil {
		return domain.Verification{}, mapErr(err)
	}
	return v, nil
}

func (s *Store) VerificationsForSighting(ctx context.Context, sightingID uuid.UUID) ([]domain.Verification, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT v.id, v.sighting_id, v.verifier_id, u.display_name,
		       v.decision, v.label, v.reject_reason, v.comment, v.created_at
		FROM verification v
		JOIN app_user u ON u.id = v.verifier_id
		WHERE v.sighting_id = $1
		ORDER BY v.created_at DESC`, sightingID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []domain.Verification
	for rows.Next() {
		var v domain.Verification
		if err := rows.Scan(&v.ID, &v.SightingID, &v.VerifierID, &v.VerifierName,
			&v.Decision, &v.Label, &v.RejectReason, &v.Comment, &v.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
