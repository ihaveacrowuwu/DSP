package store

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// The filter builder decides what every list, map, trend and export query sees,
// so its SQL is worth asserting on directly. These tests need no database.

func TestFilterExcludesRejectedByDefault(t *testing.T) {
	// FR11: rejected sightings must never reach a map, trend or export.
	sql, args := SightingFilter{}.where(1)

	if !strings.Contains(sql, "s.status <> 'rejected'") {
		t.Errorf("expected rejected sightings to be excluded, got %q", sql)
	}
	if len(args) != 0 {
		t.Errorf("expected no bound arguments, got %d", len(args))
	}
}

func TestFilterCanIncludeRejectedExplicitly(t *testing.T) {
	sql, _ := SightingFilter{IncludeRejected: true}.where(1)

	if strings.Contains(sql, "rejected") {
		t.Errorf("IncludeRejected should drop the status guard, got %q", sql)
	}
	if sql != "" {
		t.Errorf("expected an empty predicate, got %q", sql)
	}
}

func TestFilterInlinesBoundingBoxAsLiterals(t *testing.T) {
	// Bind parameters here would stop PostgreSQL folding ST_MakeEnvelope into a
	// constant, costing the GiST index after five executions. Coordinates are
	// parsed floats, so inlining them is safe.
	sql, args := SightingFilter{
		BBox: &BBox{MinLon: 71.8, MinLat: -1.2, MaxLon: 74.2, MaxLat: 7.4},
	}.where(1)

	if !strings.Contains(sql, "ST_MakeEnvelope(71.800000, -1.200000, 74.200000, 7.400000, 4326)") {
		t.Errorf("expected inlined envelope literals, got %q", sql)
	}
	if !strings.Contains(sql, "s.location &&") {
		t.Errorf("expected the indexable overlap operator, got %q", sql)
	}
	if len(args) != 0 {
		t.Errorf("bounding box must not bind parameters, got %d", len(args))
	}
}

func TestFilterBindsValuesFromTheGivenIndex(t *testing.T) {
	// Callers append their own arguments (limit/offset), so placeholder numbering
	// has to start where they ask.
	from := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	sql, args := SightingFilter{From: &from}.where(5)

	if !strings.Contains(sql, "s.captured_at >= $5") {
		t.Errorf("expected the placeholder to start at $5, got %q", sql)
	}
	if len(args) != 1 || args[0] != from {
		t.Errorf("expected the timestamp to be bound, got %v", args)
	}
}

func TestFilterNumbersMultiplePlaceholdersInOrder(t *testing.T) {
	from := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 6, 30, 0, 0, 0, 0, time.UTC)
	siteID := uuid.New()
	condition := domain.ConditionBleached

	sql, args := SightingFilter{
		From:      &from,
		To:        &to,
		Condition: &condition,
		SiteID:    &siteID,
	}.where(1)

	for _, want := range []string{
		"s.captured_at >= $1",
		"s.captured_at <= $2",
		"COALESCE(lv.label, pp.label) = $3",
		"s.site_id = $4",
	} {
		if !strings.Contains(sql, want) {
			t.Errorf("missing %q in %q", want, sql)
		}
	}
	if len(args) != 4 {
		t.Fatalf("expected 4 bound arguments, got %d", len(args))
	}
	if args[2] != condition {
		t.Errorf("third argument should be the condition, got %v", args[2])
	}
}

func TestFilterVerifiedOnlyMatchesExpertDecisions(t *testing.T) {
	// "Verified" means an expert confirmed or corrected it; a rejection is not
	// a verification of condition.
	sql, _ := SightingFilter{VerifiedOnly: true}.where(1)

	if !strings.Contains(sql, "lv.decision IN ('confirmed','corrected')") {
		t.Errorf("expected confirmed/corrected only, got %q", sql)
	}
}

func TestFilterStatusUsesArrayMatch(t *testing.T) {
	sql, args := SightingFilter{Status: []string{"processing", "verified"}}.where(1)

	if !strings.Contains(sql, "s.status::text = ANY($1)") {
		t.Errorf("expected an array match, got %q", sql)
	}
	statuses, ok := args[0].([]string)
	if !ok || len(statuses) != 2 {
		t.Errorf("expected the status slice to be bound, got %v", args[0])
	}
}

func TestFilterJoinsAllPredicatesWithAnd(t *testing.T) {
	contributor := uuid.New()
	sql, _ := SightingFilter{
		ContributorID: &contributor,
		VerifiedOnly:  true,
	}.where(1)

	if !strings.HasPrefix(sql, " WHERE ") {
		t.Errorf("predicate should begin with WHERE, got %q", sql)
	}
	if strings.Count(sql, " AND ") != 2 {
		t.Errorf("expected three predicates joined by AND, got %q", sql)
	}
}

func TestCoordRendersFixedNotation(t *testing.T) {
	// PostGIS will not parse exponent notation, which Go would otherwise use for
	// very small values such as a near-zero longitude.
	cases := map[float64]string{
		73.5093:     "73.509300",
		-1.2:        "-1.200000",
		0.0000001:   "0.000000",
		0.087890625: "0.087891",
	}

	for input, want := range cases {
		if got := coord(input); got != want {
			t.Errorf("coord(%v) = %q, want %q", input, got, want)
		}
		if strings.ContainsAny(coord(input), "eE") {
			t.Errorf("coord(%v) produced exponent notation: %q", input, coord(input))
		}
	}
}
