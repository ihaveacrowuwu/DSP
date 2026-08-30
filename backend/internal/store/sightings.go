package store

import (
	"context"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// effectiveLabelCTE derives each sighting's authoritative condition.
//
// Rules: the latest verification wins over any prediction; a
// sighting's severity is the worst (max) across its photos, and its label comes
// from that worst photo. Nothing here mutates predictions - provenance is kept.
const effectiveLabelCTE = `
WITH latest_verification AS (
    SELECT DISTINCT ON (sighting_id)
           sighting_id, decision, label, created_at
    FROM verification
    ORDER BY sighting_id, created_at DESC
),
photo_prediction AS (
    SELECT p.sighting_id,
           max(pr.severity)                                       AS severity,
           avg(pr.confidence)                                     AS confidence,
           (array_agg(pr.label ORDER BY pr.severity DESC))[1]     AS label
    FROM photo p
    JOIN LATERAL (
        SELECT label, confidence, severity
        FROM prediction
        WHERE photo_id = p.id
        ORDER BY created_at DESC
        LIMIT 1
    ) pr ON true
    GROUP BY p.sighting_id
)`

const sightingSelect = `
    SELECT s.id, s.contributor_id, u.display_name,
           s.site_id, rs.name,
           ST_Y(s.location::geometry), ST_X(s.location::geometry),
           s.location_source, s.location_accuracy_m, s.depth_m,
           s.captured_at, s.note, s.self_assessed_condition, s.status, s.created_at,
           (SELECT count(*) FROM photo WHERE sighting_id = s.id),
           COALESCE(lv.label, pp.label) AS effective_label,
           pp.severity, pp.confidence,
           (lv.decision IN ('confirmed','corrected')) AS verified
    FROM sighting s
    JOIN app_user u          ON u.id = s.contributor_id
    LEFT JOIN reef_site rs   ON rs.id = s.site_id
    LEFT JOIN latest_verification lv ON lv.sighting_id = s.id
    LEFT JOIN photo_prediction pp    ON pp.sighting_id = s.id`

func scanSighting(row interface {
	Scan(dest ...any) error
}) (domain.Sighting, error) {
	var s domain.Sighting
	var verified *bool
	err := row.Scan(
		&s.ID, &s.ContributorID, &s.ContributorName,
		&s.SiteID, &s.SiteName,
		&s.Location.Lat, &s.Location.Lon,
		&s.LocationSource, &s.LocationAccuracyM, &s.DepthM,
		&s.CapturedAt, &s.Note, &s.SelfAssessedCondition, &s.Status, &s.CreatedAt,
		&s.PhotoCount,
		&s.Condition, &s.Severity, &s.Confidence, &verified,
	)
	if err != nil {
		return s, err
	}
	s.Verified = verified != nil && *verified
	return s, nil
}

// CreateSightingInput carries the client-supplied metadata for ingestion.
type CreateSightingInput struct {
	ID                    uuid.UUID
	ContributorID         uuid.UUID
	Location              domain.Point
	LocationSource        domain.LocationSource
	LocationAccuracyM     *float64
	DepthM                *float64
	CapturedAt            time.Time
	Note                  *string
	SelfAssessedCondition *domain.Condition
}

// UpsertSighting is idempotent on the client-generated id (FR4): replaying the
// same submission is a no-op that returns the stored row. Site assignment is
// resolved server-side by PostGIS containment (FR15).
func (s *Store) UpsertSighting(ctx context.Context, in CreateSightingInput) (domain.Sighting, bool, error) {
	var created bool
	err := s.pool.QueryRow(ctx, `
		INSERT INTO sighting (
			id, contributor_id, location, location_source, location_accuracy_m,
			depth_m, captured_at, note, self_assessed_condition, status, site_id
		) VALUES (
			$1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography, $5, $6,
			$7, $8, $9, $10, 'pending_photos',
			(SELECT id FROM reef_site
			  WHERE ST_Covers(boundary, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography)
			  LIMIT 1)
		)
		ON CONFLICT (id) DO NOTHING
		RETURNING true`,
		in.ID, in.ContributorID, in.Location.Lat, in.Location.Lon,
		in.LocationSource, in.LocationAccuracyM,
		in.DepthM, in.CapturedAt, in.Note, in.SelfAssessedCondition,
	).Scan(&created)

	if err != nil && mapErr(err) != ErrNotFound {
		return domain.Sighting{}, false, mapErr(err)
	}

	got, err := s.SightingByID(ctx, in.ID)
	if err != nil {
		return domain.Sighting{}, false, err
	}
	// A replay from a different account must not leak or mutate someone's data.
	if got.ContributorID != in.ContributorID {
		return domain.Sighting{}, false, ErrConflict
	}
	return got, created, nil
}

func (s *Store) SightingByID(ctx context.Context, id uuid.UUID) (domain.Sighting, error) {
	row := s.pool.QueryRow(ctx, effectiveLabelCTE+sightingSelect+` WHERE s.id = $1`, id)
	out, err := scanSighting(row)
	return out, mapErr(err)
}

// SightingFilter is the shared filter set for list, map and export queries.
type SightingFilter struct {
	BBox          *BBox
	From          *time.Time
	To            *time.Time
	Status        []string
	Condition     *domain.Condition
	VerifiedOnly  bool
	SiteID        *uuid.UUID
	ContributorID *uuid.UUID
	// IncludeRejected is false everywhere user-facing (FR11).
	IncludeRejected bool
	Limit           int
	Offset          int
}

type BBox struct {
	MinLat, MinLon, MaxLat, MaxLon float64
}

// coord renders a coordinate for inlining into SQL. Fixed notation avoids the
// exponent form that PostGIS will not parse, and six decimal places is ~0.1 m.
func coord(v float64) string {
	return strconv.FormatFloat(v, 'f', 6, 64)
}

// where builds the shared predicate list. Returns SQL starting with " WHERE ..."
// plus positional args beginning at the given index.
func (f SightingFilter) where(argIdx int) (string, []any) {
	var (
		clauses []string
		args    []any
	)
	next := func(v any) string {
		args = append(args, v)
		return fmt.Sprintf("$%d", argIdx+len(args)-1)
	}

	if !f.IncludeRejected {
		clauses = append(clauses, "s.status <> 'rejected'")
	}
	if f.BBox != nil {
		// The envelope is inlined as literals rather than bound as parameters.
		// With bind parameters PostgreSQL cannot fold ST_MakeEnvelope into a
		// constant, so after five executions it switches from a custom plan to a
		// generic one, abandons the GiST index on location and evaluates a
		// spheroid comparison per row. Measured on 2k sightings: 7 ms with
		// literals, 1.4 s once the generic plan kicked in.
		//
		// These are float64 values parsed upstream, never raw request text, so
		// formatting them into the statement cannot inject SQL.
		clauses = append(clauses, fmt.Sprintf(
			"s.location && ST_MakeEnvelope(%s, %s, %s, %s, 4326)::geography",
			coord(f.BBox.MinLon), coord(f.BBox.MinLat), coord(f.BBox.MaxLon), coord(f.BBox.MaxLat)))
	}
	if f.From != nil {
		clauses = append(clauses, "s.captured_at >= "+next(*f.From))
	}
	if f.To != nil {
		clauses = append(clauses, "s.captured_at <= "+next(*f.To))
	}
	if len(f.Status) > 0 {
		clauses = append(clauses, "s.status::text = ANY("+next(f.Status)+")")
	}
	if f.Condition != nil {
		clauses = append(clauses, "COALESCE(lv.label, pp.label) = "+next(*f.Condition))
	}
	if f.VerifiedOnly {
		clauses = append(clauses, "lv.decision IN ('confirmed','corrected')")
	}
	if f.SiteID != nil {
		clauses = append(clauses, "s.site_id = "+next(*f.SiteID))
	}
	if f.ContributorID != nil {
		clauses = append(clauses, "s.contributor_id = "+next(*f.ContributorID))
	}

	if len(clauses) == 0 {
		return "", args
	}
	return " WHERE " + strings.Join(clauses, " AND "), args
}

func (s *Store) ListSightings(ctx context.Context, f SightingFilter) ([]domain.Sighting, int, error) {
	whereSQL, args := f.where(1)

	var total int
	countSQL := effectiveLabelCTE + `
		SELECT count(*)
		FROM sighting s
		LEFT JOIN latest_verification lv ON lv.sighting_id = s.id
		LEFT JOIN photo_prediction pp    ON pp.sighting_id = s.id` + whereSQL
	if err := s.pool.QueryRow(ctx, countSQL, args...).Scan(&total); err != nil {
		return nil, 0, mapErr(err)
	}

	limit := f.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	// PostgreSQL rejects a negative OFFSET outright, so an unclamped value from
	// the query string reaches the driver and surfaces as a 500.
	offset := f.Offset
	if offset < 0 {
		offset = 0
	}
	listArgs := append(append([]any{}, args...), limit, offset)
	listSQL := effectiveLabelCTE + sightingSelect + whereSQL +
		fmt.Sprintf(" ORDER BY s.captured_at DESC, s.id DESC LIMIT $%d OFFSET $%d",
			len(args)+1, len(args)+2)

	rows, err := s.pool.Query(ctx, listSQL, listArgs...)
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

// MapPoint is either a single sighting or a grid-aggregated cluster.
type MapPoint struct {
	Lat         float64  `json:"lat"`
	Lon         float64  `json:"lon"`
	Count       int      `json:"count"`
	AvgSeverity *float64 `json:"avgSeverity,omitempty"`
	SightingID  *string  `json:"sightingId,omitempty"`
}

// MapPoints returns sightings for a viewport. Below the cluster zoom threshold
// results are aggregated in SQL via ST_SnapToGrid, which keeps the payload
// bounded regardless of dataset size (NFR3).
func (s *Store) MapPoints(ctx context.Context, f SightingFilter, zoom int) ([]MapPoint, error) {
	whereSQL, args := f.where(1)

	// Grid size in degrees, halving per zoom level; nil past the threshold.
	if zoom >= 11 {
		rows, err := s.pool.Query(ctx, effectiveLabelCTE+`
			SELECT ST_Y(s.location::geometry), ST_X(s.location::geometry),
			       pp.severity, s.id::text
			FROM sighting s
			LEFT JOIN latest_verification lv ON lv.sighting_id = s.id
			LEFT JOIN photo_prediction pp    ON pp.sighting_id = s.id`+whereSQL+
			` ORDER BY s.captured_at DESC LIMIT 5000`, args...)
		if err != nil {
			return nil, mapErr(err)
		}
		defer rows.Close()

		var out []MapPoint
		for rows.Next() {
			var p MapPoint
			if err := rows.Scan(&p.Lat, &p.Lon, &p.AvgSeverity, &p.SightingID); err != nil {
				return nil, err
			}
			p.Count = 1
			out = append(out, p)
		}
		return out, rows.Err()
	}

	// One web-mercator tile spans 360/2^zoom degrees; a forty-eighth of that is
	// the grid. An eighth was the original choice and it was too coarse: the
	// Maldives is a 9-degree ribbon barely 1 degree wide, so at national zoom the
	// whole country collapsed into about seven cells and the map became seven
	// enormous dots that said nothing about where bleaching was. At a
	// forty-eighth the same view returns 50-80 clusters, which traces the atoll
	// chain and still keeps the payload two orders of magnitude below the raw
	// data (NFR3). Anything finer stops being a cluster and starts being noise.
	cell := coord(7.5 / math.Pow(2, float64(zoom)))

	rows, err := s.pool.Query(ctx, effectiveLabelCTE+`
		SELECT ST_Y(ST_Centroid(ST_Collect(s.location::geometry))),
		       ST_X(ST_Centroid(ST_Collect(s.location::geometry))),
		       count(*), avg(pp.severity)
		FROM sighting s
		LEFT JOIN latest_verification lv ON lv.sighting_id = s.id
		LEFT JOIN photo_prediction pp    ON pp.sighting_id = s.id`+whereSQL+`
		GROUP BY ST_SnapToGrid(s.location::geometry, `+cell+`, `+cell+`)
		LIMIT 2000`, args...)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []MapPoint
	for rows.Next() {
		var p MapPoint
		if err := rows.Scan(&p.Lat, &p.Lon, &p.Count, &p.AvgSeverity); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// TrendBucket is one time slice of the condition trend (FR12).
type TrendBucket struct {
	Bucket      time.Time `json:"bucket"`
	Total       int       `json:"total"`
	Bleached    int       `json:"bleached"`
	Healthy     int       `json:"healthy"`
	AvgSeverity *float64  `json:"avgSeverity,omitempty"`
}

func (s *Store) Trends(ctx context.Context, f SightingFilter, bucket string) ([]TrendBucket, error) {
	unit := "month"
	switch bucket {
	case "day", "week", "month":
		unit = bucket
	}

	whereSQL, args := f.where(1)
	rows, err := s.pool.Query(ctx, effectiveLabelCTE+`
		SELECT date_trunc('`+unit+`', s.captured_at) AS bucket,
		       count(*),
		       count(*) FILTER (WHERE COALESCE(lv.label, pp.label) = 'bleached'),
		       count(*) FILTER (WHERE COALESCE(lv.label, pp.label) = 'healthy'),
		       avg(pp.severity)
		FROM sighting s
		LEFT JOIN latest_verification lv ON lv.sighting_id = s.id
		LEFT JOIN photo_prediction pp    ON pp.sighting_id = s.id`+whereSQL+`
		GROUP BY bucket
		ORDER BY bucket`, args...)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []TrendBucket
	for rows.Next() {
		var t TrendBucket
		if err := rows.Scan(&t.Bucket, &t.Total, &t.Bleached, &t.Healthy, &t.AvgSeverity); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *Store) SetSightingStatus(ctx context.Context, id uuid.UUID, status domain.SightingStatus) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE sighting SET status = $2, updated_at = now() WHERE id = $1`, id, status)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// AnonymiseContributor reassigns a user's sightings to the tombstone account,
// preserving the scientific record while severing the personal link (NFR15).
func (s *Store) AnonymiseContributor(ctx context.Context, userID uuid.UUID) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return mapErr(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit

	if _, err := tx.Exec(ctx,
		`UPDATE sighting SET contributor_id = $2 WHERE contributor_id = $1`,
		userID, domain.AnonymisedUserID); err != nil {
		return mapErr(err)
	}
	if _, err := tx.Exec(ctx,
		`UPDATE app_user
		 SET status = 'anonymised',
		     email = 'deleted+' || id::text || '@muraka.invalid',
		     display_name = 'Deleted user',
		     password_hash = 'x',
		     updated_at = now()
		 WHERE id = $1`, userID); err != nil {
		return mapErr(err)
	}
	if _, err := tx.Exec(ctx,
		`UPDATE refresh_token SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
		userID); err != nil {
		return mapErr(err)
	}
	return mapErr(tx.Commit(ctx))
}
