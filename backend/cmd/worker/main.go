// Command worker runs the classification queue drainer on its own. Useful for
// scaling inference independently of the API, and for load-testing the pipeline.
package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"muraka/backend/internal/config"
	"muraka/backend/internal/database"
	"muraka/backend/internal/mlclient"
	"muraka/backend/internal/storage"
	"muraka/backend/internal/store"
	"muraka/backend/internal/worker"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		slog.Error("config", "error", err)
		os.Exit(1)
	}

	log := slog.New(slog.NewTextHandler(os.Stdout, nil)).With("service", "muraka-worker")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := database.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Error("database", "error", err)
		os.Exit(1)
	}
	defer pool.Close()

	images, err := storage.NewFS(cfg.StorageDir)
	if err != nil {
		log.Error("storage", "error", err)
		os.Exit(1)
	}

	w := worker.New(cfg, log, store.New(pool), images, mlclient.New(cfg.MLServiceURL, cfg.MLTimeout))
	w.Run(ctx)
}
