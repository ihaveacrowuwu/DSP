# Muraka - common tasks.
#
# Host ports are overridable: make up API_PORT=9090

COMPOSE := docker compose -f deploy/docker-compose.yml
# The demo configuration terminates TLS (NFR4). It is an overlay because the two mobile
# apps talk to the plain-HTTP development stack, and an HTTPS-only base stack would mean
# installing a self-signed certificate into both emulators' trust stores.
COMPOSE_TLS := docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.tls.yml
GO      := go

# `python3`, never `python`. On this machine `python` is a shell *alias*, so recipes
# using it failed with "No such file or directory" - make runs /bin/sh, which does not
# read the interactive shell's aliases. `make smoke` was broken that way.
PY      := python3
# The ML tests run in a virtualenv created on demand. The path was `.venv/Scripts/python`,
# which is the **Windows** layout - part of this repository was written on a Windows
# machine - so `make test-ml`, and therefore `make test`, failed on macOS and Linux.
VENV    := ml/service/.venv
VENV_PY := $(VENV)/bin/python

.DEFAULT_GOAL := help

.PHONY: help up up-tls down down-tls logs ps restart seed reset-data smoke smoke-tls test test-go test-ml test-web test-train \
        build fmt vet typecheck dev dev-api dev-ml dev-web psql perf \
        test-android test-ios android-build android-install android-lint \
        ios-generate ios-build mobile mobile-lint lint

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

up-tls: deploy/tls/certs/server.crt ## Build and start the demo stack with TLS on :8443
	$(COMPOSE_TLS) up -d --build
	@echo
	@echo "Demo stack on https://localhost:$${TLS_PORT:-8443} - dashboard and API share one origin."
	@echo "The certificate is self-signed (NFR9 rules out a CA), so a browser will warn once."

deploy/tls/certs/server.crt:
	./deploy/tls/generate.sh

down-tls: ## Stop the TLS demo stack
	$(COMPOSE_TLS) down

smoke-tls: up-tls ## Run the smoke test through TLS, proving the demo config works
	@# Self-signed, so the client is told to accept it - the point of the check is that
	@# the whole pipeline works over TLS, not that a demo certificate chains to a CA.
	MURAKA_API=https://localhost:$${TLS_PORT:-8443} MURAKA_TLS_INSECURE=1 $(PY) scripts/smoke_test.py

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

test: test-go test-ml test-web ## Run all unit tests (add test-train for the ML track)

test-go: ## Go unit tests
	cd backend && $(GO) test ./...

test-ml: $(VENV_PY) ## ML service tests (fake mode; needs no model)
	cd ml/service && .venv/bin/python -m pytest tests/ -q

$(VENV_PY): ml/service/requirements-dev.txt
	@echo "Creating $(VENV) from pinned requirements"
	$(PY) -m venv $(VENV)
	$(VENV_PY) -m pip install --quiet --upgrade pip
	$(VENV_PY) -m pip install --quiet -r ml/service/requirements-dev.txt
	@touch $(VENV_PY)

test-train: ml/training/.venv/bin/python ## ML training-track tests (no dataset needed)
	cd ml/training && .venv/bin/python -m pytest tests/ -q

ml/training/.venv/bin/python: ml/training/requirements.txt
	@echo "Creating ml/training/.venv (torch is a large download the first time)"
	$(PY) -m venv ml/training/.venv
	ml/training/.venv/bin/python -m pip install --quiet --upgrade pip
	ml/training/.venv/bin/python -m pip install --quiet -r ml/training/requirements.txt
	@touch ml/training/.venv/bin/python

test-web: ## Typecheck the dashboard and run its unit tests
	cd web && npm run typecheck && npm test

smoke: ## End-to-end pipeline test against the running stack
	$(PY) scripts/smoke_test.py

perf: ## Measure NFR1, NFR2, NFR3 and NFR11 against the running stack
	@# NFR3 is about 10,000 sightings; the harness reports the corpus it actually found
	@# and fails rather than quietly passing against a small one.
	$(PY) scripts/perf_test.py --json docs/evidence/performance/perf-$$(date +%Y-%m-%d).json

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

dev-ml: $(VENV_PY) ## Run the ML service on the host with reload
	cd ml/service && .venv/bin/python -m uvicorn app.main:app --reload --port 8000

dev-web: ## Dashboard with hot reload on :5180 (replaces the static web container)
	@echo "Freeing :5180 - the static web container serves the last build there."
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

# `test` and not `testDebugUnitTest`: core/common and core/model are plain JVM
# modules, which never get the Android variant tasks, so testDebugUnitTest ran
# 6 of the 47 tests and reported success. `test` covers both kinds.
test-android: ## Android JVM unit tests
	cd android && ./gradlew test

ios-generate: ## Regenerate Muraka.xcodeproj from ios/project.yml
	cd ios && xcodegen generate

# The OS is pinned deliberately. `name=iPhone 17` alone is AMBIGUOUS when more than one
# runtime is installed - this machine has an iPhone 17 on both 26.1 and 26.5, and xcodebuild
# silently picks one while `xcrun simctl` commands address whichever is booted. That cost an
# hour of "dark mode does not work" when the screenshots were simply coming from the other
# device. Override with `make test-ios IOS_SIM='...'` if you need a different one.
IOS_SIM ?= platform=iOS Simulator,name=iPhone 17,OS=26.5

ios-build: ios-generate ## Build the iOS app for the simulator
	cd ios && xcodebuild -project Muraka.xcodeproj -scheme Muraka \
	  -destination '$(IOS_SIM)' build

test-ios: ios-generate ## iOS unit tests on the simulator
	cd ios && xcodebuild -project Muraka.xcodeproj -scheme Muraka \
	  -destination '$(IOS_SIM)' test

mobile: test-android test-ios ## Unit tests for both apps

lint: mobile-lint ## Every lint and contract check, including the traceability matrix
	@echo
	@# The testing chapter of the project is assembled from TESTING.md, so a test it cites
	@# having been renamed or deleted is a documentation bug that must fail the build.
	$(PY) scripts/testing_matrix.py --check
	@echo
	@# NFR4's "config inspection" half. The live handshake is `make smoke-tls`.
	$(PY) scripts/check_tls_config.py

mobile-lint: ## Linters and cross-platform checks for both apps
	cd android && ./gradlew ktlintCheck detekt
	cd ios && swiftlint --quiet
	@echo
	$(PY) scripts/check_status_vocabulary.py
	@echo
	@# The lattice is drawn in Vue, Compose and UIKit from one specification, and no
	@# linter can see across three languages.
	$(PY) scripts/check_patch_lattice.py
	@echo
	@# NFR4: the App Transport Security exception is a debug-only affordance for the local
	@# stack, and a release build that carried one could talk to a dev server in clear text.
	@if plutil -extract NSAppTransportSecurity xml1 -o - ios/Config/Info.plist >/dev/null 2>&1; then \
		echo "FAIL: ios/Config/Info.plist (the RELEASE plist) has an ATS exception"; exit 1; \
	else \
		echo "release Info.plist carries no App Transport Security exception"; \
	fi
