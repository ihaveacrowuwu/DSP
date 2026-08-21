package httpapi_test

import (
	"context"
	"net/http"
	"testing"

	"muraka/backend/internal/domain"
)

// FR1 asks for role-based access control, and its stated verification method is
// "API integration tests; attempted privilege-escalation tests". Until now the only
// evidence was one smoke check proving a contributor cannot read the review queue.
// One guard passing is not access control being enforced; the claim is about *every*
// protected route, so this walks the whole matrix.
//
// The table is written from the route table in api.go. When a route is added to a
// guarded group and not added here, that is the gap this test cannot see — so
// TestEveryGuardedRouteIsInTheMatrix keeps the two honest about each other.
type guardedRoute struct {
	method string
	path   string
	// minimum role that may reach the handler
	needs domain.Role
	// a body, where the handler decodes one before doing anything else
	body any
}

func researcherRoutes() []guardedRoute {
	return []guardedRoute{
		{http.MethodGet, "/v1/verifications/queue", domain.RoleResearcher, nil},
		{http.MethodGet, "/v1/map/points", domain.RoleResearcher, nil},
		{http.MethodGet, "/v1/trends", domain.RoleResearcher, nil},
		{http.MethodGet, "/v1/export/sightings.csv", domain.RoleResearcher, nil},
	}
}

func adminRoutes() []guardedRoute {
	return []guardedRoute{
		{http.MethodGet, "/v1/admin/users", domain.RoleAdmin, nil},
		{http.MethodGet, "/v1/admin/models", domain.RoleAdmin, nil},
		{http.MethodGet, "/v1/admin/queue", domain.RoleAdmin, nil},
		{http.MethodPost, "/v1/admin/sites", domain.RoleAdmin, map[string]any{
			"name": "Guarded Reef", "boundary": squarePolygon(4.0, 73.0, 0.05),
		}},
		{http.MethodPost, "/v1/admin/atolls", domain.RoleAdmin, map[string]any{
			"name": "Guarded Atoll", "code": "GRD", "lat": 4.0, "lon": 73.0,
		}},
	}
}

// TestContributorsCannotReachResearcherOrAdminRoutes is the privilege-escalation
// attempt FR1 names. A contributor presents a valid token — they are authenticated,
// which is what makes this an authorisation test rather than an authentication one.
func TestContributorsCannotReachResearcherOrAdminRoutes(t *testing.T) {
	h := newHarness(t)
	contributor := h.signUp(domain.RoleContributor)

	for _, route := range append(researcherRoutes(), adminRoutes()...) {
		t.Run(route.method+" "+route.path, func(t *testing.T) {
			status, body := h.do(route.method, route.path, contributor.Token, route.body)
			if status != http.StatusForbidden {
				t.Errorf("a contributor got %d, want 403 — body: %s", status, body)
			}
		})
	}
}

// TestResearchersCannotReachAdminRoutes closes the gap the smoke test leaves open:
// "not a contributor" is not the same as "an admin", and a role ladder that collapses
// at the top is the more dangerous of the two mistakes.
func TestResearchersCannotReachAdminRoutes(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)

	for _, route := range adminRoutes() {
		t.Run(route.method+" "+route.path, func(t *testing.T) {
			status, body := h.do(route.method, route.path, researcher.Token, route.body)
			if status != http.StatusForbidden {
				t.Errorf("a researcher got %d, want 403 — body: %s", status, body)
			}
		})
	}
}

// TestEachRoleReachesItsOwnRoutes is the other half. A guard that returns 403 to
// everybody would pass every test above while breaking the application entirely.
func TestEachRoleReachesItsOwnRoutes(t *testing.T) {
	h := newHarness(t)
	researcher := h.signUp(domain.RoleResearcher)
	admin := h.signUp(domain.RoleAdmin)

	for _, route := range researcherRoutes() {
		t.Run("researcher "+route.path, func(t *testing.T) {
			status, body := h.do(route.method, route.path, researcher.Token, route.body)
			if status == http.StatusForbidden || status == http.StatusUnauthorized {
				t.Errorf("a researcher was refused their own route: %d — body: %s", status, body)
			}
		})
	}
	// Admins inherit the researcher surface: domain.Role.CanVerify() is true for both,
	// so an admin being locked out of the review queue would be a real defect.
	for _, route := range researcherRoutes() {
		t.Run("admin "+route.path, func(t *testing.T) {
			status, body := h.do(route.method, route.path, admin.Token, route.body)
			if status == http.StatusForbidden || status == http.StatusUnauthorized {
				t.Errorf("an admin was refused a researcher route: %d — body: %s", status, body)
			}
		})
	}
	for _, route := range adminRoutes() {
		t.Run("admin "+route.path, func(t *testing.T) {
			status, body := h.do(route.method, route.path, admin.Token, route.body)
			if status == http.StatusForbidden || status == http.StatusUnauthorized {
				t.Errorf("an admin was refused their own route: %d — body: %s", status, body)
			}
		})
	}
}

// TestUnauthenticatedRequestsAreRefused covers the authentication boundary for every
// guarded route, including the contributor-level ones the role matrix does not touch.
func TestUnauthenticatedRequestsAreRefused(t *testing.T) {
	h := newHarness(t)

	routes := append(researcherRoutes(), adminRoutes()...)
	routes = append(routes,
		guardedRoute{http.MethodGet, "/v1/me", domain.RoleContributor, nil},
		guardedRoute{http.MethodDelete, "/v1/me", domain.RoleContributor, nil},
		guardedRoute{http.MethodGet, "/v1/sightings", domain.RoleContributor, nil},
		guardedRoute{http.MethodGet, "/v1/sites", domain.RoleContributor, nil},
	)

	for _, route := range routes {
		t.Run(route.method+" "+route.path, func(t *testing.T) {
			status, body := h.do(route.method, route.path, "", route.body)
			if status != http.StatusUnauthorized {
				t.Errorf("an anonymous caller got %d, want 401 — body: %s", status, body)
			}
		})
	}
}

// TestAGarbageOrForeignTokenIsRefused separates "no credentials" from "bad
// credentials". The unit tests already prove the parser rejects these; this proves the
// middleware actually consults it.
func TestAGarbageOrForeignTokenIsRefused(t *testing.T) {
	h := newHarness(t)

	// Signed with a different secret, correct shape, valid base64 — the case a
	// signature check exists to catch.
	const foreign = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
		"eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJyb2xlIjoiYWRtaW4ifQ." +
		"ZmFrZS1zaWduYXR1cmUtdGhhdC13aWxsLW5vdC12ZXJpZnk"

	for name, token := range map[string]string{
		"garbage":            "not-a-token",
		"foreign signature":  foreign,
		"empty bearer value": "",
		"structurally empty": "..",
	} {
		t.Run(name, func(t *testing.T) {
			status, body := h.do(http.MethodGet, "/v1/me", token, nil)
			if status != http.StatusUnauthorized {
				t.Errorf("got %d, want 401 — body: %s", status, body)
			}
		})
	}
}

// TestAContributorCannotVerifyEvenTheirOwnSighting is the escalation attempt most
// likely to be waved through by a guard that checks ownership instead of role.
func TestAContributorCannotVerifyEvenTheirOwnSighting(t *testing.T) {
	h := newHarness(t)
	contributor := h.signUp(domain.RoleContributor)
	id := h.newSighting(contributor, 4.1, 73.5)
	h.gradeSighting(id, "bleached", 0.9, 0.8)

	status, body := h.do(http.MethodPost, "/v1/sightings/"+id.String()+"/verification",
		contributor.Token, map[string]any{"decision": "confirmed", "label": "bleached"})
	if status != http.StatusForbidden {
		t.Fatalf("a contributor verified their own sighting: %d — body: %s", status, body)
	}
	if got := h.statusOf(id); got != "awaiting_verification" {
		t.Errorf("status changed to %q despite the refusal", got)
	}
}

// TestAContributorCannotReadAnotherContributorsSighting — role is not the only axis;
// two contributors are peers, and peers must not see each other's records.
func TestAContributorCannotReadAnotherContributorsSighting(t *testing.T) {
	h := newHarness(t)
	owner := h.signUp(domain.RoleContributor)
	stranger := h.signUp(domain.RoleContributor)

	id := h.newSighting(owner, 4.2, 73.4)

	status, body := h.do(http.MethodGet, "/v1/sightings/"+id.String(), stranger.Token, nil)
	if status != http.StatusNotFound && status != http.StatusForbidden {
		t.Fatalf("a stranger read another contributor's sighting: %d — body: %s", status, body)
	}
}

// TestReplayingAnotherContributorsIDIsRefused: the submission id is chosen by the
// client, so it is guessable, and idempotency means a repeated id is *accepted* by
// design. Together those would let one account overwrite another's sighting if the
// upsert did not check ownership — which is why UpsertSighting compares the
// contributor before returning.
func TestReplayingAnotherContributorsIDIsRefused(t *testing.T) {
	h := newHarness(t)
	owner := h.signUp(domain.RoleContributor)
	attacker := h.signUp(domain.RoleContributor)

	id := h.newSighting(owner, 4.3, 73.3)

	status, body := h.do(http.MethodPost, "/v1/sightings", attacker.Token, map[string]any{
		"id":             id.String(),
		"lat":            0.0,
		"lon":            0.0,
		"locationSource": "manual_pin",
		"capturedAt":     "2026-01-01T00:00:00Z",
	})
	if status < 400 {
		t.Fatalf("an attacker replayed another contributor's id and got %d — body: %s", status, body)
	}

	// Refusing the request is not enough on its own: the row must be untouched, and
	// the attacker's payload deliberately differs in every field that could be
	// clobbered by an upsert that wrote first and checked afterwards.
	var lat, lon float64
	var contributor string
	if err := h.pool.QueryRow(context.Background(), `
		SELECT ST_Y(location::geometry), ST_X(location::geometry), contributor_id::text
		FROM sighting WHERE id = $1`, id).Scan(&lat, &lon, &contributor); err != nil {
		t.Fatalf("read back the sighting: %v", err)
	}
	if contributor != owner.ID.String() {
		t.Errorf("ownership moved to %s", contributor)
	}
	if lat == 0 && lon == 0 {
		t.Error("the attacker's coordinates were written over the owner's")
	}
}
