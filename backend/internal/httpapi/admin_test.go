package httpapi_test

import (
	"context"
	"net/http"
	"testing"

	"muraka/backend/internal/domain"
)

// FR10 - "An administrator shall be able to manage user roles and ban users, manage
// atoll/site reference data, and register/activate ML model versions." Reaching the
// endpoint is covered by the RBAC matrix; this covers the endpoints doing what they say.
//
// The ban case matters most. A ban that returns 200 and changes a column but does not
// actually stop the user is worse than no ban at all, because the administrator has
// been told the problem is dealt with.

func TestPromotingAUserTakesEffectImmediately(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	user := h.signUp(domain.RoleContributor)

	// Before: the review queue is closed to them.
	if status, _ := h.do(http.MethodGet, "/v1/verifications/queue", user.Token, nil); status != http.StatusForbidden {
		t.Fatalf("precondition: contributor got %d on the queue, want 403", status)
	}

	h.mustJSON(http.MethodPut, "/v1/admin/users/"+user.ID.String()+"/role", admin.Token,
		map[string]any{"role": "researcher"}, http.StatusNoContent, nil)

	// The **same** token now works. requireAuth reads the role from the database
	// rather than from the JWT claim, so a change of role does not wait for the token
	// to expire. Before that, this assertion was inverted: the old token kept the old
	// role for up to fifteen minutes.
	if status, body := h.do(http.MethodGet, "/v1/verifications/queue", user.Token, nil); status != http.StatusOK {
		t.Errorf("a promoted user was still refused with their existing token: %d - body: %s", status, body)
	}

	// And a fresh login is equally fine, which is the case that always worked.
	fresh := h.login(user.Email, "muraka-integration-2026")
	if status, body := h.do(http.MethodGet, "/v1/verifications/queue", fresh, nil); status != http.StatusOK {
		t.Errorf("after re-login the promoted user got %d, want 200 - body: %s", status, body)
	}
}

func TestDemotingAUserRemovesTheirAccess(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	user := h.signUp(domain.RoleResearcher)

	if status, _ := h.do(http.MethodGet, "/v1/verifications/queue", user.Token, nil); status != http.StatusOK {
		t.Fatalf("precondition: researcher could not read the queue")
	}

	h.mustJSON(http.MethodPut, "/v1/admin/users/"+user.ID.String()+"/role", admin.Token,
		map[string]any{"role": "contributor"}, http.StatusNoContent, nil)

	// The dangerous direction: a demotion that waits for a token to expire leaves the
	// user holding privileges an administrator has just taken away.
	if status, body := h.do(http.MethodGet, "/v1/verifications/queue", user.Token, nil); status != http.StatusForbidden {
		t.Errorf("a demoted user kept access with their existing token: %d - body: %s", status, body)
	}
	fresh := h.login(user.Email, "muraka-integration-2026")
	if status, body := h.do(http.MethodGet, "/v1/verifications/queue", fresh, nil); status != http.StatusForbidden {
		t.Errorf("a demoted user still reached the queue after re-login: %d - body: %s", status, body)
	}
}

// TestBanningAUserStopsThemSigningIn is the half of a ban that a user would notice.
func TestBanningAUserStopsThemSigningIn(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	user := h.signUp(domain.RoleContributor)

	h.mustJSON(http.MethodPut, "/v1/admin/users/"+user.ID.String()+"/status", admin.Token,
		map[string]any{"status": "banned"}, http.StatusNoContent, nil)

	var status string
	if err := h.pool.QueryRow(context.Background(),
		`SELECT status::text FROM app_user WHERE id = $1`, user.ID).Scan(&status); err != nil {
		t.Fatalf("read status: %v", err)
	}
	if status != "banned" {
		t.Fatalf("status = %q, want banned", status)
	}

	code, body := h.do(http.MethodPost, "/v1/auth/login", "", map[string]any{
		"email": user.Email, "password": "muraka-integration-2026",
	})
	if code < 400 {
		t.Errorf("a banned user signed in successfully: %d - body: %s", code, body)
	}
}

// TestBanningAUserStopsTheirExistingSession is the half that is easy to miss. A user
// who is already signed in holds a valid token, and revoking a session is a different
// mechanism from refusing a login.
func TestBanningAUserStopsTheirExistingSession(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	user := h.signUp(domain.RoleContributor)

	if status, _ := h.do(http.MethodGet, "/v1/me", user.Token, nil); status != http.StatusOK {
		t.Fatalf("precondition: the user could not read /v1/me")
	}

	h.mustJSON(http.MethodPut, "/v1/admin/users/"+user.ID.String()+"/status", admin.Token,
		map[string]any{"status": "banned"}, http.StatusNoContent, nil)

	status, body := h.do(http.MethodGet, "/v1/me", user.Token, nil)
	if status < 400 {
		t.Errorf("a banned user's existing token still worked: %d - body: %s", status, body)
	}
}

// TestAnAdminCannotBanThemselves - locking the last administrator out of the system is
// unrecoverable without database access.
func TestAnAdminCannotBanThemselves(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)

	status, body := h.do(http.MethodPut, "/v1/admin/users/"+admin.ID.String()+"/status",
		admin.Token, map[string]any{"status": "banned"})
	if status < 400 {
		t.Errorf("an admin banned themselves: %d - body: %s", status, body)
	}
}

// TestActivatingAModelVersionLeavesExactlyOneActive. The schema enforces this with a
// partial unique index, so a naive "UPDATE ... SET is_active = true" would violate it
// unless the previous active row is cleared in the same transaction.
func TestActivatingAModelVersionLeavesExactlyOneActive(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)
	ctx := context.Background()

	// A second version to switch to. Registering one is FR10's "register" half; there
	// is no endpoint for it, so it is inserted the way the training track will.
	if _, err := h.pool.Exec(ctx, `
		INSERT INTO model_version (version, task, is_active, notes)
		VALUES ('test-1.0.0', 'patch_classification', false, 'integration test')`); err != nil {
		t.Fatalf("register a model version: %v", err)
	}

	// 204 per docs/openapi.yaml - these mutations return no body.
	h.mustJSON(http.MethodPost, "/v1/admin/models/test-1.0.0/activate", admin.Token, nil,
		http.StatusNoContent, nil)

	var active int
	if err := h.pool.QueryRow(ctx,
		`SELECT count(*) FROM model_version WHERE is_active`).Scan(&active); err != nil {
		t.Fatalf("count active: %v", err)
	}
	if active != 1 {
		t.Errorf("%d model versions are active, want exactly 1", active)
	}

	var version string
	if err := h.pool.QueryRow(ctx,
		`SELECT version FROM model_version WHERE is_active`).Scan(&version); err != nil {
		t.Fatalf("read active: %v", err)
	}
	if version != "test-1.0.0" {
		t.Errorf("active version is %q, want test-1.0.0", version)
	}
}

// TestActivatingAnUnknownModelVersionIs404 - a typo must not silently deactivate the
// model that is currently serving.
func TestActivatingAnUnknownModelVersionIs404(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)

	status, _ := h.do(http.MethodPost, "/v1/admin/models/does-not-exist/activate", admin.Token, nil)
	if status != http.StatusNotFound {
		t.Errorf("got %d, want 404", status)
	}

	var active int
	if err := h.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM model_version WHERE is_active`).Scan(&active); err != nil {
		t.Fatalf("count active: %v", err)
	}
	if active != 1 {
		t.Errorf("a failed activation left %d models active, want 1", active)
	}
}

// TestAtollsAreUpsertedRatherThanDuplicated - FR10's reference-data half. The endpoint
// is named "upsert", and the table has a unique constraint on both name and code, so
// re-posting the same atoll must update rather than 409 or duplicate.
func TestAtollsAreUpsertedRatherThanDuplicated(t *testing.T) {
	h := newHarness(t)
	admin := h.signUp(domain.RoleAdmin)

	payload := map[string]any{"name": "Test Atoll", "code": "TST", "lat": 4.0, "lon": 73.0}
	h.mustJSON(http.MethodPost, "/v1/admin/atolls", admin.Token, payload, http.StatusOK, nil)

	moved := map[string]any{"name": "Test Atoll", "code": "TST", "lat": 4.5, "lon": 73.5}
	h.mustJSON(http.MethodPost, "/v1/admin/atolls", admin.Token, moved, http.StatusOK, nil)

	var count int
	var lat float64
	if err := h.pool.QueryRow(context.Background(), `
		SELECT count(*), max(ST_Y(centroid::geometry))
		FROM atoll WHERE code = 'TST'`).Scan(&count, &lat); err != nil {
		t.Fatalf("read atoll: %v", err)
	}
	if count != 1 {
		t.Errorf("upserting twice produced %d rows, want 1", count)
	}
	if lat != 4.5 {
		t.Errorf("centroid latitude = %v, want the updated 4.5", lat)
	}
}
