# ============================================================================
#  StreamPulse — task runner
#
#  Why a Makefile when Gradle exists? Because not everything is a Gradle task:
#  Docker, Terraform and demo scripts are not. One entry point beats a README
#  full of commands people copy incorrectly.
#
#  Run `make` or `make help` to list everything.
# ============================================================================

.DEFAULT_GOAL := help
SHELL := /bin/bash
COMPOSE := docker compose -f docker/compose.yml

# ---------------------------------------------------------------------------
# help — self-documenting. Any target with a `## comment` appears below.
# ---------------------------------------------------------------------------
.PHONY: help
help: ## Show this help
	@echo ""
	@echo "StreamPulse — available commands"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ---------------------------------------------------------------------------
# Build & test  (no Docker required)
# ---------------------------------------------------------------------------
.PHONY: build
build: ## Compile everything and run all tests
	./gradlew build

.PHONY: test
test: ## Fast tests only (unit + architecture, no Docker)
	./gradlew test

.PHONY: it
it: ## Integration tests (needs Docker; arrives in Phase 2)
	./gradlew integrationTest

.PHONY: format
format: ## Auto-format all source (run before committing)
	./gradlew spotlessApply

.PHONY: check
check: ## Everything CI runs: format check + tests + architecture rules
	./gradlew spotlessCheck build

.PHONY: clean
clean: ## Delete all build output
	./gradlew clean

.PHONY: coverage
coverage: ## Run tests and open the coverage report
	./gradlew test jacocoTestReport
	@echo "Report: libs/platform/build/reports/jacoco/test/html/index.html"

# ---------------------------------------------------------------------------
# Local infrastructure (Docker Compose profiles)
#
# Profiles let you start ONLY what the current phase needs. Running the whole
# estate for a unit test wastes ~10 GB of RAM and several minutes.
# ---------------------------------------------------------------------------
.PHONY: up-core
up-core: ## Start moto (Kinesis/S3/SQS) + Postgres + Redis + Keycloak
	$(COMPOSE) --profile core up -d

.PHONY: up-kafka
up-kafka: ## Also start Kafka, for the `kafka` profile comparison (ADR-0008)
	$(COMPOSE) --profile kafka up -d

.PHONY: seed-aws
seed-aws: ## Create the Kinesis stream in moto (no AWS account needed)
	@AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1 \
	 aws --endpoint-url=http://localhost:5001 kinesis create-stream \
	     --stream-name telemetry-raw-v1 --shard-count 2 2>/dev/null \
	  && echo "created stream telemetry-raw-v1" || echo "stream already exists"

.PHONY: up-aws
up-aws: ## Start moto (S3/SQS/Kinesis) + DynamoDB Local (Phase 4+)
	$(COMPOSE) --profile aws up -d

.PHONY: up-obs
up-obs: ## Start Prometheus + Grafana + Tempo + Loki (Phase 8+)
	$(COMPOSE) --profile obs up -d

.PHONY: up-full
up-full: ## Start every profile
	$(COMPOSE) --profile core --profile aws --profile obs --profile ui up -d

.PHONY: down
down: ## Stop all containers (keeps volumes)
	$(COMPOSE) down

.PHONY: nuke
nuke: ## Stop containers AND DELETE ALL DATA volumes
	$(COMPOSE) down -v

.PHONY: ps
ps: ## Show running containers and their health
	$(COMPOSE) ps

.PHONY: logs
logs: ## Tail logs from all containers
	$(COMPOSE) logs -f --tail=100

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------
.PHONY: doctor
doctor: ## Check your machine has what this project needs
	@echo "Java:"   && java -version 2>&1 | head -1
	@echo "Docker:" && (docker --version 2>/dev/null || echo "  NOT FOUND (needed from Phase 2)")
	@echo "Compose:"&& (docker compose version 2>/dev/null | head -1 || echo "  NOT FOUND")
	@echo "Gradle:" && ./gradlew --version 2>/dev/null | grep -E '^Gradle' || echo "  wrapper will download it"

.PHONY: modules
modules: ## List Gradle modules
	./gradlew -q projects
