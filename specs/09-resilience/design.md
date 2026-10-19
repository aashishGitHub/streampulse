# Phase 9 — Resilience, load testing, JVM profiling: Design

## Flow of control and data

```
                         ┌─────────────────────────── loadgen (extended) ───────────────────────────┐
                         │  IngestDriver (exists)   ControlPlaneDriver (new)   AlertDriver (new)      │
                         │  WatchBurstDriver (new)                                                    │
                         └──────┬───────────────────────┬───────────────────────┬────────────────────┘
                                │ HTTP/gRPC :8080         │ HTTP :8085             │ synthetic watch events
                                v                        v                        v
                        ingest-gateway            control-plane            activity-feed :8087
                                │ produce                │ outbox+CDC
                                v                        v
   telemetry.raw.v1 (Kafka) ───┴──────────────────────→ config.changes.v1
       │ consume
       v
  stream-processor ──windowed agg──▶ telemetry.agg.v1 ──▶ sink-writer ──▶ Postgres / Redis
                                             │                                    │
                                             v                                    v
                                      alert-engine :8086                  query-api :8082
                                       (RuleBook, evaluate)              GET /overview, SSE

                         ┌───────────────── toxiproxy (new, docker-compose) ─────────────────┐
                         │  proxies placed in front of: Kafka broker, Postgres, Redis,        │
                         │  alert-engine's HTTP clients to query-api/control-plane            │
                         │  fault types: latency, bandwidth cap, full partition (close conns)  │
                         └──────────────────────────────────────────────────────────────────┘

  JVM profiling taps ingest-gateway and stream-processor directly (JFR / async-profiler attach,
  and -Djdk.tracePinnedThreads on ingest-gateway's JVM flags) — no new component, a run mode.
```

Every arrow above already exists from phases 2–6/12. This phase adds: three new `loadgen` drivers,
one new compose service (`toxiproxy`), and two profiling run modes. No production code changes
unless a measurement forces one, and that is a separate follow-up task per finding (see
requirements.md, out of scope).

## Fault injection points

| Point | Fault | Proxied via | Expected behaviour (per existing ADR/spec) |
|---|---|---|---|
| ingest-gateway -> Kafka | broker unreachable | toxiproxy `down` | `ResilientEventPublisher` circuit breaker opens per ADR-0009's thresholds (sliding window 20, failure-rate 50%, wait 30s); Redis-down fallback still publishes (ADR-0007) |
| ingest-gateway -> downstream publisher | elevated latency / errors | toxiproxy `latency`/`timeout` | breaker opens within the configured window; half-open probes after 30s (ADR-0009) |
| any JPA service -> Postgres | unreachable | toxiproxy `down` | HikariCP connection-timeout (2s, control-plane) fails fast; readiness probe flips; recovers without restart |
| activity-feed -> Redis | unreachable | toxiproxy `down` | `RedisTimelineStore` read fails per-request, does not hang; recovers on reconnect |
| alert-engine -> query-api / control-plane | partition | toxiproxy `down` | `HttpQueryApiClient`/`HttpAlertEngineClient` (mcp-server's clients) time out per their configured timeout, surfaced as a problem+json, not a hang |
| a stream-processor instance | process killed mid-window | `docker kill` | Kafka Streams rebalances; exactly-once state (ADR-0012) means no double-counted or lost aggregate on restart |
| Kafka broker | killed mid-run | `docker kill` | producers/consumers retry per their configured backoff; consumer lag grows then drains once the broker returns (single broker, so this is also an availability gap — expected and stated, not hidden) |

## Load drivers (new, in `tools/loadgen`)

| Driver | Target | What it reports |
|---|---|---|
| `ControlPlaneDriver` | `POST/PUT/DELETE /v1/admin/tenants`, `/v1/admin/.../rules` | p50/p95/p99 and error rate vs. concurrency |
| `AlertDriver` | synthetic `telemetry.agg.v1` records at increasing rate, reads `alerts.v1`/SSE for evaluation latency | time from aggregate produced to alert state change observed |
| `WatchBurstDriver` | `POST` watch events for one followed account at increasing follower count | fan-out completion lag vs. follower count, crossing the celebrity threshold (Phase 12) |

Each driver reuses `BucketHistogram`/`Percentiles`/`ReportFormatter` already in `tools/loadgen` —
no new reporting format.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Ingest rate exceeds capacity | 503s rise, no unbounded queueing | `RampRunnerTest` / `SaturationFinderTest` prove the search finds and names the breach; the bounded-memory half is an observation in T9-11 (ingest JVM metrics), not a unit test |
| Kafka broker down during ingest | breaker opens, Redis-fallback publish continues | `BrokerDownResilienceIT` (new, toxiproxy) |
| Postgres down during control-plane write | 503/503-equivalent, no hang, recovers on reconnect | `ControlPlaneDatastoreDownIT` (new, toxiproxy) |
| Redis down during feed read | per-request failure, no hang | `ActivityFeedDatastoreDownIT` (new, toxiproxy) — extends the existing `reportsA503WhenAStoreIsDown` unit test to a real injected fault |
| stream-processor instance killed mid-window | claimed: no double count, no loss. **Observed (kill -9, 40-50 s outage): no double count, but on-time events dropped as expired-window records** | `scripts/accounting-check.py --at` against the live stack; MILESTONES T9-34. A regression test exists only once a fix does |
| Virtual thread pinned by a blocking call | detected and localized | `PinnedThreadLoadIT` (new) — runs ingest-gateway with `-Djdk.tracePinnedThreads`, asserts the log is empty or names the exact call site if not |

## Decisions

Links to ADR-0006 (virtual threads over WebFlux — this phase pays off its stated measurement),
ADR-0009 (circuit breaker thresholds — this phase proves or revises them), ADR-0012 (exactly-once
— this phase is the first to kill a process mid-window to test it), ADR-0003 (local AWS
simulation proves API-level behaviour only — same caveat applies to every load number here: one
laptop, one broker, not a production topology).
