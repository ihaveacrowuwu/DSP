// Package storage abstracts binary image storage behind a small interface.
//
// The filesystem implementation keeps the dev/demo stack key-free (see the
// project's no-external-API-key constraint); swapping in an S3/MinIO backend is
// an implementation of the same interface plus config, with no call-site churn.
package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type Store interface {
	// Put writes the object and returns its storage key.
	Put(ctx context.Context, key string, r io.Reader) error
	Get(ctx context.Context, key string) (io.ReadCloser, error)
	Delete(ctx context.Context, key string) error
}

type FS struct {
	root string
}

func NewFS(root string) (*FS, error) {
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, fmt.Errorf("create storage dir: %w", err)
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("resolve storage dir: %w", err)
	}
	return &FS{root: abs}, nil
}

func (f *FS) path(key string) (string, error) {
	clean := filepath.Clean(filepath.FromSlash(key))
	if filepath.IsAbs(clean) || strings.HasPrefix(clean, "..") {
		return "", fmt.Errorf("invalid storage key %q", key)
	}
	full := filepath.Join(f.root, clean)
	// Defence in depth: the resolved path must stay under root.
	if !strings.HasPrefix(full, f.root+string(os.PathSeparator)) {
		return "", fmt.Errorf("storage key escapes root: %q", key)
	}
	return full, nil
}

func (f *FS) Put(_ context.Context, key string, r io.Reader) error {
	full, err := f.path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		return fmt.Errorf("create object dir: %w", err)
	}

	tmp, err := os.CreateTemp(filepath.Dir(full), ".upload-*")
	if err != nil {
		return fmt.Errorf("create temp file: %w", err)
	}
	tmpName := tmp.Name()
	defer func() {
		tmp.Close()
		os.Remove(tmpName) // no-op once renamed
	}()

	if _, err := io.Copy(tmp, r); err != nil {
		return fmt.Errorf("write object: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("sync object: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close object: %w", err)
	}
	// Atomic publish: readers never observe a partial file.
	if err := os.Rename(tmpName, full); err != nil {
		return fmt.Errorf("publish object: %w", err)
	}
	return nil
}

func (f *FS) Get(_ context.Context, key string) (io.ReadCloser, error) {
	full, err := f.path(key)
	if err != nil {
		return nil, err
	}
	file, err := os.Open(full)
	if err != nil {
		return nil, err
	}
	return file, nil
}

func (f *FS) Delete(_ context.Context, key string) error {
	full, err := f.path(key)
	if err != nil {
		return err
	}
	if err := os.Remove(full); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Key builds a sharded storage key: photos/<aa>/<photoID>.<ext>. Sharding by
// the first hash byte keeps directory sizes reasonable as the dataset grows.
func Key(photoID, contentHash, ext string) string {
	shard := "00"
	if len(contentHash) >= 2 {
		shard = contentHash[:2]
	}
	return fmt.Sprintf("photos/%s/%s%s", shard, photoID, ext)
}

func HashBytes(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}
