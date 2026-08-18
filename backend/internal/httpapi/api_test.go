package httpapi

import (
	"net/http/httptest"
	"testing"

	"muraka/backend/internal/domain"
)

func TestParseBBoxAcceptsLonLatOrder(t *testing.T) {
	// The parameter follows the GeoJSON/OGC order: minLon,minLat,maxLon,maxLat.
	bbox := parseBBox("71.8,-1.2,74.2,7.4")
	if bbox == nil {
		t.Fatal("expected a bounding box")
	}

	if bbox.MinLon != 71.8 || bbox.MinLat != -1.2 || bbox.MaxLon != 74.2 || bbox.MaxLat != 7.4 {
		t.Errorf("parsed %+v", *bbox)
	}
}

func TestParseBBoxToleratesWhitespace(t *testing.T) {
	if parseBBox(" 71.8 , -1.2 , 74.2 , 7.4 ") == nil {
		t.Error("expected padded values to parse")
	}
}

func TestParseBBoxRejectsMalformedInput(t *testing.T) {
	for _, raw := range []string{
		"",
		"71.8,-1.2,74.2",         // too few
		"71.8,-1.2,74.2,7.4,9.9", // too many
		"not,a,bounding,box",     // non-numeric
		"71.8;-1.2;74.2;7.4",     // wrong separator
	} {
		if bbox := parseBBox(raw); bbox != nil {
			t.Errorf("parseBBox(%q) should be nil, got %+v", raw, *bbox)
		}
	}
}

func TestSightingFilterFromQueryReadsEveryFilter(t *testing.T) {
	request := httptest.NewRequest("GET",
		"/v1/sightings?bbox=71,1,74,7&from=2026-01-01&to=2026-06-30&condition=bleached&verified=true&limit=10&offset=20",
		nil)

	filter := sightingFilterFromQuery(request)

	if filter.BBox == nil {
		t.Error("bbox should be parsed")
	}
	if filter.From == nil || filter.From.Year() != 2026 {
		t.Errorf("from = %v", filter.From)
	}
	if filter.To == nil || filter.To.Month() != 6 {
		t.Errorf("to = %v", filter.To)
	}
	if filter.Condition == nil || *filter.Condition != domain.ConditionBleached {
		t.Errorf("condition = %v", filter.Condition)
	}
	if !filter.VerifiedOnly {
		t.Error("verified=true should set VerifiedOnly")
	}
	if filter.Limit != 10 || filter.Offset != 20 {
		t.Errorf("limit/offset = %d/%d", filter.Limit, filter.Offset)
	}
}

func TestSightingFilterFromQueryIgnoresUnknownCondition(t *testing.T) {
	// An unrecognised value must widen the query, never fail it or match nothing.
	request := httptest.NewRequest("GET", "/v1/sightings?condition=slightly-off-colour", nil)

	if filter := sightingFilterFromQuery(request); filter.Condition != nil {
		t.Errorf("expected no condition filter, got %v", *filter.Condition)
	}
}

func TestSightingFilterFromQueryDefaultsPagination(t *testing.T) {
	filter := sightingFilterFromQuery(httptest.NewRequest("GET", "/v1/sightings", nil))

	if filter.Limit != 50 || filter.Offset != 0 {
		t.Errorf("defaults = %d/%d, want 50/0", filter.Limit, filter.Offset)
	}
	if filter.VerifiedOnly {
		t.Error("VerifiedOnly should default to false")
	}
}

func TestQueryTimeAcceptsDateAndRFC3339(t *testing.T) {
	dateOnly := queryTime(httptest.NewRequest("GET", "/?from=2026-03-15", nil), "from")
	if dateOnly == nil || dateOnly.Day() != 15 {
		t.Errorf("date-only form failed: %v", dateOnly)
	}

	full := queryTime(httptest.NewRequest("GET", "/?from=2026-03-15T08:30:00Z", nil), "from")
	if full == nil || full.Hour() != 8 {
		t.Errorf("RFC3339 form failed: %v", full)
	}

	if queryTime(httptest.NewRequest("GET", "/?from=yesterday", nil), "from") != nil {
		t.Error("an unparseable date should be ignored")
	}
}

func TestQueryUUIDIgnoresInvalidValues(t *testing.T) {
	if queryUUID(httptest.NewRequest("GET", "/?site=not-a-uuid", nil), "site") != nil {
		t.Error("an invalid UUID should be ignored, not fabricated")
	}
	if queryUUID(httptest.NewRequest("GET", "/?site=", nil), "site") != nil {
		t.Error("an empty value should be ignored")
	}

	valid := "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
	got := queryUUID(httptest.NewRequest("GET", "/?site="+valid, nil), "site")
	if got == nil || got.String() != valid {
		t.Errorf("expected %s, got %v", valid, got)
	}
}

func TestRoleCapabilities(t *testing.T) {
	// Route guards depend on this, so the mapping is asserted explicitly.
	if domain.RoleContributor.CanVerify() {
		t.Error("contributors must not be able to verify")
	}
	if !domain.RoleResearcher.CanVerify() || !domain.RoleAdmin.CanVerify() {
		t.Error("researchers and admins must be able to verify")
	}
	if domain.Role("moderator").Valid() {
		t.Error("unknown roles must be invalid")
	}
}

func TestParseOptionalUUID(t *testing.T) {
	valid := "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
	if got := parseOptionalUUID(&valid); got == nil || got.String() != valid {
		t.Errorf("valid UUID should parse, got %v", got)
	}

	blank := "   "
	if parseOptionalUUID(&blank) != nil {
		t.Error("a blank value should be treated as absent")
	}
	if parseOptionalUUID(nil) != nil {
		t.Error("nil should be treated as absent")
	}
}
