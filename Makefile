# Muraka — common tasks.
#
# Host ports are overridable: make up API_PORT=9090

COMPOSE := docker compose -f deploy/docker-compose.yml
GO      := go

.DEFAULT_GOAL := help

.PHONY: help up down logs ps restart seed reset-data smoke test test-go test-ml test-web \
        build fmt vet typecheck dev dev-api dev-ml dev-web psql \
        test-android test-ios android-build android-install android-lint \
        ios-generate ios-build mobile mobile-lint

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

test: test-go test-ml test-web ## Run all unit tests

test-go: ## Go unit tests
	cd backend && $(GO) test ./...

test-ml: ## ML service tests (fake mode; needs no model)
	cd ml/service && ./.venv/Scripts/python -m pytest tests/ -q

test-web: ## Typecheck the dashboard and run its unit tests
	cd web && npm run typecheck && npm test

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

dev-web: ## Dashboard with hot reload on :5180 (replaces the static web container)
	@echo "Freeing :5180 — the static web container serves the last build there."
	-$(COMPOSE) stop web
	cd web && npm run dev

dev: ## Backing services in Docker + the dashboard with hot reload
	$(COMPOSE) up -d --build postgres ml api
	@$(MAKE) --no-print-directory dev-web

## ---------------------------------------------------------------- mobile

# Android emulators reach the host through 10.0.2.2, never localhost; the iOS
# simulator shares the host's loopback and uses localhost. Both are wired into
# the debug build config, so `make up` then a run is all either app needs.

android-build: ## Assemble the Android debug APK
	cd android && ./gradlew assembleDebug

android-install: ## Build and install on the running emulator or device
	cd android && ./gradlew installDebug

android-lint: ## ktlint, detekt and Android Lint
	cd android && ./gradlew qualityCheck

test-android: ## Android JVM unit tests
	cd android && ./gradlew testDebugUnitTest

ios-generate: ## Regenerate Muraka.xcodeproj from ios/project.yml
	cd ios && xcodegen generate

ios-build: ios-generate ## Build the iOS app for the simulator
	cd ios && xcodebuild -project Muraka.xcodeproj -scheme Muraka \
	  -destination 'platform=iOS Simulator,name=iPhone 17' build

test-ios: ios-generate ## iOS unit tests on the simulator
	cd ios && xcodebuild -project Muraka.xcodeproj -scheme Muraka \
	  -destination 'platform=iOS Simulator,name=iPhone 17' test

mobile: test-android test-ios ## Unit tests for both apps

mobile-lint: ## Linters for both apps
	cd android && ./gradlew ktlintCheck detekt
	cd ios && swiftlint --quiet
