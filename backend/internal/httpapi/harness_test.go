// Integration-test harness: a real API on a real PostgreSQL+PostGIS database.
//
// The other 40 Go tests are pure unit tests, which means the things this project
// actually promises - that a contributor cannot verify a sighting, that a replayed
// submission creates one row, that PostGIS assigns a site, that deleting an account
// keeps the science and drops the person - had no automated evidence at all. Those
// claims cannot be tested without a database, because the database is where most of
// them are implemented: `ST_Covers` containment, `ON CONFLICT DO NOTHING` idempotency
// and the `ORDER BY confidence ASC NULLS FIRST` queue order are SQL, not Go.
//
// Each test gets its **own** database, created and dropped around it. That is slower
// than sharing one and truncating, and it is worth it: a shared database makes tests
// order-dependent, and an order-dependent integration suite is one that passes alone
// and fails in CI for reasons nobody can reproduce.
//
// The suite **skips** when PostgreSQL is unreachable rather than failing. A red suite
// on a machine with no Docker running tells nobody anything - the same rule the iOS
// integration suites follow.
package httpapi_test

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"muraka/backend/internal/auth"
	"muraka/backend/internal/config"
	"muraka/backend/internal/database"
	"muraka/backend/internal/domain"
	"muraka/backend/internal/httpapi"
	"muraka/backend/internal/mlclient"
	"muraka/backend/internal/storage"
	"muraka/backend/internal/store"
)

// The compose stack publishes PostgreSQL on 5433 - 5432 was taken on the development
// machine. Overridable so this suite can run against any reachable instance.
const defaultAdminURL = "postgres://muraka:muraka@localhost:5433/muraka?sslmode=disable"

func adminURL() string {
	if v := os.Getenv("MURAKA_TEST_DATABASE_URL"); v != "" {
		return v
	}
	return defaultAdminURL
}

type harness struct {
	t      *testing.T
	server *httptest.Server
	store  *store.Store
	pool   *pgxpool.Pool
	// Kept so a test can run the real worker over the same images the API just
	// wrote, which is the only way to exercise the Go-to-Python hop end to end.
	imagesStore storage.Store
}

// newHarness brings up an isolated database, migrates it, and serves the real router
// over it. Everything is torn down through t.Cleanup, including the database itself.
func newHarness(t *testing.T) *harness {
	t.Helper()
	ctx := context.Background()

	admin, err := pgxpool.New(ctx, adminURL())
	if err != nil {
		t.Skipf("no PostgreSQL at %s: %v - start it with `make up`", adminURL(), err)
	}
	pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := admin.Ping(pingCtx); err != nil {
		admin.Close()
		t.Skipf("PostgreSQL unreachable: %v - start it with `make up`", err)
	}

	// A UUID, not the test name: Go subtests contain slashes and spaces, and
	// `CREATE DATABASE` would need quoting that is easy to get subtly wrong.
	dbName := "muraka_test_" + strings.ReplaceAll(uuid.NewString(), "-", "")[:24]
	if _, err := admin.Exec(ctx, `CREATE DATABASE `+dbName); err != nil {
		admin.Close()
		t.Fatalf("create test database: %v", err)
	}

	pool, err := database.Connect(ctx, replaceDatabase(adminURL(), dbName))
	if err != nil {
		_, _ = admin.Exec(ctx, `DROP DATABASE IF EXISTS `+dbName)
		admin.Close()
		t.Fatalf("connect to test database: %v", err)
	}

	t.Cleanup(func() {
		pool.Close()
		// Terminate stragglers first: DROP DATABASE fails while any connection
		// remains, and a leaked database silently breaks the *next* run.
		_, _ = admin.Exec(ctx,
			`SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = $1`, dbName)
		if _, err := admin.Exec(ctx, `DROP DATABASE IF EXISTS `+dbName); err != nil {
			t.Logf("could not drop %s: %v", dbName, err)
		}
		admin.Close()
	})

	quiet := slog.New(slog.NewTextHandler(io.Discard, nil))
	if err := database.Migrate(ctx, pool, quiet); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	images, err := storage.NewFS(t.TempDir())
	if err != nil {
		t.Fatalf("storage: %v", err)
	}

	st := store.New(pool)
	cfg := config.Config{
		Env:             "test",
		JWTSecret:       []byte("integration-test-secret-not-a-real-one"),
		JWTIssuer:       "muraka-test",
		AccessTokenTTL:  15 * time.Minute,
		RefreshTokenTTL: 24 * time.Hour,
		MaxUploadBytes:  8 << 20,
		// The real defaults, not ad-hoc test values: the decode ceilings are a
		// security boundary, and a harness that relaxed them would be proving
		// the boundary holds somewhere the deployment does not.
		MaxImagePixels:    config.DefaultMaxImagePixels,
		MaxImageDimension: config.DefaultMaxImageDimension,
		CORSOrigins:       []string{"*"},
	}
	tokens := auth.NewTokenIssuer(cfg.JWTSecret, cfg.JWTIssuer, cfg.AccessTokenTTL, cfg.RefreshTokenTTL)

	// The ML service is not under test here. Pointing the client at an address that
	// refuses connections is deliberate: it proves the API's own behaviour does not
	// depend on the classifier being up, which is what NFR7's server side amounts to.
	ml := mlclient.New("http://127.0.0.1:1", 200*time.Millisecond)

	api := httpapi.New(cfg, quiet, st, tokens, images, ml)
	server := httptest.NewServer(api.Routes())
	t.Cleanup(server.Close)

	return &harness{t: t, server: server, store: st, pool: pool, imagesStore: images}
}

// replaceDatabase swaps the database name in a postgres URL, keeping credentials,
// host and query parameters intact.
func replaceDatabase(rawURL, dbName string) string {
	slash := strings.LastIndex(rawURL, "/")
	if slash < 0 {
		return rawURL
	}
	tail := ""
	if q := strings.Index(rawURL[slash:], "?"); q >= 0 {
		tail = rawURL[slash+q:]
	}
	return rawURL[:slash+1] + dbName + tail
}

// ---------------------------------------------------------------- actors

// actor is an authenticated user plus the token needed to act as them.
type actor struct {
	ID    uuid.UUID
	Email string
	Token string
	Role  domain.Role
}

var actorSeq int

// signUp registers a new user and promotes them to role, returning an actor. The
// promotion goes through the store rather than the admin endpoint so that a test
// about admin endpoints is not bootstrapped by the thing it is testing.
func (h *harness) signUp(role domain.Role) *actor {
	h.t.Helper()
	actorSeq++
	email := fmt.Sprintf("actor%d.%s@muraka.test", actorSeq, uuid.NewString()[:8])

	var body struct {
		AccessToken string `json:"accessToken"`
		User        struct {
			ID string `json:"id"`
		} `json:"user"`
	}
	// 200, not 201: `docs/openapi.yaml` specifies "Account created and signed in" as a
	// 200, because the response body is a session rather than a bare new resource. The
	// first draft of this harness asserted 201 and was wrong about the contract.
	h.mustJSON(http.MethodPost, "/v1/auth/register", "", map[string]any{
		"email":       email,
		"password":    "muraka-integration-2026",
		"displayName": "Integration Actor",
	}, http.StatusOK, &body)

	id := uuid.MustParse(body.User.ID)
	token := body.AccessToken

	if role != domain.RoleContributor {
		if _, err := h.pool.Exec(context.Background(),
			`UPDATE app_user SET role = $2 WHERE id = $1`, id, string(role)); err != nil {
			h.t.Fatalf("promote to %s: %v", role, err)
		}
		// The role is a claim inside the JWT, so the old token still says
		// "contributor". Logging in again is what makes the promotion effective
		// and that asymmetry is itself worth knowing about.
		token = h.login(email, "muraka-integration-2026")
	}
	return &actor{ID: id, Email: email, Token: token, Role: role}
}

func (h *harness) login(email, password string) string {
	h.t.Helper()
	var body struct {
		AccessToken string `json:"accessToken"`
	}
	h.mustJSON(http.MethodPost, "/v1/auth/login", "", map[string]any{
		"email": email, "password": password,
	}, http.StatusOK, &body)
	if body.AccessToken == "" {
		h.t.Fatal("login returned no access token")
	}
	return body.AccessToken
}

// ---------------------------------------------------------------- requests

// do issues a request and returns the status and raw body. Nothing is asserted, so
// callers can test failure paths as first-class outcomes.
func (h *harness) do(method, path, token string, payload any) (int, []byte) {
	h.t.Helper()
	var reader io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			h.t.Fatalf("marshal payload: %v", err)
		}
		reader = strings.NewReader(string(encoded))
	}
	req, err := http.NewRequest(method, h.server.URL+path, reader)
	if err != nil {
		h.t.Fatalf("build request: %v", err)
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := h.server.Client().Do(req)
	if err != nil {
		h.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		h.t.Fatalf("read body: %v", err)
	}
	return res.StatusCode, body
}

// mustJSON issues a request, requires an exact status, and decodes the body into out
// when out is non-nil.
func (h *harness) mustJSON(method, path, token string, payload any, wantStatus int, out any) {
	h.t.Helper()
	status, body := h.do(method, path, token, payload)
	if status != wantStatus {
		h.t.Fatalf("%s %s: got %d, want %d - body: %s", method, path, status, wantStatus, body)
	}
	if out != nil {
		if err := json.Unmarshal(body, out); err != nil {
			h.t.Fatalf("%s %s: decode %s: %v", method, path, body, err)
		}
	}
}

// ---------------------------------------------------------------- fixtures

// newSighting posts a sighting as the given actor and returns its id. The id is
// generated here, as a mobile client would, because it is the idempotency key.
func (h *harness) newSighting(a *actor, lat, lon float64) uuid.UUID {
	h.t.Helper()
	id := uuid.NewString()
	h.mustJSON(http.MethodPost, "/v1/sightings", a.Token, map[string]any{
		"id":             id,
		"lat":            lat,
		"lon":            lon,
		"locationSource": "gps",
		"capturedAt":     time.Now().UTC().Add(-time.Hour).Format(time.RFC3339),
	}, http.StatusCreated, nil)
	return uuid.MustParse(id)
}

// gradeSighting writes a prediction directly, standing in for the worker and the ML
// service. Verification and queue-ordering tests need a graded sighting to exist;
// routing them through the real classifier would test the classifier instead.
func (h *harness) gradeSighting(id uuid.UUID, label string, confidence, severity float64) {
	h.t.Helper()
	ctx := context.Background()
	var photoID uuid.UUID
	if err := h.pool.QueryRow(ctx, `
		INSERT INTO photo (id, sighting_id, storage_key, content_hash, width, height, bytes)
		VALUES (gen_random_uuid(), $1, 'test/key/' || gen_random_uuid()::text,
		        encode(gen_random_bytes(32), 'hex'), 640, 640, 1024)
		RETURNING id`, id).Scan(&photoID); err != nil {
		h.t.Fatalf("insert photo: %v", err)
	}
	// The migration seeds one model version (`fake-0.0.0`) so that predictions can be
	// recorded before a real model is registered; predictions reference it by id.
	if _, err := h.pool.Exec(ctx, `
		INSERT INTO prediction
		    (photo_id, model_version_id, label, confidence, severity, patch_grid, patches, inference_ms)
		VALUES ($1, (SELECT id FROM model_version WHERE is_active LIMIT 1),
		        $2, $3, $4, 5, '[]'::jsonb, 12)`,
		photoID, label, confidence, severity); err != nil {
		h.t.Fatalf("insert prediction: %v", err)
	}
	if _, err := h.pool.Exec(ctx,
		`UPDATE sighting SET status = 'awaiting_verification' WHERE id = $1`, id); err != nil {
		h.t.Fatalf("set status: %v", err)
	}
}

// siteOf returns the reef site a sighting was assigned to, or "" for none.
func (h *harness) siteOf(id uuid.UUID) string {
	h.t.Helper()
	var site *string
	if err := h.pool.QueryRow(context.Background(),
		`SELECT site_id::text FROM sighting WHERE id = $1`, id).Scan(&site); err != nil {
		h.t.Fatalf("read site: %v", err)
	}
	if site == nil {
		return ""
	}
	return *site
}

// trendTotal sums every bucket the trends endpoint returns. Trends is aggregated, so a
// count is the only observable that can betray a row which should have been excluded.
func (h *harness) trendTotal(token string) int {
	h.t.Helper()
	var body struct {
		Buckets []struct {
			Total int `json:"total"`
		} `json:"buckets"`
	}
	h.mustJSON(http.MethodGet, "/v1/trends", token, nil, http.StatusOK, &body)
	total := 0
	for _, b := range body.Buckets {
		total += b.Total
	}
	return total
}

// statusOf reads a sighting's status straight from the database, so an assertion
// about state cannot be satisfied by a serialisation quirk in the API.
func (h *harness) statusOf(id uuid.UUID) string {
	h.t.Helper()
	var status string
	if err := h.pool.QueryRow(context.Background(),
		`SELECT status FROM sighting WHERE id = $1`, id).Scan(&status); err != nil {
		h.t.Fatalf("read status: %v", err)
	}
	return status
}
