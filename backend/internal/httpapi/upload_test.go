package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"mime/multipart"
	"net/http"
	"runtime"
	"testing"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
	"muraka/backend/internal/imagemeta"
)

// photoCount reads the number of photographs actually stored for a sighting.
// A retried upload that returns 200 while quietly writing a second row would
// satisfy every status assertion and still corrupt the data.
func (h *harness) photoCount(sightingID uuid.UUID) int {
	h.t.Helper()
	var n int
	if err := h.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM photo WHERE sighting_id = $1`, sightingID).Scan(&n); err != nil {
		h.t.Fatalf("count photos: %v", err)
	}
	return n
}

// NFR5: "Uploaded images shall be validated (type, size cap) and re-encoded
// server-side, stripping EXIF after extracting capture time and GPS."
//
// The smoke test covered one half of one clause - it refuses a text file. This
// file covers the rest, and it is deliberately adversarial: the interesting
// inputs are not malformed ones, which fail loudly, but well-formed ones whose
// cost is out of proportion to their size. A 12 MiB upload the server is willing
// to accept can still ask it to allocate gigabytes.

// upload posts a photograph the way a mobile client does: a client-generated id
// as the idempotency key, and the file as multipart.
func (h *harness) upload(sightingID uuid.UUID, token string, photoID uuid.UUID, filename string, content []byte) (int, []byte) {
	h.t.Helper()

	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	if err := form.WriteField("photoId", photoID.String()); err != nil {
		h.t.Fatalf("write photoId: %v", err)
	}
	part, err := form.CreateFormFile("file", filename)
	if err != nil {
		h.t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write(content); err != nil {
		h.t.Fatalf("write file part: %v", err)
	}
	if err := form.Close(); err != nil {
		h.t.Fatalf("close form: %v", err)
	}

	req, err := http.NewRequest(http.MethodPost,
		fmt.Sprintf("%s/v1/sightings/%s/photos", h.server.URL, sightingID), &body)
	if err != nil {
		h.t.Fatalf("build request: %v", err)
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)

	res, err := h.server.Client().Do(req)
	if err != nil {
		h.t.Fatalf("upload: %v", err)
	}
	defer res.Body.Close()
	out, err := io.ReadAll(res.Body)
	if err != nil {
		h.t.Fatalf("read upload response: %v", err)
	}
	return res.StatusCode, out
}

// errorCode pulls the machine-readable code out of an error body, so a test can
// assert why a request was refused rather than only that it was.
func errorCode(t *testing.T, body []byte) string {
	t.Helper()
	var out struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatalf("decode error body %s: %v", body, err)
	}
	return out.Error
}

// jpegOf encodes a solid-colour JPEG of the given size.
func jpegOf(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 120, A: 255})
		}
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatalf("encode jpeg: %v", err)
	}
	return out.Bytes()
}

func TestUploadRejectsAFileOverTheSizeCap(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	// The harness caps uploads at 8 MiB (the deployment uses 12). A valid JPEG
	// comfortably past whichever it is must be refused on size, before anything
	// tries to decode it.
	oversize := make([]byte, 13<<20)
	copy(oversize, jpegOf(t, 64, 64))

	status, body := h.upload(sighting, diver.Token, uuid.New(), "big.jpg", oversize)
	if status != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize upload: got %d, want 413 - body: %s", status, body)
	}
}

func TestUploadAcceptsAFileUnderTheCapAndReEncodesIt(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	// A PNG in, a JPEG out: the re-encode is what strips EXIF, so proving the
	// stored bytes are not the submitted bytes is proving the strip happened.
	var submitted bytes.Buffer
	img := image.NewRGBA(image.Rect(0, 0, 320, 240))
	if err := png.Encode(&submitted, img); err != nil {
		t.Fatalf("encode png: %v", err)
	}

	photoID := uuid.New()
	status, body := h.upload(sighting, diver.Token, photoID, "reef.png", submitted.Bytes())
	if status != http.StatusCreated {
		t.Fatalf("upload: got %d, want 201 - body: %s", status, body)
	}

	var out struct {
		Width, Height, Bytes int
	}
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
	if out.Width != 320 || out.Height != 240 {
		t.Fatalf("dimensions: got %dx%d, want 320x240", out.Width, out.Height)
	}
	if out.Bytes == submitted.Len() {
		t.Fatal("stored byte count equals the submitted one; the image was not re-encoded")
	}
}

func TestUploadRefusesAFileThatIsNotAnImage(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	// Named .jpg and declared as a photograph. Only decoding it reveals otherwise,
	// which is why the handler never trusts the declared content type.
	status, _ := h.upload(sighting, diver.Token, uuid.New(), "reef.jpg", []byte("#!/bin/sh\nrm -rf /\n"))
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("non-image upload: got %d, want 422", status)
	}
}

func TestUploadRefusesAnImageFormatThatIsNotJPEGOrPNG(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	// A real, decodable GIF. It is a valid image and still not one of the two
	// formats the pipeline is specified for.
	status, _ := h.upload(sighting, diver.Token, uuid.New(), "reef.gif", gifBytes(t))
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("gif upload: got %d, want 422", status)
	}
}

// TestUploadRefusesADecompressionBomb is the test this file exists for.
//
// A PNG's header declares its dimensions; the pixels are deflate-compressed. A
// uniform image compresses so well that a 30000x30000 PNG - 3.6 GB once decoded
// into an RGBA buffer - fits in a few hundred kilobytes on the wire. Every
// size check in the handler passes it, because by every measure the handler
// takes, it is a small file.
//
// The assertion is on memory, not only on the status code: a handler that
// refuses the image *after* decoding it has already lost, since the allocation
// is the attack.
func TestUploadRefusesADecompressionBomb(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	bomb := bombPNG(t, 30000, 30000)
	if len(bomb) > 1<<20 {
		t.Fatalf("bomb is %d bytes; it must be small enough to pass the size cap", len(bomb))
	}

	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)

	done := make(chan struct{})
	var status int
	var body []byte
	go func() {
		defer close(done)
		status, body = h.upload(sighting, diver.Token, uuid.New(), "bomb.png", bomb)
	}()

	select {
	case <-done:
	case <-time.After(20 * time.Second):
		t.Fatal("upload of a decompression bomb did not return within 20s")
	}

	runtime.ReadMemStats(&after)
	allocated := after.TotalAlloc - before.TotalAlloc

	if status != http.StatusUnprocessableEntity {
		t.Fatalf("decompression bomb: got %d, want 422", status)
	}
	// On the code, not just the status: refusing it as "not a decodable image"
	// would be the decoder giving up after the allocation, which is the failure
	// this test is about rather than a pass.
	if code := errorCode(t, body); code != "image_too_large" {
		t.Fatalf("decompression bomb refused as %q, want image_too_large", code)
	}
	// 3.6 GB is what decoding it would cost. A guard that reads the header and
	// stops keeps this in the low megabytes; the threshold is deliberately loose
	// so this fails on the attack rather than on allocator noise.
	if allocated > 256<<20 {
		t.Fatalf("decoding the bomb allocated %d MiB; the header should have been enough to refuse it",
			allocated>>20)
	}
}

func TestUploadRefusesABombEvenWithOneEnormousDimension(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	// 100000 x 4 is only 400k pixels, so a total-pixel budget waves it through.
	// It is still not a photograph, and the patch grid could not tile it into
	// anything a reviewer could judge, so the per-side cap refuses it.
	status, body := h.upload(sighting, diver.Token, uuid.New(), "strip.png", bombPNG(t, 100000, 4))
	if status != http.StatusUnprocessableEntity {
		t.Fatalf("single-dimension bomb: got %d, want 422", status)
	}
	if code := errorCode(t, body); code != "image_too_large" {
		t.Fatalf("strip refused as %q, want image_too_large - a pixel budget alone would miss this", code)
	}
}

func TestUploadIsIdempotentOnThePhotoID(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	photoID := uuid.New()
	content := jpegOf(t, 64, 64)

	// FR4: a retried upload - the normal case when a phone loses signal
	// mid-request - must not produce a second photograph.
	first, _ := h.upload(sighting, diver.Token, photoID, "reef.jpg", content)
	second, _ := h.upload(sighting, diver.Token, photoID, "reef.jpg", content)

	if first != http.StatusCreated {
		t.Fatalf("first upload: got %d, want 201", first)
	}
	if second != http.StatusOK {
		t.Fatalf("retried upload: got %d, want 200 - a retry must not create a second photo", second)
	}
	if n := h.photoCount(sighting); n != 1 {
		t.Fatalf("photo count after a retry: got %d, want 1", n)
	}
}

func TestUploadRefusesAPhotographForAnotherContributorsSighting(t *testing.T) {
	h := newHarness(t)
	owner := h.signUp(domain.RoleContributor)
	stranger := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(owner, 4.05, 72.94)

	status, _ := h.upload(sighting, stranger.Token, uuid.New(), "reef.jpg", jpegOf(t, 64, 64))
	if status != http.StatusForbidden {
		t.Fatalf("cross-contributor upload: got %d, want 403", status)
	}
}

// TestUploadKeepsExifFactsAndStripsTheRest is NFR5's ordering clause end to end:
// "stripping EXIF after extracting capture time and GPS".
//
// The two halves have to be asserted together, because each is trivially
// satisfiable alone. Stripping everything and keeping nothing passes any test
// that only looks at the stored file; keeping everything passes any test that
// only looks at the database. This checks that the facts reached the row *and*
// that the bytes on disk no longer carry them.
func TestUploadKeepsExifFactsAndStripsTheRest(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	photoID := uuid.New()
	captured := time.Date(2026, 8, 20, 8, 30, 0, 0, time.UTC)
	withExif := jpegWithExif(t, captured, 4.0520, 72.9481)

	status, body := h.upload(sighting, diver.Token, photoID, "reef.jpg", withExif)
	if status != http.StatusCreated {
		t.Fatalf("upload: got %d, want 201 - body: %s", status, body)
	}

	// Extracted: the row carries what the camera recorded.
	gotTime, gotLat, gotLon := h.exifOf(photoID)
	if gotTime == nil || !gotTime.Equal(captured) {
		t.Fatalf("exif_captured_at: got %v, want %s", gotTime, captured)
	}
	if gotLat == nil || gotLon == nil {
		t.Fatal("exif_location was not stored; the GPS fix was discarded")
	}
	if *gotLat < 4.051 || *gotLat > 4.053 || *gotLon < 72.947 || *gotLon > 72.949 {
		t.Fatalf("exif_location: got %f,%f - want about 4.0520,72.9481", *gotLat, *gotLon)
	}

	// Stripped: whatever is served back no longer carries the block.
	stored := h.photoBytes(diver.Token, photoID)
	if bytes.Contains(stored, []byte("Exif\x00\x00")) {
		t.Fatal("the stored image still carries an EXIF segment")
	}
	leftover, err := imagemeta.FromJPEG(stored)
	if err == nil && (leftover.CapturedAt != nil || leftover.Lat != nil) {
		t.Fatalf("EXIF survived the re-encode: %+v", leftover)
	}
}

func TestUploadAcceptsAPhotographWithNoExifAtAll(t *testing.T) {
	h := newHarness(t)
	diver := h.signUp(domain.RoleContributor)
	sighting := h.newSighting(diver, 4.05, 72.94)

	// The common case. Missing metadata must never cost a contributor their
	// sighting, so this is a 201 with null columns, not a refusal.
	photoID := uuid.New()
	status, _ := h.upload(sighting, diver.Token, photoID, "reef.jpg", jpegOf(t, 64, 64))
	if status != http.StatusCreated {
		t.Fatalf("upload without exif: got %d, want 201", status)
	}

	when, lat, lon := h.exifOf(photoID)
	if when != nil || lat != nil || lon != nil {
		t.Fatalf("metadata invented for a file that had none: %v %v %v", when, lat, lon)
	}
}

// exifOf reads back the EXIF columns for a photograph.
func (h *harness) exifOf(photoID uuid.UUID) (*time.Time, *float64, *float64) {
	h.t.Helper()
	var when *time.Time
	var lat, lon *float64
	err := h.pool.QueryRow(context.Background(), `
		SELECT exif_captured_at,
		       ST_Y(exif_location::geometry),
		       ST_X(exif_location::geometry)
		FROM photo WHERE id = $1`, photoID).Scan(&when, &lat, &lon)
	if err != nil {
		h.t.Fatalf("read exif columns: %v", err)
	}
	if when != nil {
		utc := when.UTC()
		when = &utc
	}
	return when, lat, lon
}

// photoBytes fetches the stored image back through the API, which is the only
// form of it a contributor or researcher ever sees.
func (h *harness) photoBytes(token string, photoID uuid.UUID) []byte {
	h.t.Helper()
	req, err := http.NewRequest(http.MethodGet,
		fmt.Sprintf("%s/v1/photos/%s/image", h.server.URL, photoID), nil)
	if err != nil {
		h.t.Fatalf("build image request: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := h.server.Client().Do(req)
	if err != nil {
		h.t.Fatalf("fetch image: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		h.t.Fatalf("fetch image: got %d, want 200", res.StatusCode)
	}
	out, err := io.ReadAll(res.Body)
	if err != nil {
		h.t.Fatalf("read image: %v", err)
	}
	return out
}
