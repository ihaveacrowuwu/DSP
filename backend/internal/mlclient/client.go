// Package mlclient talks to the Python inference service.
//
// The boundary is deliberately narrow: bytes in, structured assessment out. The
// Go side knows nothing about models, patches or preprocessing beyond this
// contract, so the ML service can evolve (classifier -> segmentation) without
// touching the platform.
package mlclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"time"
)

type Client struct {
	baseURL string
	http    *http.Client
}

func New(baseURL string, timeout time.Duration) *Client {
	return &Client{
		baseURL: baseURL,
		http:    &http.Client{Timeout: timeout},
	}
}

// PatchResult mirrors one grid cell from the ML service.
type PatchResult struct {
	Row        int     `json:"row"`
	Col        int     `json:"col"`
	Label      string  `json:"label"`
	Confidence float64 `json:"confidence"`
}

// Assessment is the ML service's verdict on a single photo.
type Assessment struct {
	Label        string        `json:"label"`
	Confidence   float64       `json:"confidence"`
	Severity     float64       `json:"severity"`
	PatchGrid    int           `json:"patch_grid"`
	Patches      []PatchResult `json:"patches"`
	ModelVersion string        `json:"model_version"`
	InferenceMS  int           `json:"inference_ms"`
	Fake         bool          `json:"fake"`
}

type HealthResponse struct {
	Status       string `json:"status"`
	ModelVersion string `json:"model_version"`
	FakeMode     bool   `json:"fake_mode"`
	PatchGrid    int    `json:"patch_grid"`
}

func (c *Client) Health(ctx context.Context) (HealthResponse, error) {
	var out HealthResponse
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/healthz", nil)
	if err != nil {
		return out, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return out, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return out, fmt.Errorf("ml service health returned %s", resp.Status)
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return out, fmt.Errorf("decode health: %w", err)
	}
	return out, nil
}

// Classify uploads one image and returns its assessment.
func (c *Client) Classify(ctx context.Context, filename string, image []byte) (Assessment, error) {
	var out Assessment

	body := &bytes.Buffer{}
	mw := multipart.NewWriter(body)
	part, err := mw.CreateFormFile("file", filename)
	if err != nil {
		return out, fmt.Errorf("build request: %w", err)
	}
	if _, err := part.Write(image); err != nil {
		return out, fmt.Errorf("write image: %w", err)
	}
	if err := mw.Close(); err != nil {
		return out, fmt.Errorf("close multipart: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/classify", body)
	if err != nil {
		return out, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	if rid, ok := ctx.Value(RequestIDKey).(string); ok && rid != "" {
		req.Header.Set("X-Request-ID", rid)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return out, fmt.Errorf("call ml service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return out, fmt.Errorf("ml service returned %s: %s", resp.Status, bytes.TrimSpace(snippet))
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return out, fmt.Errorf("decode assessment: %w", err)
	}
	return out, nil
}

// contextKey carries the request ID across the service boundary so logs in Go
// and Python can be correlated (NFR12).
type contextKey string

const RequestIDKey contextKey = "requestID"
