package storage

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newStore(t *testing.T) (*FS, string) {
	t.Helper()
	root := t.TempDir()
	store, err := NewFS(root)
	if err != nil {
		t.Fatalf("NewFS: %v", err)
	}
	return store, root
}

func TestPutThenGetReturnsSameBytes(t *testing.T) {
	store, _ := newStore(t)
	ctx := context.Background()
	want := []byte("pretend this is a reef photograph")

	if err := store.Put(ctx, "photos/ab/one.jpg", bytes.NewReader(want)); err != nil {
		t.Fatalf("Put: %v", err)
	}

	reader, err := store.Get(ctx, "photos/ab/one.jpg")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	defer reader.Close()

	got, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Errorf("round trip changed the bytes: got %q", got)
	}
}

func TestPutCreatesNestedDirectories(t *testing.T) {
	store, root := newStore(t)

	if err := store.Put(context.Background(), "photos/cd/deep/nested.jpg", strings.NewReader("x")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "photos", "cd", "deep", "nested.jpg")); err != nil {
		t.Errorf("expected the object on disk: %v", err)
	}
}

func TestPutLeavesNoTemporaryFilesBehind(t *testing.T) {
	// Writes are staged then renamed, so readers never see a partial file. The
	// staging file must not survive a successful write.
	store, root := newStore(t)

	if err := store.Put(context.Background(), "photos/ef/atomic.jpg", strings.NewReader("payload")); err != nil {
		t.Fatalf("Put: %v", err)
	}

	entries, err := os.ReadDir(filepath.Join(root, "photos", "ef"))
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".upload-") {
			t.Errorf("temporary file left behind: %s", entry.Name())
		}
	}
	if len(entries) != 1 {
		t.Errorf("expected exactly one file, got %d", len(entries))
	}
}

func TestPutOverwritesExistingKey(t *testing.T) {
	store, _ := newStore(t)
	ctx := context.Background()

	if err := store.Put(ctx, "photos/gh/same.jpg", strings.NewReader("first")); err != nil {
		t.Fatalf("first Put: %v", err)
	}
	if err := store.Put(ctx, "photos/gh/same.jpg", strings.NewReader("second")); err != nil {
		t.Fatalf("second Put: %v", err)
	}

	reader, err := store.Get(ctx, "photos/gh/same.jpg")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	defer reader.Close()

	got, _ := io.ReadAll(reader)
	if string(got) != "second" {
		t.Errorf("expected the newer content, got %q", got)
	}
}

func TestKeysCannotEscapeTheStorageRoot(t *testing.T) {
	// A traversal key must be refused rather than writing outside the root.
	store, _ := newStore(t)
	ctx := context.Background()

	for _, key := range []string{
		"../escape.jpg",
		"../../etc/passwd",
		"photos/../../escape.jpg",
	} {
		t.Run(key, func(t *testing.T) {
			if err := store.Put(ctx, key, strings.NewReader("nope")); err == nil {
				t.Error("Put should reject a traversal key")
			}
			if _, err := store.Get(ctx, key); err == nil {
				t.Error("Get should reject a traversal key")
			}
		})
	}
}

func TestGetMissingKeyReportsNotExist(t *testing.T) {
	store, _ := newStore(t)

	if _, err := store.Get(context.Background(), "photos/zz/absent.jpg"); !os.IsNotExist(err) {
		t.Errorf("expected a not-exist error, got %v", err)
	}
}

func TestDeleteIsIdempotent(t *testing.T) {
	store, _ := newStore(t)
	ctx := context.Background()

	if err := store.Put(ctx, "photos/ij/gone.jpg", strings.NewReader("x")); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := store.Delete(ctx, "photos/ij/gone.jpg"); err != nil {
		t.Fatalf("first Delete: %v", err)
	}
	// Deleting something already absent is success, not an error.
	if err := store.Delete(ctx, "photos/ij/gone.jpg"); err != nil {
		t.Errorf("second Delete should be a no-op, got %v", err)
	}
}

func TestKeyShardsByContentHash(t *testing.T) {
	key := Key("photo-id", "deadbeefcafe", ".jpg")

	if want := "photos/de/photo-id.jpg"; key != want {
		t.Errorf("Key = %q, want %q", key, want)
	}
}

func TestKeyHandlesShortHashWithoutPanicking(t *testing.T) {
	if key := Key("photo-id", "", ".jpg"); key != "photos/00/photo-id.jpg" {
		t.Errorf("Key with empty hash = %q", key)
	}
}

func TestHashBytesIsStableAndContentAddressed(t *testing.T) {
	first := HashBytes([]byte("reef"))
	second := HashBytes([]byte("reef"))
	other := HashBytes([]byte("reefs"))

	if first != second {
		t.Error("the same bytes must hash identically")
	}
	if first == other {
		t.Error("different bytes must hash differently")
	}
	if len(first) != 64 {
		t.Errorf("expected a 64-character SHA-256 hex digest, got %d characters", len(first))
	}
}
