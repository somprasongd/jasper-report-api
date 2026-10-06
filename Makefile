# Day-to-day commands. `make help` lists them.
# Maven needs JDK 21; on macOS pick it automatically, elsewhere set JAVA_HOME yourself.
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null)
ifneq ($(strip $(JAVA_HOME)),)
export JAVA_HOME
endif
MVN ?= ./mvnw

.DEFAULT_GOAL := help
.PHONY: help build test run docker-build up down dev-up dev-down seed api-key

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

build: ## Build target/jasper-report-api-*.jar (JDK 21)
	$(MVN) -B -DskipTests package

test: ## Run all tests (the S3 test needs Docker and is skipped without it)
	$(MVN) -B test

run: ## Run locally from the JAR sources; configure with env vars or config/application.yml
	$(MVN) -B spring-boot:run

docker-build: ## Build the Docker image somprasongd/jasper-report-api:latest
	docker compose build api

up: ## Start the API only (reads .env)
	docker compose up -d --build

down: ## Stop the API (and dev services when started)
	docker compose --profile dev down

dev-up: ## One-command demo: API + PostgreSQL sample data + rustfs + sample report
	scripts/dev-up.sh

dev-down: ## Stop the demo and delete its volumes
	docker compose --profile dev down -v

seed: ## Re-upload samples/reports to the demo rustfs
	scripts/seed-rustfs.sh

api-key: ## Generate an API key: make api-key CLIENT=hosos-web [INDEX=0]
	@scripts/api-key.sh "$(CLIENT)" "$(or $(INDEX),0)"
