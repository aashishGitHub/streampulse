# StreamPulse

A real-time telemetry ingestion and analytics platform, built in Java 21 + Spring Boot —
**as a learning vehicle**. Every component exists to make one senior-level concept concrete
and demonstrable.

> **What this is:** a portfolio project. The business scenarios are fictional design
> exercises, not production incidents. Performance numbers are targets chosen for the
> exercise until Phase 9 measures the real ones.

---

## The product, in one paragraph

Video players across several apps emit telemetry — `session_start`, `heartbeat`,
`buffering`, `bitrate_change`, `error`. StreamPulse ingests them at high volume, computes
near-real-time metrics (concurrent viewers, rebuffer ratio, error rate) in windows, serves
live dashboards over Server-Sent Events, raises alerts with root-cause suppression, and
keeps a queryable history. Three fictional tenants share it: `streamflix` (general VOD),
`sportsnow` (live sports, spiky, enterprise SLA) and `kidsplus` (small, strict data rules).

---

## Architecture

```
                    ┌──────────────┐
  players / SDKs ──▶│ ingest-      │  REST batch + gRPC stream
  load generator    │ gateway      │  validation · idempotency · rate limit · backpressure
                    └──────┬───────┘
                           │ key = sessionId
                    ┌──────▼──────────────────────┐
                    │  Kafka: telemetry.raw.v1    │  12 partitions · Avro
                    └──┬───────────────────────┬──┘
                       │                       │
         ┌─────────────▼──────────┐   ┌────────▼─────────┐
         │ stream-processor       │   │ sink-writer      │
         │ Kafka Streams, EOS v2  │   │ raw → S3 (NDJSON)│
         │ 10s tumbling windows   │   └────────┬─────────┘
         └─────────────┬──────────┘            │
                       │ telemetry.agg.v1      │
         ┌─────────────▼──────────┐   ┌────────▼──────────────┐
         │ sink-writer            │──▶│ Redis (hot, TTL)      │
         │                        │──▶│ DynamoDB (history)    │
         └─────────────┬──────────┘   └───────────────────────┘
                       │
         ┌─────────────▼──────────┐              ┌──────────────────┐
         │ query-api (WebFlux)    │──── SSE ────▶│ React dashboard  │
         │ REST + live stream     │              └──────────────────┘
         └────────────────────────┘

         ┌────────────────────────┐
         │ control-plane          │  tenants · rules · alert engine
         │ Postgres + outbox      │  BFS root-cause suppression
         └─────────┬──────────────┘
                   │ Debezium CDC
                   ▼
            config.changes.v1 ──▶ every service refreshes its caches
```

**Full plan:** [`../Java/StreamPulse_Project_Plan.md`](../Java/StreamPulse_Project_Plan.md)
· **Study material:** [`../Java/interview-qa/`](../Java/interview-qa/)

---

## Quick start

```bash
./gradlew build   # compile + test everything (no Docker needed yet)
make help         # every available command
make doctor       # check your machine has what this project needs
```

The Gradle **wrapper** is committed, so you do not need Gradle installed — `./gradlew`
downloads the exact pinned version (with a SHA-256 check) on first run. You need **JDK 21**
and, from Phase 2, **Docker**.

---

## Repository layout

| Path | What lives there |
|---|---|
| `build-logic/` | Convention plugins — the build's "house style", written once |
| `gradle/libs.versions.toml` | Every dependency version, in one file |
| `libs/platform/` | Shared kernel: domain primitives + architecture rules → becomes a Spring Boot starter in Phase 2 |
| `libs/contracts/` | Avro event schemas + generated classes; the schema-evolution gate |
| `api/` | OpenAPI (REST) and AsyncAPI (Kafka topic) contracts |
| `specs/` | Spec-driven development: requirements → design → tasks, per phase |
| `docs/adr/` | Architecture Decision Records — *why*, not *what* |
| `docs/learning/` | Guided explanations, especially for the .NET → Java transition |
| `docker/` | Local stack (Kafka, Postgres, Redis, moto…) with compose profiles |
| `MILESTONES.md` | Build log with **verified** command output, not aspirations |

---

## How this repo is built: spec-driven development

Each phase follows the same loop, and nothing is skipped:

```
  1. requirements.md   user stories + acceptance criteria (EARS: WHEN … THE SYSTEM SHALL …)
  2. design.md         flow of control, data flow, sequence diagram, failure modes
  3. contracts         OpenAPI / AsyncAPI / Avro / proto — codegen makes drift impossible
  4. tasks.md          small checklist; each task is "write the failing test, then the code"
  5. tests first       acceptance → unit → slice → integration (Testcontainers)
  6. demo + ADR        verified output pasted into MILESTONES.md
```

The point is not ceremony. It is that **the acceptance criteria are written before the
code**, so "done" is a fact rather than an opinion.

---

## Phases

| Phase | Delivers | Status |
|---|---|---|
| **0** | Build foundation, conventions, domain primitives, architecture rules | ✅ done |
| **1** | Contracts & schemas (Avro, OpenAPI, AsyncAPI) | ✅ done |
| 2 | Ingest gateway (MVC + virtual threads, gRPC, idempotency, backpressure) | ⬜ |
| 3 | Stream processing (Kafka Streams, windowing, exactly-once) | ⬜ |
| 4 | Sinks & storage (Redis, DynamoDB, S3, DLT) | ⬜ |
| 5 | Query API + live SSE | ⬜ |
| 6 | Control plane, Debezium CDC, alert engine | ⬜ |
| 7 | React dashboard | ⬜ |
| 8 | Observability, SLOs, error budgets | ⬜ |
| 9 | Resilience, load testing, JVM profiling | ⬜ |
| 10 | AWS simulation & Terraform | ⬜ |
| 11 | Containers & Kubernetes | ⬜ |
| 12 | Activity feed (the Twitter-style design, implemented) | ⬜ |
| 13 | MCP server / AI tooling | ⬜ |
| 14 | Interview packaging | ⬜ |

---

## If you are reading this to learn

Start with [`docs/learning/00-how-to-read-this-repo.md`](docs/learning/00-how-to-read-this-repo.md).

**Scope note:** this project is about the **Java ecosystem in a cloud environment** —
Spring Boot, Kafka, streaming, storage, observability, AWS, Kubernetes. It is deliberately
*not* algorithm practice. Coding-round preparation lives separately in
[`../Java/interview-qa/17-coding-lru-and-graphs.md`](../Java/interview-qa/17-coding-lru-and-graphs.md).

The code is commented far more heavily than production code should be. That is deliberate:
the comments explain **why** a design was chosen and what the alternatives cost, which is
the part that does not survive in a normal codebase — and the part interviews are actually
about.
