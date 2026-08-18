# Muraka — common tasks.
#
# Host ports are overridable: make up API_PORT=9090

COMPOSE := docker compose -f deploy/docker-compose.yml
GO      := go

.DEFAULT_GOAL := help

.PHONY: help up down logs ps restart seed reset-data smoke test test-go test-ml test-web \
        build fmt vet typecheck dev-api dev-ml dev-web psql

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

## ---------------------------------------------------------------- stack

up: ## Build and start the whole stack
	$(COMPOSE) up -d --build
	@echo
	@echo "  dashboard  http://localhost:$${WEB_PORT:-5180}"
	@echo "  api        http://localhost:$${API_PORT:-8090}/healthz"
	@echo "  ml         http://localhost:$${ML_PORT:-8010}/healthz"

down: ## Stop the stack (keeps data)
	$(COMPOSE) down

logs: ## Follow all logs
	$(COMPOSE) logs -f

ps: ## Show service status
	$(COMPOSE) ps

restart: ## Rebuild and restart one service, e.g. make restart S=api
	$(COMPOSE) up -d --build $(S)

psql: ## Open a database shell
	$(COMPOSE) exec postgres psql -U muraka -d muraka

## ---------------------------------------------------------------- data

seed: ## Load demo data (N=500 by default)
	$(COMPOSE) exec api seed -sightings $(or $(N),500)

reset-data: ## Delete all sightings, then reseed
	$(COMPOSE) exec api seed -reset -sightings $(or $(N),500)

## ---------------------------------------------------------------- tests

test: test-go test-ml ## Run all unit tests

test-go: ## Go unit tests
	cd backend && $(GO) test ./...

test-ml: ## ML service tests (fake mode; needs no model)
	cd ml/service && ./.venv/Scripts/python -m pytest tests/ -q

test-web: ## Typecheck the dashboard
	cd web && npm run typecheck

smoke: ## End-to-end pipeline test against the running stack
	python scripts/smoke_test.py

## ---------------------------------------------------------------- build

build: ## Compile everything
	cd backend && $(GO) build ./...
	cd web && npm run build

fmt: ## Format Go code
	cd backend && gofmt -w .

vet: ## Static analysis
	cd backend && $(GO) vet ./...

typecheck: test-web ## Alias for test-web

## ---------------------------------------------------------------- local dev

dev-api: ## Run the API on the host against the containerised database
	cd backend && DATABASE_URL='postgres://muraka:muraka@localhost:5433/muraka?sslmode=disable' \
	  ML_SERVICE_URL='http://localhost:8010' $(GO) run ./cmd/api

dev-ml: ## Run the ML service on the host with reload
	cd ml/service && ./.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000

dev-web: ## Run the Vite dev server
	cd web && npm run dev
