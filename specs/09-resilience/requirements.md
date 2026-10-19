# Phase 9 — Resilience, load testing, JVM profiling: Requirements

**Status:** draft · **Depends on:** Phase 2 (ingest-gateway), Phase 3 (stream-processor), Phase 4
(sink-writer), Phase 5 (query-api), Phase 6 (control-plane, alert-engine), Phase 12
(activity-feed) · **Delivers:** real, measured numbers replacing every target the earlier phases
labelled "Phase 9 measures this," plus proof that the stack's existing resilience mechanisms
(retry, circuit breaker, bulkhead, DLT) actually trip under injected failure.

## Why (3 lines max)

Every earlier phase picked timeouts, pool sizes, window durations and breaker thresholds as
*explainable* targets and said so in writing — `grep -rn "Phase 9"` across this repo returns 30+
such promises. This phase pays them off: it measures the full pipeline under load, breaks each
service's dependency on purpose, and profiles the one correctness question virtual threads raise
(a blocking call pinning a carrier thread) rather than taking it on faith.

## Scope

Full pipeline load test: `ingest-gateway -> Kafka -> stream-processor -> sink-writer -> query-api`,
**plus** `control-plane` (tenant/rule CRUD), `alert-engine` (rule evaluation under load), and
`activity-feed` (fan-out and timeline-read latency). All against the existing local
docker-compose stack (single broker, replication factor 1, single instance per service) — this
measures *this* laptop-scale deployment, not production capacity; see "Out of scope."

## Requirements (EARS, no technology named)

### Load testing — full pipeline

- **REQ-RL-01** WHEN the load generator is run at an increasing send rate THE SYSTEM SHALL report
  the rate at which the ingest entry point's error rate first exceeds 1%, distinguishing
  rate-limit responses from capacity-exhaustion responses. `[M1]`
- **REQ-RL-02** WHEN the ingest entry point is saturated THE SYSTEM SHALL shed load by rejecting
  requests rather than by growing unbounded queues or memory. `[M1]`
- **REQ-RL-03** WHEN a sustained load run completes THE SYSTEM SHALL account for every event sent
  against exactly-once consumption, with no event counted twice and no event silently dropped
  outside a stated, bounded window-close delay.
- **REQ-RL-04** WHEN a sustained load run completes THE SYSTEM SHALL report observed p50/p95/p99
  for: ingest acceptance latency, event-to-aggregate-visible freshness, and query-api read
  latency, each measured, not assumed.

### Load testing — control plane, alert engine, activity feed

- **REQ-RL-10** WHEN tenant and rule CRUD operations are run at increasing concurrency THE SYSTEM
  SHALL report the request rate at which p99 latency crosses its target and the error rate at
  that point.
- **REQ-RL-11** WHEN alert rule evaluation is driven at increasing aggregate throughput THE SYSTEM
  SHALL report the per-evaluation latency distribution and the point at which evaluation falls
  behind its input rate.
- **REQ-RL-12** WHEN a watch-event burst targets a followed account THE SYSTEM SHALL report
  fan-out completion lag as a function of follower count, including the celebrity-threshold
  boundary already defined in Phase 12.

### Resilience — fault injection

- **REQ-RS-01** WHEN the message broker becomes unreachable from a producing service THE SYSTEM
  SHALL apply its configured retry/circuit-breaker/fallback behaviour, and recover automatically
  once the broker returns, with no restart required.
- **REQ-RS-02** WHEN the ingest path's downstream publisher is degraded (elevated latency,
  elevated error rate) THE SYSTEM SHALL open its circuit breaker within the configured window and
  half-open to probe recovery, per ADR-0009's existing targets.
- **REQ-RS-03** WHEN a service's datastore (Postgres or Redis) becomes unreachable THE SYSTEM
  SHALL fail the operations that need it (not hang indefinitely) and recover automatically once
  the datastore returns.
- **REQ-RS-04** WHEN network faults (added latency, a partition) are injected between two
  services THE SYSTEM SHALL be observed to behave as the relevant ADR or ticket already claims
  (timeout fires, breaker opens, consumer lag grows and drains), with the observation recorded
  even where it contradicts the claim.
- **REQ-RS-05** WHEN a service instance is killed mid-processing THE SYSTEM SHALL resume
  processing from its last committed position with no event lost and no duplicate aggregate
  state, per the exactly-once guarantee already claimed in ADR-0012.

### JVM profiling

- **REQ-JP-01** WHEN the ingest-gateway is driven at sustained load with `-Djdk.tracePinnedThreads`
  enabled THE SYSTEM SHALL report whether any virtual thread was pinned to its carrier, and if so,
  the exact call site.
- **REQ-JP-02** WHEN the ingest-gateway and stream-processor are driven at sustained load under a
  sampling profiler THE SYSTEM SHALL produce a CPU and allocation flame graph for each, retained
  as an artifact.

## Non-functional

- All numbers produced by this phase are measurements from one developer machine running the
  full stack in Docker Compose, not a representative production benchmark. State the hardware
  (CPU, RAM, disk) alongside every number.
- 1% error rate, p99 targets, and "within the configured window" above are **targets** for what
  counts as "found the saturation point" — the measured number itself is the deliverable, not a
  pass/fail bar.

## Out of scope

- Real AWS infrastructure, multi-node Kafka, multi-AZ or cross-region behaviour (see
  `docs/multi-region-design.md` and ADR-0003 — design-only, never measured here).
- Kubernetes-level resilience (pod eviction, node loss, HPA) — that is Phase 11's concern once it
  exists; this phase injects faults at the container/process/network level only.
- Changing any production default as a result of a measurement is a follow-up task per finding,
  not part of this phase's definition of done — this phase measures and reports, it does not
  necessarily retune.
- A production capacity plan. This answers "what does this stack do under load," not "what AWS
  instance types and counts does the real system need."
