# Numbers you may quote

Quote numbers from this page only. Each one is one of three kinds:

- **measured**: real terminal output, recorded in `MILESTONES.md` (the section is named).
- **target**: a value chosen to be explainable and set in config or a spec; not measured.
- **assumption**: arithmetic from an interview prompt, not about this system's behaviour.

Every measured number comes from **one developer machine** (Apple M2 Pro, 12 cores, 32 GiB for
Phase 9), a single Kafka broker with replication factor 1, and Docker Compose. Say that when you
quote one. None is a production figure. `scripts/check-interview-pack.py` fails if a measured row
does not name a real `MILESTONES.md` heading.

## Measured

| Claim | Value | Kind | Source |
|---|---|---|---|
| Heartbeat event size, Avro | 134 bytes (JSON about 409, 67% smaller) | measured | MILESTONES: Measured — event size |
| Ingest at 1,500 events/s (Phase 3 run) | p50 50.0 ms, p95 57.0 ms, p99 68.7 ms | measured | MILESTONES: Day 5 · the whole pipeline, running |
| Steady-state freshness, Phase 3 | 5.2 to 5.4 s per window (9 windows) | measured | MILESTONES: Day 5 · the whole pipeline, running |
| Ingest saturation with the quota lifted | clean at 40,000 events/s; 503s from 45,000 (11.8% errors) | measured | MILESTONES: Run B — quota effectively removed |
| Default per-tenant quota as a ceiling | 429s from 3,000 events/s (33.4% refused; 1 - 2000/3000 predicts 33.3%) | measured | MILESTONES: Run A — default config |
| Ingest JVM while refusing 105,800 events | heap 45.5 to 313 MiB, rising and falling; threads 34 to 35 | measured | MILESTONES: REQ-RL-02: what the ingest JVM did while refusing |
| Ingest at 1,000 events/s, full pipeline | p50 52.9, p95 59.4, p99 76.5 ms | measured | MILESTONES: Phase 9 · T9-13 |
| Ingest at 2,000 events/s, two runs | p99 70.9 ms and 59.0 ms (run-to-run noise about 12 ms) | measured | MILESTONES: Phase 9 · T9-13 |
| query-api overview read under write load | p50 about 24 ms, p99 27 to 30 ms, at about 52 req/s | measured | MILESTONES: Phase 9 · T9-13 |
| Freshness, full pipeline, 1,000 and 2,000 events/s | 5.2 to 5.4 s per window; all rows of a window within 0.1 s | measured | MILESTONES: Phase 9 · T9-13 |
| End-to-end accounting, happy path | 2,000 and 30,000 events: every closed window exact; 400 and 6,000 re-sent duplicates not double-counted | measured | MILESTONES: Phase 9 · T9-12 |
| Processor `kill -9` mid-window, outage 40 to 50 s | 1,482 of 4,000 and 2,554 of 8,000 events dropped as expired-window records | measured | MILESTONES: Phase 9 · T9-34 |
| Terraform against moto | 13 resources applied, read back, destroyed; second plan shows 3 changes (moto re-serialisation) | measured | MILESTONES: Phase 10 — AWS simulation |
| Control plane / alert engine tests | 102 and 191 tests, 0 failures | measured | MILESTONES: Phase 6 — Control plane |
| Dashboard tests | 258 unit, 17 Playwright end-to-end (mocked backends) | measured | MILESTONES: Phase 7 — React dashboard |
| Activity feed tests | 165 tests, 0 failures | measured | MILESTONES: Phase 12 — Activity feed |
| MCP server tests | 87 tests, 0 failures | measured | MILESTONES: Phase 13 — MCP server |
| Incremental build | about 1 s (`UP-TO-DATE`) | measured | MILESTONES: Measurements |

## Targets (configured, never measured as a limit)

| Claim | Value | Kind | Source |
|---|---|---|---|
| Event-time window and grace | 10 s window, 5 s grace (freshness floor 15 s from window start) | target | `services/stream-processor/src/main/resources/application.yml` |
| Max future skew of an event | 5 min | target | `services/stream-processor/src/main/resources/application.yml` |
| Per-tenant quota | refill 2,000 events/s, burst 10,000 | target | `services/ingest-gateway/src/main/resources/application.yml` |
| Ingest backpressure | 64 in-flight batches, 250 ms permit wait, then 503 | target | `services/ingest-gateway/src/main/resources/application.yml` |
| Publisher circuit breaker | window 20 calls, min 10, 50% failure or slow, slow above 2 s, open 30 s, 5 probes, call timeout 5 s | target | `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md` |
| Publisher retry | 3 attempts, 100 ms then 200 ms, plus or minus 50% jitter | target | `services/ingest-gateway/src/main/resources/application.yml` |
| Max batch size | 500 events per request | target | `services/ingest-gateway/src/main/resources/application.yml` |
| Sink batch writer | 200 rows or 100 ms, queue 2,000, offer timeout 1 s | target | `services/sink-writer/src/main/resources/application.yml` |
| Overview request budget | 300 ms, on an 8-thread bounded executor (queue 16) | target | `services/query-api/src/main/resources/application.yml` |
| SSE stream | heartbeat 15 s, coalesce 250 ms, replay limit 1,000 | target | `services/query-api/src/main/resources/application.yml` |
| Alert feed | heartbeat 15 s, replay buffer 1,000, subscriber buffer 256 | target | `services/alert-engine/src/main/resources/application.yml` |
| Control-plane connection pool | max 10, min idle 2, connection timeout 2 s | target | `services/control-plane/src/main/resources/application.yml` |
| Ingest latency NFR | p99 under 150 ms for a 100-event batch, locally | target | `specs/02-ingest-gateway/requirements.md` |
| Ingest throughput NFR | at least 5,000 events/s locally (T9-11 measured ingest alone well above this) | target | `specs/02-ingest-gateway/requirements.md` |

## Assumptions (interview arithmetic, not the system)

| Claim | Value | Kind | Source |
|---|---|---|---|
| Prompt scale used in the spoken system-design answer | 10 M viewers, one heartbeat per 10 s each, so about 1 M events/s at peak | assumption | `docs/script.md` |
| Aggregate volume at that scale | 1,000 titles x 10 regions x 2 metrics = 20,000 records per 10 s window, about 2,000/s (a 500x reduction) | assumption | `docs/script.md` |
| Bytes per event in that arithmetic | about 1 KB, so about 1 GB/s. **But the measured Avro heartbeat is 134 bytes**, which puts it nearer 134 MB/s before compression | assumption | `docs/script.md` |
| Dedup state at that scale | 30 s retention x 1 M events/s = about 30 M ids held | assumption | `docs/script.md` |

## How to say a number out loud

> "On my laptop, single broker, with the per-tenant quota lifted, the ingest gateway alone ran
> clean at 40,000 events a second and started shedding with 503s at 45,000. That is one run, and
> the load generator shared the CPU, so the real ceiling is probably higher. I would not quote it
> as capacity."

Never say "it handles 45k events per second" without the conditions.
