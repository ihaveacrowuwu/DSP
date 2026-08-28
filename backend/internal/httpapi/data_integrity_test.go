package httpapi_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// squarePolygon builds a GeoJSON polygon centred on lat/lon with the given half-width
// in degrees, wound counter-clockwise. PostGIS accepts either winding for geography,
// but writing it consistently means a failing containment test is about containment.
func squarePolygon(lat, lon, half float64) json.RawMessage {
	return json.RawMessage(fmt.Sprintf(
		`{"type":"Polygon","coordinates":[[[%[1]f,%[3]f],[%[2]f,%[3]f],[%[2]f,%[4]f],[%[1]f,%[4]f],[%[1]f,%[3]f]]]}`,
		lon-half, lon+half, lat-half, lat+half))
}

// ---------------------------------------------------------------- FR4

// TestReplayingASubmissionCreatesExactlyOneRow is FR4 at the level it is implemented:
// `ON CONFLICT (id) DO NOTHING`. The smoke test already replays a submission, but only
// with the whole stack running, so a regression here is invisible to `make test`.
func TestReplayingASubmissionCreatesExactlyOneRow(t *testing.T) {
	h := newHarness(t)
	contributor := h.signUp(domain.RoleContributor)

	id := uuid.NewString()
	payload := map[string]any{
		"id":             id,
		"lat":            4.17,
		"lon":            73.51,
		"locationSource": "gps",
		"capturedAt":     "2026-05-01T09:30:00Z",
		"depthM":         6.5,
	}

	// Eight attempts, which is also the mobile outbox's give-up threshold - the
	// realistic worst case is a client that retried its whole backoff curve.
	statuses := make([]int, 0, 8)
	for i := 0; i < 8; i++ {
		status, body := h.do(http.MethodPost, "/v1/sightings", contributor.Token, payload)
		if status != http.StatusCreated && status != http.StatusOK {
			t.Fatalf("attempt %d: got %d - body: %s", i+1, status, body)
		}
		statuses = append(statuses, status)
	}

	var count int
	if err := h.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM sighting WHERE id = $1`, id).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 1 {
		t.Fatalf("eight identical submissions produced %d rows, want 1", count)
	}

	// The protocol tells clients to treat 201 and 200 identically, so this is not a
	// requirement - but the first attempt creating and the rest not is the behaviour
	// the mobile reconciliation logic was written against, and worth pinning.
	if statuses[0] != http.StatusCreated {
		t.Errorf("first attempt returned %d, expected 201", statuses[0])
	}
}

// TestDepthAndNoteSurviveAReplay guards the subtler half of idempotency: DO NOTHING
// must not blank the fields it declined to write.
func TestDepthAndNoteSurviveAReplay(t *testing.T) {
	h := newHarness(t)
	contributor := h.signUp(domain.RoleContributor)

	id := uuid.NewString()
	note := "Bleaching on the northern slope"
	payload := map[string]any{
		"id": id, "lat": 4.2, "lon": 73.2, "locationSource": "gps",
		"capturedAt": "2026-05-02T09:30:00Z", "depthM": 12.25, "note": note,
	}
	h.mustJSON(http.MethodPost, "/v1/sightings", contributor.Token, payload, http.StatusCreated, nil)
	h.do(http.MethodPost, "/v1/sightings", contributor.Token, payload)

	var depth *float64
	var storedNote *string
	if err := h.pool.QueryRow(context.Background(),
		`SELECT depth_m, note FROM sighting WHERE id = $1`, id).Scan(&depth, &storedNote); err != nil {
		t.Fatalf("read back: %v", err)
	}
	if depth == nil || *depth != 12.25 {
		t.Errorf("depth after replay = %v, want 12.25", depth)
	}
	if storedNote == nil || *storedNote != note {
		t.Errorf("note after replay = %v, want %q", storedNote, note)
	}
}

// ---------------------------------------------------------------- FR15

// TestASightingInsideASitePolygonIsAssignedToIt is FR15's stated verification method
// ("PostGIS containment tests") and the only test in the project that exercises the
// reason PostgreSQL+PostGIS is a hard constraint rather than a preference.
func TestASightingInsideASitePolygonIsAssignedToIt(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	contributor := h.signUp(domain.RoleContributor)

	var site struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}
	h.mustJSON(http.MethodPost, "/v1/admin/sites", admin.Token, map[string]any{
		"name":     "Containment Reef",
		"boundary": squarePolygon(4.00, 73.00, 0.10),
	}, http.StatusCreated, &site)
	if site.ID == "" {
		t.Fatal("site creation returned no id")
	}

	inside := h.newSighting(contributor, 4.02, 73.02)
	outside := h.newSighting(contributor, 5.50, 72.00)

	if got := h.siteOf(inside); got != site.ID {
		t.Errorf("a sighting inside the polygon was assigned to %q, want %q", got, site.ID)
	}
	if got := h.siteOf(outside); got != "" {
		t.Errorf("a sighting outside the polygon was assigned to %q, want none", got)
	}
}

// TestCreatingASiteBackfillsSightingsAlreadyInside covers the other direction, which
// is a separate SQL statement (an UPDATE ... ST_Covers inside the create transaction)
// and therefore a separate way to be wrong. Sightings usually arrive before an admin
// has drawn the site.
func TestCreatingASiteBackfillsSightingsAlreadyInside(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	contributor := h.signUp(domain.RoleContributor)

	early := h.newSighting(contributor, 1.05, 73.05)
	elsewhere := h.newSighting(contributor, 6.00, 71.00)
	if got := h.siteOf(early); got != "" {
		t.Fatalf("precondition failed: already assigned to %q", got)
	}

	var site struct {
		ID string `json:"id"`
	}
	h.mustJSON(http.MethodPost, "/v1/admin/sites", admin.Token, map[string]any{
		"name":     "Backfill Reef",
		"boundary": squarePolygon(1.00, 73.00, 0.10),
	}, http.StatusCreated, &site)

	if got := h.siteOf(early); got != site.ID {
		t.Errorf("an existing sighting inside the new polygon was not backfilled: got %q, want %q", got, site.ID)
	}
	if got := h.siteOf(elsewhere); got != "" {
		t.Errorf("a sighting outside the polygon was backfilled to %q", got)
	}
}

// ---------------------------------------------------------------- FR6

// TestRejectingASightingIsRecordedWithItsReasonAndAuthor covers the half of FR6 that
// had no evidence: the smoke test confirms and corrects, but never rejects, and
// nothing asserted that the audit trail records *who* decided *what* - which is the
// reason FR6 says "audit-logged".
func TestRejectingASightingIsRecordedWithItsReasonAndAuthor(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)
	contributor := h.signUp(domain.RoleContributor)

	id := h.newSighting(contributor, 4.4, 73.4)
	h.gradeSighting(id, "healthy", 0.55, 0.1)

	// `rejectReason` is an enum - blurry, not_coral, duplicate, spam, other - not free
	// text. Sending a sentence is a 422.
	reason := "blurry"
	h.mustJSON(http.MethodPost, "/v1/sightings/"+id.String()+"/verification", researcher.Token,
		map[string]any{"decision": "rejected", "rejectReason": reason},
		http.StatusCreated, nil)

	if got := h.statusOf(id); got != "rejected" {
		t.Errorf("status = %q, want rejected", got)
	}

	var verifier, decision string
	var storedReason *string
	if err := h.pool.QueryRow(context.Background(), `
		SELECT verifier_id::text, decision::text, reject_reason
		FROM verification WHERE sighting_id = $1
		ORDER BY created_at DESC LIMIT 1`, id).Scan(&verifier, &decision, &storedReason); err != nil {
		t.Fatalf("read verification: %v", err)
	}
	if verifier != researcher.ID.String() {
		t.Errorf("verifier recorded as %s, want %s", verifier, researcher.ID)
	}
	if decision != "rejected" {
		t.Errorf("decision recorded as %q", decision)
	}
	if storedReason == nil || *storedReason != reason {
		t.Errorf("reject reason recorded as %v, want %q", storedReason, reason)
	}
}

// TestARejectionWithoutAReasonIsRefused - a rejection with no reason is unauditable,
// which defeats the point of recording it.
func TestARejectionWithoutAReasonIsRefused(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)
	contributor := h.signUp(domain.RoleContributor)

	id := h.newSighting(contributor, 4.5, 73.45)
	h.gradeSighting(id, "healthy", 0.6, 0.2)

	status, body := h.do(http.MethodPost, "/v1/sightings/"+id.String()+"/verification",
		researcher.Token, map[string]any{"decision": "rejected"})
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("got %d, want 422 - body: %s", status, body)
	}
	if got := h.statusOf(id); got == "rejected" {
		t.Error("the sighting was rejected despite the refused request")
	}
}

// ---------------------------------------------------------------- FR11

// TestRejectedSightingsAreExcludedFromMapTrendsAndExport is FR11 end-to-end. The unit
// tests prove the SQL builder emits the right predicate; nothing proved the predicate
// reaches all three read paths, and "excluded from all maps, trends and exports" is a
// claim about every one of them.
func TestRejectedSightingsAreExcludedFromMapTrendsAndExport(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)
	contributor := h.signUp(domain.RoleContributor)

	kept := h.newSighting(contributor, 4.61, 73.61)
	h.gradeSighting(kept, "healthy", 0.9, 0.1)
	dropped := h.newSighting(contributor, 4.62, 73.62)
	h.gradeSighting(dropped, "bleached", 0.9, 0.9)

	h.mustJSON(http.MethodPost, "/v1/sightings/"+dropped.String()+"/verification", researcher.Token,
		map[string]any{"decision": "rejected", "rejectReason": "duplicate"},
		http.StatusCreated, nil)

	// CSV is the easiest to assert precisely: the id either appears or it does not.
	status, csv := h.do(http.MethodGet, "/v1/export/sightings.csv", researcher.Token, nil)
	if status != http.StatusOK {
		t.Fatalf("export: %d", status)
	}
	if !strings.Contains(string(csv), kept.String()) {
		t.Error("the surviving sighting is missing from the export")
	}
	if strings.Contains(string(csv), dropped.String()) {
		t.Error("a rejected sighting appears in the export (FR11)")
	}

	// The map returns clusters at low zoom and points at high zoom; asking for a tight
	// bbox at high zoom is what makes individual ids observable.
	status, points := h.do(http.MethodGet,
		"/v1/map/points?zoom=14&bbox=73.5,4.5,73.7,4.7", researcher.Token, nil)
	if status != http.StatusOK {
		t.Fatalf("map: %d - %s", status, points)
	}
	if strings.Contains(string(points), dropped.String()) {
		t.Error("a rejected sighting appears in the map response (FR11)")
	}

	// Trends is aggregated, so the id is not visible - count instead, which is the
	// only observable that can carry a rejected row.
	before := h.trendTotal(researcher.Token)
	extra := h.newSighting(contributor, 4.63, 73.63)
	h.gradeSighting(extra, "bleached", 0.9, 0.9)
	h.mustJSON(http.MethodPost, "/v1/sightings/"+extra.String()+"/verification", researcher.Token,
		map[string]any{"decision": "rejected", "rejectReason": "duplicate"},
		http.StatusCreated, nil)
	if after := h.trendTotal(researcher.Token); after != before {
		t.Errorf("trends total moved from %d to %d after adding a rejected sighting (FR11)", before, after)
	}
}

// ---------------------------------------------------------------- FR14

// TestTheVerificationQueuePutsLowConfidenceFirst is FR14, whose stated verification
// method is "API ordering test" and which had no evidence at all.
func TestTheVerificationQueuePutsLowConfidenceFirst(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)
	contributor := h.signUp(domain.RoleContributor)

	// Inserted in deliberately the wrong order, so passing cannot be an accident of
	// insertion order matching the expected order.
	type seeded struct {
		id         uuid.UUID
		confidence float64
	}
	seeds := []seeded{
		{h.newSighting(contributor, 4.71, 73.71), 0.95},
		{h.newSighting(contributor, 4.72, 73.72), 0.30},
		{h.newSighting(contributor, 4.73, 73.73), 0.62},
	}
	for _, s := range seeds {
		h.gradeSighting(s.id, "healthy", s.confidence, 0.2)
	}

	var queue struct {
		Items []struct {
			ID string `json:"id"`
		} `json:"items"`
	}
	h.mustJSON(http.MethodGet, "/v1/verifications/queue", researcher.Token, nil, http.StatusOK, &queue)
	if len(queue.Items) < 3 {
		t.Fatalf("queue returned %d items, want at least 3", len(queue.Items))
	}

	position := map[string]int{}
	for i, item := range queue.Items {
		position[item.ID] = i
	}
	low, mid, high := position[seeds[1].id.String()], position[seeds[2].id.String()], position[seeds[0].id.String()]
	if !(low < mid && mid < high) {
		t.Errorf("queue order by confidence is wrong: 0.30 at %d, 0.62 at %d, 0.95 at %d", low, mid, high)
	}
}

// ---------------------------------------------------------------- NFR15

// TestDeletingAnAccountKeepsTheScienceAndDropsThePerson is NFR15. Both halves matter
// and they pull in opposite directions, which is exactly why it needs a test: the
// sighting must survive, and every personal field must not.
func TestDeletingAnAccountKeepsTheScienceAndDropsThePerson(t *testing.T) {
	h := newHarness(t)
	contributor := h.signUp(domain.RoleContributor)
	id := h.newSighting(contributor, 4.8, 73.8)

	h.mustJSON(http.MethodDelete, "/v1/me", contributor.Token, nil, http.StatusNoContent, nil)

	ctx := context.Background()

	// The scientific record survives, reassigned to the tombstone account.
	var owner string
	if err := h.pool.QueryRow(ctx,
		`SELECT contributor_id::text FROM sighting WHERE id = $1`, id).Scan(&owner); err != nil {
		t.Fatalf("the sighting did not survive deletion: %v", err)
	}
	if owner != domain.AnonymisedUserID.String() {
		t.Errorf("sighting owner is %s, want the tombstone account %s", owner, domain.AnonymisedUserID)
	}

	// The person does not.
	var email, displayName, status string
	if err := h.pool.QueryRow(ctx,
		`SELECT email, display_name, status::text FROM app_user WHERE id = $1`,
		contributor.ID).Scan(&email, &displayName, &status); err != nil {
		t.Fatalf("read the anonymised user: %v", err)
	}
	if status != "anonymised" {
		t.Errorf("status = %q, want anonymised", status)
	}
	if strings.Contains(email, "@muraka.test") || email == contributor.Email {
		t.Errorf("the original email survived: %q", email)
	}
	if displayName != "Deleted user" {
		t.Errorf("display name = %q, want \"Deleted user\"", displayName)
	}

	// And the credentials no longer work, which is the part a contributor would
	// actually notice if it were wrong.
	code, _ := h.do(http.MethodPost, "/v1/auth/login", "", map[string]any{
		"email": contributor.Email, "password": "muraka-integration-2026",
	})
	if code < 400 {
		t.Errorf("login with the deleted account's credentials returned %d", code)
	}
}

// TestAnInvalidRejectReasonSaysSoRatherThanClaimingItIsMissing pins the fix for a
// misleading 422. A supplied-but-invalid reason used to report "is required when
// rejecting", sending the caller to look for a field they had already sent.
func TestAnInvalidRejectReasonSaysSoRatherThanClaimingItIsMissing(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)
	contributor := h.signUp(domain.RoleContributor)

	id := h.newSighting(contributor, 4.9, 73.9)
	h.gradeSighting(id, "healthy", 0.5, 0.2)

	status, body := h.do(http.MethodPost, "/v1/sightings/"+id.String()+"/verification",
		researcher.Token, map[string]any{
			"decision": "rejected", "rejectReason": "photograph is out of focus",
		})
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("got %d, want 422", status)
	}
	if strings.Contains(string(body), "is required") {
		t.Errorf("a supplied-but-invalid reason was reported as missing: %s", body)
	}
	if !strings.Contains(string(body), "must be") {
		t.Errorf("the error does not say what the valid values are: %s", body)
	}
}

// ---------------------------------------------------------------- pagination bounds

// TestANegativeOffsetIsClampedRatherThanReturningAServerError covers a defect found
// by probing the running API rather than by reading code. `limit` was defended in
// both stores - 0, -5, 99999 and "abc" all fall back to the default - but `offset`
// was passed through untouched, and PostgreSQL rejects a negative OFFSET outright
// ("OFFSET must not be negative", SQLSTATE 2201X). The driver error surfaced as a
// bare 500 on two endpoints that a dashboard paginating backwards past zero could
// reach on its own. Clamping matches how limit already behaves.
func TestANegativeOffsetIsClampedRatherThanReturningAServerError(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)

	for _, path := range []string{
		"/v1/sightings?offset=-1",
		"/v1/sightings?offset=-999999&limit=5",
		"/v1/verifications/queue?offset=-1",
	} {
		status, body := h.do(http.MethodGet, path, researcher.Token, nil)
		if status != http.StatusOK {
			t.Errorf("GET %s: got %d, want 200 - body: %s", path, status, body)
		}
	}
}

// TestALargeOffsetReturnsAnEmptyPageNotAnError is the other end of the same range:
// paging past the last row is a normal thing for a client to do and must not fault.
func TestALargeOffsetReturnsAnEmptyPageNotAnError(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)

	var page struct {
		Items []json.RawMessage `json:"items"`
	}
	h.mustJSON(http.MethodGet, "/v1/sightings?offset=100000", researcher.Token, nil, http.StatusOK, &page)
	if len(page.Items) != 0 {
		t.Errorf("offset past the end returned %d items, want 0", len(page.Items))
	}
}
