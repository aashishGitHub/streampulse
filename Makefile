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
test: ## Fast tests only (everything not named *IT; no Docker)
	./gradlew test

.PHONY: it
it: ## Integration tests: every *IT class (needs Docker)
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
up-core: ## Start Kafka (+ topics) + moto + Postgres + Redis + Keycloak
	$(COMPOSE) --profile core up -d

.PHONY: up-kafka
up-kafka: ## Start only Kafka and create the topics (ADR-0012)
	$(COMPOSE) --profile kafka up -d

.PHONY: seed-aws
seed-aws: ## Create the Kinesis stream in moto (no AWS account needed)
	@AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1 \
	 aws --endpoint-url=http://localhost:5001 kinesis create-stream \
	     --stream-name telemetry-raw-v1 --shard-count 2 2>/dev/null \
	  && echo "created stream telemetry-raw-v1" || echo "stream already exists"

.PHONY: topics
topics: ## Show the Kafka topics kafka-init created
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

.PHONY: up-aws
up-aws: ## Start moto (S3/SQS/Kinesis) + DynamoDB Local (Phase 4+)
	$(COMPOSE) --profile aws up -d

.PHONY: up-obs
up-obs: ## Start Prometheus (host :9091) + Grafana (Tempo/Loki arrive in Phase 8)
	$(COMPOSE) --profile obs up -d

.PHONY: up-full
up-full: ## Start every profile
	$(COMPOSE) --profile core --profile kafka --profile aws --profile obs --profile ui up -d

.PHONY: down
down: ## Stop all containers (keeps volumes)
	$(COMPOSE) --profile core --profile kafka --profile aws --profile obs --profile ui down

.PHONY: nuke
nuke: ## Stop containers AND DELETE ALL DATA volumes
	$(COMPOSE) --profile core --profile kafka --profile aws --profile obs --profile ui down -v

.PHONY: ps
ps: ## Show running containers and their health
	$(COMPOSE) ps

.PHONY: logs
logs: ## Tail logs from all containers
	$(COMPOSE) logs -f --tail=100

# ---------------------------------------------------------------------------
# Running the pipeline (services run on the host; infrastructure in Docker)
#
#   make up-core        infrastructure + topics
#   make run-all        ingest-gateway, stream-processor, sink-writer, query-api in the background
#   make wait-services  block until all four report ready
#   make loadgen        drive load and print the measured numbers
#   make stop-all       stop the four services
#
# Logs: build/logs/<service>.log. Ports: ingest 8080, query-api 8082, processor 8083, sink 8084.
# ---------------------------------------------------------------------------
SERVICES := ingest-gateway stream-processor sink-writer query-api
RATE ?= 2000
DURATION ?= 60

.PHONY: run-ingest run-processor run-sink run-query
run-ingest: ## Run ingest-gateway in the foreground (:8080, gRPC :9090)
	./gradlew :services:ingest-gateway:bootRun

run-processor: ## Run stream-processor in the foreground (:8083)
	./gradlew :services:stream-processor:bootRun

run-sink: ## Run sink-writer in the foreground (:8084)
	./gradlew :services:sink-writer:bootRun

run-query: ## Run query-api in the foreground (:8082)
	./gradlew :services:query-api:bootRun

.PHONY: run-all
run-all: ## Start all four services in the background (logs in build/logs)
	@mkdir -p build/logs build/pids
	@for s in $(SERVICES); do \
	  nohup ./gradlew :services:$$s:bootRun > build/logs/$$s.log 2>&1 & echo $$! > build/pids/$$s.pid; \
	  echo "started $$s (pid $$(cat build/pids/$$s.pid))"; \
	done

.PHONY: wait-services
wait-services: ## Wait (up to 3 min) until every service reports ready
	@for port in 8080 8082 8083 8084; do \
	  printf "waiting for :$$port "; \
	  for i in $$(seq 1 90); do \
	    curl -sf http://localhost:$$port/actuator/health/readiness >/dev/null && { echo ready; break; }; \
	    printf .; sleep 2; \
	    [ $$i -eq 90 ] && { echo " NOT READY; see build/logs"; exit 1; }; \
	  done; \
	done

.PHONY: stop-all
stop-all: ## Stop the four background services
	@for s in $(SERVICES); do \
	  [ -f build/pids/$$s.pid ] && kill $$(cat build/pids/$$s.pid) 2>/dev/null && echo "stopped $$s"; \
	  rm -f build/pids/$$s.pid; \
	done

.PHONY: loadgen
loadgen: ## Drive load: needs LOADGEN_CLIENT_SECRET; RATE=2000 DURATION=60 by default
	./gradlew :tools:loadgen:run --args="--rate $(RATE) --duration $(DURATION)"

.PHONY: demo
demo: up-core run-all wait-services loadgen ## Everything: infrastructure, services, then load

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
