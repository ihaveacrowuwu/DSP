// Command seed loads demo and performance-test data (feature M11).
//
// It writes synthetic sightings directly, including predictions, so the map,
// trends and export paths have realistic volume without waiting on inference.
// Severity is modulated by season and by a simulated 2024 bleaching event, which
// makes the dashboard's spatial and temporal views meaningful in a demo.
//
//	go run ./cmd/seed -sightings 2000
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"log"
	"log/slog"
	"math"
	mrand "math/rand"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"muraka/backend/internal/auth"
	"muraka/backend/internal/config"
	"muraka/backend/internal/database"
	"muraka/backend/internal/domain"
	"muraka/backend/internal/storage"
)

// Maldivian atolls with approximate centroids, north to south.
var atolls = []struct {
	Name string
	Code string
	Lat  float64
	Lon  float64
}{
	{"Haa Alifu", "HA", 6.9500, 72.9000},
	{"Haa Dhaalu", "HDh", 6.7500, 73.1000},
	{"Shaviyani", "Sh", 6.3500, 73.1500},
	{"Noonu", "N", 5.8500, 73.3000},
	{"Raa", "R", 5.6000, 72.9500},
	{"Baa", "B", 5.2000, 73.0500},
	{"Lhaviyani", "Lh", 5.4000, 73.5000},
	{"Kaafu", "K", 4.2000, 73.5000},
	{"Alifu Alifu", "AA", 4.0500, 72.8500},
	{"Alifu Dhaalu", "ADh", 3.7500, 72.8000},
	{"Vaavu", "V", 3.5500, 73.5000},
	{"Meemu", "M", 2.9500, 73.5500},
	{"Faafu", "F", 3.2000, 72.9500},
	{"Dhaalu", "Dh", 2.8500, 72.9500},
	{"Thaa", "Th", 2.3500, 73.1500},
	{"Laamu", "L", 1.9500, 73.4500},
	{"Gaafu Alifu", "GA", 0.6000, 73.3000},
	{"Gaafu Dhaalu", "GDh", 0.2000, 73.1000},
	{"Gnaviyani", "Gn", -0.3000, 73.4200},
	{"Seenu", "S", -0.6000, 73.1000},
}

type demoUser struct {
	Email    string
	Name     string
	Password string
	Role     domain.Role
}

var demoUsers = []demoUser{
	{"admin@muraka.test", "Demo Admin", "muraka-admin-2026", domain.RoleAdmin},
	{"researcher@muraka.test", "Dr. Demo Researcher", "muraka-research-2026", domain.RoleResearcher},
	{"diver@muraka.test", "Demo Dive Guide", "muraka-diver-2026", domain.RoleContributor},
	{"diver2@muraka.test", "Second Dive Guide", "muraka-diver-2026", domain.RoleContributor},
}

func main() {
	count := flag.Int("sightings", 500, "number of synthetic sightings to create")
	imageDir := flag.String("images", "/app/sample-images",
		"directory of real reef photographs to attach; optional 'healthy' and "+
			"'bleached' subdirectories are matched to each sighting's label. "+
			"Falls back to a synthetic swatch when empty.")
	seedValue := flag.Int64("seed", 42, "PRNG seed for reproducible data sets")
	reset := flag.Bool("reset", false, "delete existing sightings before seeding")
	flag.Parse()

	if err := run(*count, *seedValue, *reset, *imageDir); err != nil {
		log.Fatalf("seed failed: %v", err)
	}
}

func run(count int, seedValue int64, reset bool, imageDir string) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	ctx := context.Background()

	pool, err := database.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	if err := database.Migrate(ctx, pool, newQuietLogger()); err != nil {
		return err
	}

	rng := mrand.New(mrand.NewSource(seedValue))

	if reset {
		if _, err := pool.Exec(ctx, `DELETE FROM sighting`); err != nil {
			return fmt.Errorf("reset sightings: %w", err)
		}
		fmt.Println("existing sightings deleted")
	}

	// --- atolls
	atollIDs := make([]uuid.UUID, 0, len(atolls))
	for _, a := range atolls {
		var id uuid.UUID
		if err := pool.QueryRow(ctx, `
			INSERT INTO atoll (name, code, centroid)
			VALUES ($1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography)
			ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
			RETURNING id`, a.Name, a.Code, a.Lat, a.Lon).Scan(&id); err != nil {
			return fmt.Errorf("seed atoll %s: %w", a.Code, err)
		}
		atollIDs = append(atollIDs, id)
	}
	fmt.Printf("%d atolls ready\n", len(atollIDs))

	// --- users
	userIDs := map[domain.Role][]uuid.UUID{}
	for _, u := range demoUsers {
		hash, err := auth.HashPassword(u.Password)
		if err != nil {
			return err
		}
		var id uuid.UUID
		if err := pool.QueryRow(ctx, `
			INSERT INTO app_user (email, password_hash, display_name, role)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT (email) DO UPDATE
			SET display_name = EXCLUDED.display_name, role = EXCLUDED.role
			RETURNING id`, u.Email, hash, u.Name, u.Role).Scan(&id); err != nil {
			return fmt.Errorf("seed user %s: %w", u.Email, err)
		}
		userIDs[u.Role] = append(userIDs[u.Role], id)
		fmt.Printf("user %-26s role=%-11s password=%s\n", u.Email, u.Role, u.Password)
	}

	// --- reef sites: one small box around each of the first few atoll centroids
	for i := 0; i < 5 && i < len(atolls); i++ {
		a := atolls[i]
		boundary := boxGeoJSON(a.Lat, a.Lon, 0.25)
		if _, err := pool.Exec(ctx, `
			INSERT INTO reef_site (name, atoll_id, created_by, boundary)
			SELECT $1, $2, $3, ST_GeomFromGeoJSON($4)::geography
			WHERE NOT EXISTS (SELECT 1 FROM reef_site WHERE name = $1)`,
			a.Name+" House Reef", atollIDs[i], userIDs[domain.RoleAdmin][0], boundary); err != nil {
			return fmt.Errorf("seed reef site: %w", err)
		}
	}

	// --- photographs for the synthetic sightings
	images, err := storage.NewFS(cfg.StorageDir)
	if err != nil {
		return err
	}
	library, err := loadImageLibrary(ctx, images, imageDir)
	if err != nil {
		return err
	}
	if library.usingRealPhotos() {
		fmt.Printf("attaching real photographs from %s (%d healthy, %d bleached)\n",
			imageDir, len(library.healthy), len(library.bleached))
	} else {
		fmt.Printf("no photographs found in %s - attaching a synthetic swatch instead\n", imageDir)
		fmt.Println("  drop reef photographs there, optionally in healthy/ and bleached/ subdirectories")
	}

	var fakeModelID uuid.UUID
	if err := pool.QueryRow(ctx, `
		INSERT INTO model_version (version, task, notes)
		VALUES ('seed-0.0.0', 'patch_classification', 'Synthetic predictions from the seed loader')
		ON CONFLICT (version) DO UPDATE SET version = EXCLUDED.version
		RETURNING id`).Scan(&fakeModelID); err != nil {
		return fmt.Errorf("seed model version: %w", err)
	}

	contributors := userIDs[domain.RoleContributor]
	researcher := userIDs[domain.RoleResearcher][0]
	start := time.Date(2023, 1, 1, 0, 0, 0, 0, time.UTC)
	end := time.Now().UTC()
	span := end.Sub(start)

	batch := &pgx.Batch{}
	const flushEvery = 200
	created := 0

	for i := 0; i < count; i++ {
		atoll := atolls[rng.Intn(len(atolls))]
		// Scatter within roughly half a degree of the atoll centre.
		lat := atoll.Lat + (rng.Float64()-0.5)*0.5
		lon := atoll.Lon + (rng.Float64()-0.5)*0.5

		capturedAt := start.Add(time.Duration(rng.Float64() * float64(span)))
		severity := simulatedSeverity(capturedAt, rng)

		label := domain.ConditionHealthy
		if severity >= 0.5 {
			label = domain.ConditionBleached
		}
		confidence := 0.55 + rng.Float64()*0.44

		sightingID := mustUUIDv7()
		photoID := mustUUIDv7()
		contributor := contributors[rng.Intn(len(contributors))]
		depth := math.Round((2+rng.Float64()*22)*10) / 10

		// 60% get expert verification, mirroring a realistic review backlog.
		verified := rng.Float64() < 0.6
		status := domain.StatusAwaitingVerification
		if verified {
			status = domain.StatusVerified
		}

		batch.Queue(`
			INSERT INTO sighting (
				id, contributor_id, location, location_source, depth_m,
				captured_at, status, self_assessed_condition, site_id
			) VALUES (
				$1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography, 'gps', $5,
				$6, $7, NULL,
				(SELECT id FROM reef_site
				  WHERE ST_Covers(boundary, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography)
				  LIMIT 1)
			)`, sightingID, contributor, lat, lon, depth, capturedAt, status)

		// Match the photograph to the label so a sighting reported as bleached does
		// not show obviously healthy coral, which would undermine the demo.
		photo := library.pick(label, rng)
		batch.Queue(`
			INSERT INTO photo (id, sighting_id, storage_key, content_hash, width, height, bytes)
			VALUES ($1, $2, $3, $4, $5, $6, $7)`,
			photoID, sightingID, photo.key, photo.hash, photo.width, photo.height, photo.bytes)

		patches, err := json.Marshal(synthPatches(severity, 5, rng))
		if err != nil {
			return err
		}
		batch.Queue(`
			INSERT INTO classification_job (photo_id, status, finished_at)
			VALUES ($1, 'done', now())`, photoID)
		batch.Queue(`
			INSERT INTO prediction (
				photo_id, model_version_id, label, confidence, severity,
				patch_grid, patches, inference_ms, created_at
			) VALUES ($1, $2, $3, $4, $5, 5, $6, $7, $8)`,
			photoID, fakeModelID, label, confidence, severity,
			patches, 120+rng.Intn(400), capturedAt.Add(time.Hour))

		if verified {
			decision := domain.DecisionConfirmed
			verifiedLabel := label
			// A tenth of reviews correct the model, so the UI has both cases.
			if rng.Float64() < 0.1 {
				decision = domain.DecisionCorrected
				if label == domain.ConditionHealthy {
					verifiedLabel = domain.ConditionBleached
				} else {
					verifiedLabel = domain.ConditionHealthy
				}
			}
			batch.Queue(`
				INSERT INTO verification (sighting_id, verifier_id, decision, label, created_at)
				VALUES ($1, $2, $3, $4, $5)`,
				sightingID, researcher, decision, verifiedLabel, capturedAt.Add(48*time.Hour))
		}

		if batch.Len() >= flushEvery {
			if err := pool.SendBatch(ctx, batch).Close(); err != nil {
				return fmt.Errorf("flush batch: %w", err)
			}
			batch = &pgx.Batch{}
		}
		created++
		if created%500 == 0 {
			fmt.Printf("  %d/%d sightings\n", created, count)
		}
	}

	if batch.Len() > 0 {
		if err := pool.SendBatch(ctx, batch).Close(); err != nil {
			return fmt.Errorf("flush final batch: %w", err)
		}
	}

	fmt.Printf("seeded %d sightings across %d atolls\n", created, len(atolls))
	return nil
}

// simulatedSeverity produces a plausible bleaching signal: a warm-season
// baseline plus a pronounced 2024 thermal-stress event.
func simulatedSeverity(t time.Time, rng *mrand.Rand) float64 {
	// Warm season peaks around April/May in the Maldives.
	seasonal := 0.18 * math.Max(0, math.Cos(2*math.Pi*float64(t.YearDay()-120)/365))

	event := 0.0
	if t.Year() == 2024 && t.Month() >= 3 && t.Month() <= 7 {
		event = 0.45
	}

	severity := 0.12 + seasonal + event + (rng.Float64()-0.5)*0.25
	return math.Min(1, math.Max(0, math.Round(severity*100)/100))
}

// synthPatches distributes bleached cells over the grid consistently with the
// target severity, so overlays match the reported number.
func synthPatches(severity float64, grid int, rng *mrand.Rand) []domain.Patch {
	total := grid * grid
	bleached := int(math.Round(severity * float64(total)))

	order := rng.Perm(total)
	isBleached := make(map[int]bool, bleached)
	for i := 0; i < bleached; i++ {
		isBleached[order[i]] = true
	}

	patches := make([]domain.Patch, 0, total)
	for i := 0; i < total; i++ {
		label := domain.ConditionHealthy
		if isBleached[i] {
			label = domain.ConditionBleached
		}
		patches = append(patches, domain.Patch{
			Row:        i / grid,
			Col:        i % grid,
			Label:      label,
			Confidence: math.Round((0.6+rng.Float64()*0.39)*1000) / 1000,
		})
	}
	return patches
}

func boxGeoJSON(lat, lon, half float64) string {
	return fmt.Sprintf(`{"type":"Polygon","coordinates":[[[%f,%f],[%f,%f],[%f,%f],[%f,%f],[%f,%f]]]}`,
		lon-half, lat-half,
		lon+half, lat-half,
		lon+half, lat+half,
		lon-half, lat+half,
		lon-half, lat-half)
}

// storedImage is one photograph already written to blob storage, ready to be
// referenced by any number of seeded sightings.
type storedImage struct {
	key    string
	hash   string
	width  int
	height int
	bytes  int
}

// imageLibrary holds the photographs available to the seeder, split by the label
// they illustrate. With no real photographs supplied, both lists are empty and
// every sighting falls back to the synthetic swatch.
type imageLibrary struct {
	healthy  []storedImage
	bleached []storedImage
	fallback storedImage
}

func (l imageLibrary) usingRealPhotos() bool {
	return len(l.healthy) > 0 || len(l.bleached) > 0
}

func (l imageLibrary) pick(label domain.Condition, rng *mrand.Rand) storedImage {
	pool := l.healthy
	if label == domain.ConditionBleached {
		pool = l.bleached
	}
	// Either class may be missing; fall back to whatever photographs do exist.
	if len(pool) == 0 {
		pool = append(append([]storedImage{}, l.healthy...), l.bleached...)
	}
	if len(pool) == 0 {
		return l.fallback
	}
	return pool[rng.Intn(len(pool))]
}

// loadImageLibrary stores each photograph found under dir once, keyed by content
// hash, so ten thousand sightings can share a few dozen files.
//
// A missing or empty directory is not an error: the seeder has to work on a
// machine that has never downloaded a dataset.
func loadImageLibrary(ctx context.Context, store *storage.FS, dir string) (imageLibrary, error) {
	library := imageLibrary{}

	swatch, err := syntheticSwatch()
	if err != nil {
		return library, err
	}
	fallback, err := putImage(ctx, store, "seed-swatch", swatch, 640, 640)
	if err != nil {
		return library, err
	}
	library.fallback = fallback

	if dir == "" {
		return library, nil
	}

	// Class subdirectories are optional; a flat directory feeds both labels.
	healthy, err := loadImagesFrom(ctx, store, filepath.Join(dir, "healthy"))
	if err != nil {
		return library, err
	}
	bleached, err := loadImagesFrom(ctx, store, filepath.Join(dir, "bleached"))
	if err != nil {
		return library, err
	}
	library.healthy, library.bleached = healthy, bleached

	if !library.usingRealPhotos() {
		flat, err := loadImagesFrom(ctx, store, dir)
		if err != nil {
			return library, err
		}
		library.healthy, library.bleached = flat, flat
	}
	return library, nil
}

// maxSampleImages caps how many files are read, so pointing the seeder at a whole
// dataset directory does not turn seeding into an import job.
const maxSampleImages = 60

func loadImagesFrom(ctx context.Context, store *storage.FS, dir string) ([]storedImage, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("read %s: %w", dir, err)
	}

	var out []storedImage
	for _, entry := range entries {
		if entry.IsDir() || len(out) >= maxSampleImages {
			continue
		}
		switch strings.ToLower(filepath.Ext(entry.Name())) {
		case ".jpg", ".jpeg", ".png":
		default:
			continue
		}

		path := filepath.Join(dir, entry.Name())
		raw, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", path, err)
		}
		// Decode and re-encode so seeded photographs match what the API would have
		// produced from a real upload: JPEG, EXIF stripped.
		decoded, _, err := image.Decode(bytes.NewReader(raw))
		if err != nil {
			fmt.Printf("  skipping %s: not a decodable image\n", entry.Name())
			continue
		}
		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, decoded, &jpeg.Options{Quality: 85}); err != nil {
			return nil, fmt.Errorf("encode %s: %w", path, err)
		}

		bounds := decoded.Bounds()
		name := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
		stored, err := putImage(ctx, store, name, buf.Bytes(), bounds.Dx(), bounds.Dy())
		if err != nil {
			return nil, err
		}
		out = append(out, stored)
	}
	return out, nil
}

func putImage(ctx context.Context, store *storage.FS, name string, payload []byte, width, height int) (storedImage, error) {
	hash := storage.HashBytes(payload)
	key := storage.Key(name, hash, ".jpg")
	if err := store.Put(ctx, key, bytes.NewReader(payload)); err != nil {
		return storedImage{}, fmt.Errorf("write %s: %w", key, err)
	}
	return storedImage{key: key, hash: hash, width: width, height: height, bytes: len(payload)}, nil
}

// syntheticSwatch stands in when no photographs are available.
//
// Deliberately hatched on the diagonal with no orthogonal lines. An earlier
// version drew a grid, which was indistinguishable from the model's patch overlay
// and made reviewers think the photograph itself had failed to load.
func syntheticSwatch() ([]byte, error) {
	const size = 640
	img := image.NewRGBA(image.Rect(0, 0, size, size))

	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			// Soft mottling, roughly the colour of shallow water over reef.
			shade := 90 + 90*math.Sin(float64(x)/70)*math.Cos(float64(y)/70)
			c := color.RGBA{
				R: clamp8(shade * 0.45),
				G: clamp8(118 + shade*0.28),
				B: clamp8(146 + shade*0.20),
				A: 255,
			}
			// Diagonal hatching reads as "no photograph here" and cannot be taken
			// for the axis-aligned patch grid.
			if (x+y)%64 < 4 {
				c = color.RGBA{
					R: clamp8(shade*0.45 + 26),
					G: clamp8(118 + shade*0.28 + 26),
					B: clamp8(146 + shade*0.20 + 26),
					A: 255,
				}
			}
			img.Set(x, y, c)
		}
	}

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 70}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func clamp8(v float64) uint8 {
	if v < 0 {
		return 0
	}
	if v > 255 {
		return 255
	}
	return uint8(v)
}

// mustUUIDv7 mirrors what the mobile clients generate for idempotent ingest.
func mustUUIDv7() uuid.UUID {
	id, err := uuid.NewV7()
	if err != nil {
		// Fall back to v4 rather than aborting a long seed run.
		var b [16]byte
		if _, err := rand.Read(b[:]); err != nil {
			panic(err)
		}
		binary.BigEndian.PutUint16(b[6:8], binary.BigEndian.Uint16(b[6:8])&0x0fff|0x4000)
		return uuid.Must(uuid.FromBytes(b[:]))
	}
	return id
}

// newQuietLogger keeps migration chatter out of the seeder's progress output.
func newQuietLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelWarn}))
}
