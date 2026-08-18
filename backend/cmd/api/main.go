// Command api serves the Muraka HTTP API and, unless disabled, runs the
// classification worker in-process for single-command local development.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"muraka/backend/internal/auth"
	"muraka/backend/internal/config"
	"muraka/backend/internal/database"
	"muraka/backend/internal/httpapi"
	"muraka/backend/internal/mlclient"
	"muraka/backend/internal/storage"
	"muraka/backend/internal/store"
	"muraka/backend/internal/worker"
)

func main() {
	if err := run(); err != nil {
		slog.Error("fatal", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	log := newLogger(cfg)

	// Cancelled on SIGINT/SIGTERM so shutdown propagates everywhere at once.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := database.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	log.Info("database connected")

	if err := database.Migrate(ctx, pool, log); err != nil {
		return err
	}

	images, err := storage.NewFS(cfg.StorageDir)
	if err != nil {
		return err
	}

	st := store.New(pool)
	tokens := auth.NewTokenIssuer(cfg.JWTSecret, cfg.JWTIssuer, cfg.AccessTokenTTL, cfg.RefreshTokenTTL)
	ml := mlclient.New(cfg.MLServiceURL, cfg.MLTimeout)

	if cfg.WorkerEnabled {
		go worker.New(cfg, log, st, images, ml).Run(ctx)
	} else {
		log.Info("in-process worker disabled", "hint", "run cmd/worker separately")
	}

	api := httpapi.New(cfg, log, st, tokens, images, ml)
	srv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           api.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		// Generous write timeout: photo uploads and CSV exports are slow paths.
		WriteTimeout: 120 * time.Second,
		IdleTimeout:  90 * time.Second,
	}

	serverErr := make(chan error, 1)
	go func() {
		log.Info("api listening", "addr", cfg.HTTPAddr, "env", cfg.Env)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	select {
	case err := <-serverErr:
		return err
	case <-ctx.Done():
		log.Info("shutdown signal received")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return err
	}
	log.Info("shutdown complete")
	return nil
}

func newLogger(cfg config.Config) *slog.Logger {
	level := slog.LevelInfo
	switch cfg.LogLevel {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}

	var handler slog.Handler
	opts := &slog.HandlerOptions{Level: level}
	if cfg.IsProduction() {
		handler = slog.NewJSONHandler(os.Stdout, opts)
	} else {
		handler = slog.NewTextHandler(os.Stdout, opts)
	}
	return slog.New(handler).With("service", "muraka-api")
}
