package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/config"
	"muraka/backend/internal/domain"
	"muraka/backend/internal/mlclient"
	"muraka/backend/internal/worker"
)

// NFR12: "All API requests shall carry request IDs propagated through logs
// across Go and Python services."
//
// TESTING.md recorded this as implemented but unasserted. Writing the assertion
// showed it was implemented for the wrong path. The middleware assigns an id to
// inbound HTTP and mlclient forwards it, which covers a request that calls the
// classifier synchronously - and nothing does. Classification happens in the
// worker, draining a queue with no inbound request on the stack, so every call
// the system has ever made to the Python service carried no id and every Python
// log line recorded request_id=None.
//
// These tests assert the header on the wire rather than the words in a log,
// because the header is what the other service actually receives. A log line is
// evidence that the Go side intended to correlate; the header is evidence that
// it can.

// mlRecorder stands in for the Python service and records what it was sent.
type mlRecorder struct {
	mu        sync.Mutex
	requestID []string
	server    *httptest.Server
}

func newMLRecorder(t *testing.T) *mlRecorder {
	rec := &mlRecorder{}
	rec.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec.mu.Lock()
		rec.requestID = append(rec.requestID, r.Header.Get("X-Request-ID"))
		rec.mu.Unlock()

		// The shape the real service returns, so the worker's own parsing runs.
		patches := make([]map[string]any, 0, 25)
		for row := 0; row < 5; row++ {
			for col := 0; col < 5; col++ {
				patches = append(patches, map[string]any{
					"row": row, "col": col, "label": "healthy", "confidence": 0.9,
				})
			}
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"label": "healthy", "confidence": 0.9, "severity": 0.1,
			"patchGrid": 5, "patches": patches, "modelVersion": "test-0.0.1",
			"inferenceMs": 12, "fake": true,
		})
	}))
	t.Cleanup(rec.server.Close)
	return rec
}

func (m *mlRecorder) seen() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return append([]string{}, m.requestID...)
}

func TestTheClassificationCallCarriesACorrelationID(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	photoID := uuid.New()
	if status, body := h.upload(sighting, diver.Token, photoID, "reef.jpg", jpegOf(t, 64, 64)); status != http.StatusCreated {
		t.Fatalf("upload: got %d - body: %s", status, body)
	}

	ml := newMLRecorder(t)
	var logs bytes.Buffer
	h.runWorkerOnce(t, ml, &logs)

	seen := ml.seen()
	if len(seen) == 0 {
		t.Fatal("the worker never called the classifier; nothing was traced")
	}
	for i, rid := range seen {
		if rid == "" {
			t.Fatalf("call %d reached the classifier with no X-Request-ID; "+
				"the Python log line for it would read request_id=None", i)
		}
	}

	// The same id must appear in the Go log, or the two services hold two
	// unrelated identifiers and nothing can be joined.
	if !strings.Contains(logs.String(), seen[0]) {
		t.Fatalf("the id sent to the classifier (%q) appears in no Go log line:\n%s",
			seen[0], logs.String())
	}
}

func TestARetriedJobIsDistinguishableFromItsFirstAttempt(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	if status, _ := h.upload(sighting, diver.Token, uuid.New(), "reef.jpg", jpegOf(t, 64, 64)); status != http.StatusCreated {
		t.Fatal("upload failed")
	}

	// A classifier that fails once. The job goes back on the queue and is
	// claimed again, and the second call must not be indistinguishable from the
	// first - otherwise a duplicated log line and a retry look identical.
	var calls int
	var mu sync.Mutex
	ids := []string{}
	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		calls++
		ids = append(ids, r.Header.Get("X-Request-ID"))
		n := calls
		mu.Unlock()
		if n == 1 {
			http.Error(w, "inference failed", http.StatusInternalServerError)
			return
		}
		patches := make([]map[string]any, 0, 25)
		for row := 0; row < 5; row++ {
			for col := 0; col < 5; col++ {
				patches = append(patches, map[string]any{
					"row": row, "col": col, "label": "healthy", "confidence": 0.9,
				})
			}
		}
		json.NewEncoder(w).Encode(map[string]any{
			"label": "healthy", "confidence": 0.9, "severity": 0.1,
			"patchGrid": 5, "patches": patches, "modelVersion": "test-0.0.1",
			"inferenceMs": 12, "fake": true,
		})
	}))
	t.Cleanup(failing.Close)

	// A failed job returns straight to 'queued' with no backoff, so a single
	// drain covers both the failure and the retry.
	var logs bytes.Buffer
	h.runWorkerAgainst(t, failing.URL, &logs)

	mu.Lock()
	defer mu.Unlock()
	if len(ids) < 2 {
		t.Fatalf("expected a retry; the classifier saw %d call(s)", len(ids))
	}
	if ids[0] == "" || ids[1] == "" {
		t.Fatalf("a retry reached the classifier untraced: %q, %q", ids[0], ids[1])
	}
	if ids[0] == ids[1] {
		t.Fatalf("both attempts used the correlation id %q; a retry is indistinguishable "+
			"from a duplicated log line", ids[0])
	}
}

func TestAnInboundRequestIDIsReusedRatherThanReplaced(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)

	// A caller that already has a trace id - a mobile app, or a proxy - must see
	// its own id come back, not a fresh one, or the trace breaks at the edge.
	mine := "client-chosen-" + uuid.NewString()
	req, err := http.NewRequest(http.MethodGet, h.server.URL+"/v1/me", nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+diver.Token)
	req.Header.Set("X-Request-ID", mine)

	res, err := h.server.Client().Do(req)
	if err != nil {
		t.Fatalf("GET /v1/me: %v", err)
	}
	defer res.Body.Close()

	if got := res.Header.Get("X-Request-ID"); got != mine {
		t.Fatalf("X-Request-ID: got %q, want the inbound %q", got, mine)
	}
}

func TestEveryResponseCarriesARequestIDEvenWithoutOne(t *testing.T) {
	h := newHarness(t)

	// Including failures: an id that only appears on success is missing from
	// exactly the responses someone would be tracing.
	status, _ := h.do(http.MethodGet, "/v1/sightings", "", nil)
	if status != http.StatusUnauthorized {
		t.Fatalf("unauthenticated request: got %d, want 401", status)
	}

	res, err := h.server.Client().Get(h.server.URL + "/v1/sightings")
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	defer res.Body.Close()
	if res.Header.Get("X-Request-ID") == "" {
		t.Fatal("a 401 carried no X-Request-ID")
	}
}

// runWorkerOnce drains the queue once against a stand-in classifier, capturing
// the worker's own log output.
func (h *harness) runWorkerOnce(t *testing.T, ml *mlRecorder, logs *bytes.Buffer) {
	t.Helper()
	h.runWorkerAgainst(t, ml.server.URL, logs)
}

func (h *harness) runWorkerAgainst(t *testing.T, mlURL string, logs *bytes.Buffer) {
	t.Helper()
	cfg := config.Config{
		WorkerBatchSize:    4,
		WorkerMaxAttempts:  5,
		WorkerClaimTimeout: time.Minute,
		WorkerPollInterval: 10 * time.Millisecond,
	}
	log := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	w := worker.New(cfg, log, h.store, h.imagesStore, mlclient.New(mlURL, 5*time.Second))

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	go w.Run(ctx)

	// Poll until the queue drains rather than sleeping a fixed interval.
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if h.queuedJobs() == 0 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	cancel()
}

func (h *harness) queuedJobs() int {
	var n int
	if err := h.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM classification_job WHERE status = 'queued'`).Scan(&n); err != nil {
		h.t.Fatalf("count queued jobs: %v", err)
	}
	return n
}
