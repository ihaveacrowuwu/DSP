package store

import (
	"context"
	"encoding/json"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// ---------------------------------------------------------------- atolls & sites

func (s *Store) ListAtolls(ctx context.Context) ([]domain.Atoll, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, name, code,
		       ST_Y(centroid::geometry), ST_X(centroid::geometry)
		FROM atoll ORDER BY name`)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []domain.Atoll
	for rows.Next() {
		var a domain.Atoll
		if err := rows.Scan(&a.ID, &a.Name, &a.Code, &a.Centroid.Lat, &a.Centroid.Lon); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *Store) UpsertAtoll(ctx context.Context, name, code string, centroid domain.Point) (domain.Atoll, error) {
	var a domain.Atoll
	err := s.pool.QueryRow(ctx, `
		INSERT INTO atoll (name, code, centroid)
		VALUES ($1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography)
		ON CONFLICT (code) DO UPDATE
		SET name = EXCLUDED.name, centroid = EXCLUDED.centroid
		RETURNING id, name, code,
		          ST_Y(centroid::geometry), ST_X(centroid::geometry)`,
		name, code, centroid.Lat, centroid.Lon,
	).Scan(&a.ID, &a.Name, &a.Code, &a.Centroid.Lat, &a.Centroid.Lon)
	return a, mapErr(err)
}

func (s *Store) ListReefSites(ctx context.Context) ([]domain.ReefSite, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id, atoll_id, name FROM reef_site ORDER BY name`)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []domain.ReefSite
	for rows.Next() {
		var r domain.ReefSite
		if err := rows.Scan(&r.ID, &r.AtollID, &r.Name); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// CreateReefSite accepts a GeoJSON polygon. Existing sightings inside the new
// boundary are back-assigned so historical data joins the site immediately.
func (s *Store) CreateReefSite(ctx context.Context, name string, atollID *uuid.UUID, createdBy uuid.UUID, geojson string) (domain.ReefSite, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return domain.ReefSite{}, mapErr(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	var r domain.ReefSite
	if err := tx.QueryRow(ctx, `
		INSERT INTO reef_site (name, atoll_id, created_by, boundary)
		VALUES ($1, $2, $3, ST_GeomFromGeoJSON($4)::geography)
		RETURNING id, atoll_id, name`,
		name, atollID, createdBy, geojson,
	).Scan(&r.ID, &r.AtollID, &r.Name); err != nil {
		return domain.ReefSite{}, mapErr(err)
	}

	if _, err := tx.Exec(ctx, `
		UPDATE sighting s
		SET site_id = $1
		FROM reef_site rs
		WHERE rs.id = $1
		  AND s.site_id IS NULL
		  AND ST_Covers(rs.boundary, s.location)`, r.ID); err != nil {
		return domain.ReefSite{}, mapErr(err)
	}

	if err := tx.Commit(ctx); err != nil {
		return domain.ReefSite{}, mapErr(err)
	}
	return r, nil
}

// ---------------------------------------------------------------- model versions

func (s *Store) ListModelVersions(ctx context.Context) ([]domain.ModelVersion, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, version, task, is_active, metrics, dataset_hash, notes, trained_at, created_at
		FROM model_version ORDER BY created_at DESC`)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []domain.ModelVersion
	for rows.Next() {
		var (
			m       domain.ModelVersion
			metrics []byte
		)
		if err := rows.Scan(&m.ID, &m.Version, &m.Task, &m.IsActive, &metrics,
			&m.DatasetHash, &m.Notes, &m.TrainedAt, &m.CreatedAt); err != nil {
			return nil, err
		}
		if len(metrics) > 0 {
			_ = json.Unmarshal(metrics, &m.Metrics)
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// ActivateModelVersion flips the active flag; the partial unique index in the
// schema guarantees at most one active row, so this is deactivate-then-activate.
func (s *Store) ActivateModelVersion(ctx context.Context, version string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return mapErr(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	if _, err := tx.Exec(ctx,
		`UPDATE model_version SET is_active = false WHERE is_active`); err != nil {
		return mapErr(err)
	}
	tag, err := tx.Exec(ctx,
		`UPDATE model_version SET is_active = true WHERE version = $1`, version)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return mapErr(tx.Commit(ctx))
}

// ---------------------------------------------------------------- audit log

func (s *Store) WriteAudit(ctx context.Context, actorID *uuid.UUID, action, subject string, detail map[string]any) {
	payload, err := json.Marshal(detail)
	if err != nil {
		payload = []byte(`{}`)
	}
	// Audit writes must never break the request they describe; errors are
	// intentionally swallowed here and surfaced by the caller's logging.
	_, _ = s.pool.Exec(ctx, `
		INSERT INTO audit_log (actor_id, action, subject, detail)
		VALUES ($1, $2, $3, $4)`, actorID, action, subject, payload)
}
