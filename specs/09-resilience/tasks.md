# Phase 9 — Resilience, load testing, JVM profiling: Tasks

Each line: failing test first, then code. Tick only with passing output pasted into
`MILESTONES.md`.

## Setup

- [ ] T9-01 `requirements.md`, `design.md` written (this spec); ADR for any real decision made
      along the way (e.g. if a threshold changes as a result of a measurement)
- [x] T9-02 `chaos-check.sh` fails (no proxy) → `toxiproxy` in `docker/compose.yml` under a new
      `chaos` profile, proxying Kafka, Postgres, Redis; Kafka `CHAOS` listener advertising the
      proxy; `make up-chaos` / `chaos-check` / `down-chaos`. Mutation: proxy repointed at the
      internal listener → advertised-address check red; restored → green

## Full-pipeline load test

- [x] T9-10 `RampPlanTest`, `SaturationFinderTest`, `RampRunnerTest`, `RampReportFormatterTest`,
      `ArgParserTest`, `RunnerTest` fail (types and flags absent) → `RampPlan`, `StepResult`,
      `SaturationFinder`, `SaturationReport`, `RampRunner`, `RampReportFormatter`, `--ramp-to` /
      `--ramp-step` / `--step-duration`, and a no-backoff `Runner` constructor. Reports the first
      step whose error rate exceeds 1%, split 429 (quota) vs 503 (capacity), and warns when the
      client could not offer the asked-for rate. Mutations: backoff flag removed,
      `>` to `>=`, stop-at-first-breach removed; each went red, restored, green.
      Spec said `Pacing`/`RunSummary` and `IngestSaturationTest`; separate small classes read
      better than growing those two, and "memory bound" cannot be asserted from the client (see T9-11)
- [x] T9-11 ramp run for real against the local stack; output pasted into `MILESTONES.md` with
      hardware spec. Ingest only (no downstream services ran). Default config stops at the
      per-tenant quota (429, about 3,000/s); with the quota lifted, clean at 40,000 events/s and
      503s from 45,000. Heap and threads sampled during the 503 phase (REQ-RL-02): bounded over a
      short run. Which mechanism issued the 503s is NOT proven (breaker ruled out, permit bound
      likely); see MILESTONES
- [x] T9-12 end-to-end accounting over whole runs, using the existing `scripts/accounting-check.py`
      against the live stack instead of a new `EndToEndAccountingIT` (a Testcontainers version
      would need Kafka, Postgres, Redis and four services). 2,000 and 30,000 events, retries
      re-sent: every closed window matches on count, sessions and error ratio. Mutation (one
      event under-counted in the script's bookkeeping) went red on that window; restored.
      Happy path only: accounting under a crash is T9-34
- [ ] T9-13 freshness/latency run repeated at a second rate (not just the one Phase 3 number);
      p50/p95/p99 for ingest, freshness, query-api read, pasted into `MILESTONES.md`

## Control plane / alert engine / activity feed load

- [ ] T9-20 `ControlPlaneDriverTest` fails → `ControlPlaneDriver` (tenant/rule CRUD at increasing
      concurrency)
- [ ] T9-21 `AlertDriverTest` fails → `AlertDriver` (synthetic aggregates at increasing rate,
      measures evaluation-to-alert-state-change latency)
- [ ] T9-22 `WatchBurstDriverTest` fails → `WatchBurstDriver` (fan-out lag vs. follower count,
      crossing the Phase 12 celebrity threshold)
- [ ] T9-23 all three run for real; output (rate vs. p99, rate vs. error rate, lag vs. follower
      count) pasted into `MILESTONES.md`

## Resilience — fault injection

- [ ] T9-30 `BrokerDownResilienceIT` fails (no toxiproxy harness) → kill the Kafka proxy mid-run,
      assert the ingest-gateway circuit breaker opens per ADR-0009's thresholds and the
      Redis-fallback path (ADR-0007) keeps publishing; restore, assert recovery with no restart
- [ ] T9-31 `ControlPlaneDatastoreDownIT` fails → kill the Postgres proxy, assert fast failure
      (HikariCP connection-timeout, not a hang) and automatic recovery
- [ ] T9-32 `ActivityFeedDatastoreDownIT` fails → kill the Redis proxy, assert per-request failure
      (no hang) and automatic recovery
- [ ] T9-33 `AlertEngineClientFaultIT` fails → partition alert-engine from query-api/control-plane,
      assert the HTTP clients time out and surface a problem, not a hang
- [ ] T9-34 processor crash mid-window. MEASURED, AND IT FAILS: with `kill -9` and a 40-50 s outage,
      events are dropped as expired-window records (1,482 of 4,000 and 2,554 of 8,000; each matches
      the processor's own WARN count exactly). No duplicates. Run through the live stack with
      `scripts/accounting-check.py --at`, not an IT. Stays unticked until the defect is fixed or
      the guarantee is rewritten in an ADR; the failing run is the red step. See MILESTONES
- [ ] T9-35 `BrokerKilledDuringLoadIT` fails → kill the Kafka container itself under load, assert
      consumer lag grows then fully drains once it returns, with the single-broker availability
      gap stated plainly in the result, not hidden

## JVM profiling

- [ ] T9-40 `PinnedThreadLoadIT` fails (no run mode wires the flag) → ingest-gateway run with
      `-Djdk.tracePinnedThreads=full` under the T9-11 load; assert the log is empty, or if not,
      capture and name the exact call site (pays off ADR-0006's stated measurement)
- [ ] T9-41 async-profiler (or JFR) CPU + allocation flame graphs captured for ingest-gateway and
      stream-processor under the T9-11 load; artifacts saved under `docs/profiling/` and linked
      from `MILESTONES.md`

## Close-out

- [ ] T9-50 every number produced above replaces its "target, not measured" label in the specs
      that stated it (`grep -rn "Phase 9"` the whole repo and resolve or explicitly re-defer each
      hit)
- [ ] T9-51 verified output into `MILESTONES.md`; README phase table flipped

## Not done (state here if cut, never hide)

(fill in as work proceeds — e.g. if toxiproxy setup proves too unreliable locally and a fault is
instead done with raw `docker pause`/`iptables`, say so here with the reason)
