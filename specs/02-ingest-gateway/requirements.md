# Phase 2 — Ingest Gateway: Requirements

**Status:** in progress
**Depends on:** Phase 1 (contracts)
**Delivers:** the front door — the first running service

---

## Why this phase exists

Everything upstream of Kafka happens here. The ingest gateway is where correctness is
decided: if it loses an event, no downstream cleverness recovers it; if it accepts a
duplicate, every aggregate is wrong; if it buffers without bound under load, it dies with
the events still in memory.

Three properties define it, and each is a deliberate answer to a failure mode:

| Property | The failure it prevents |
|---|---|
| **Idempotent** | a retried request after a lost response counted twice |
| **Backpressured** | unbounded buffering, which relocates failure to the heap and makes it worse |
| **Ordered by key** | `session_start → heartbeat → session_end` arriving out of order |

This is also where Spring Boot enters the codebase, and where the concurrency model becomes
a real decision rather than a topic.

---

## User stories

- **As a player SDK**, I want to send a batch of events and get a clear answer, so I know
  whether to retry or move on.
- **As a player SDK**, I want a retry after a network failure to be recognised as the same
  batch, so I do not corrupt the metrics by retrying.
- **As an SRE**, I want the gateway to reject load it cannot handle, with a `Retry-After`,
  rather than accepting it and dying.
- **As a tenant**, I want my quota enforced so a noisy neighbour cannot consume the pipeline.
- **As a security reviewer**, I want the tenant taken from the verified JWT, never from the
  request body.
- **As a smart-TV OEM**, I want a long-lived binary stream rather than repeated HTTP batches,
  because handshakes and JSON cost device battery.

---

## Functional requirements

### Ingest endpoint

- **REQ-IG-01** THE SYSTEM SHALL expose `POST /v1/events:batch` accepting 1–500 events,
  implementing the contract in `api/openapi/ingest-v1.yaml`.
- **REQ-IG-02** WHEN a batch exceeds 500 events THE SYSTEM SHALL return `413` with a
  ProblemDetail, without processing any of it.
- **REQ-IG-03** THE SYSTEM SHALL accept or reject a batch **atomically**. Partial success is
  not supported, because it forces every client to implement per-event retry bookkeeping.
- **REQ-IG-04** WHEN any event fails validation THE SYSTEM SHALL return `400` with a
  ProblemDetail naming the offending index and field.
- **REQ-IG-05** WHEN a batch is accepted THE SYSTEM SHALL return `202` with counts of
  published and duplicate events. `202`, not `201`: the events are durable in Kafka but not
  yet processed, and claiming otherwise would be a lie a client might act on.
- **REQ-IG-06** Every error response SHALL be RFC 7807 `application/problem+json` and carry
  the `traceId`.

### Identity and isolation

- **REQ-IG-07** THE SYSTEM SHALL authenticate with a JWT bearer token and derive `tenantId`
  from a verified claim.
- **REQ-IG-08** THE SYSTEM SHALL **ignore** any tenant field in the request body. Accepting
  it would let any authenticated caller write into another tenant.
- **REQ-IG-09** WHEN the token is missing, expired or malformed THE SYSTEM SHALL return
  `401`, and `403` when the tenant is not permitted.

### Idempotency

- **REQ-IG-10** THE SYSTEM SHALL deduplicate by `eventId` using an atomic claim
  (Redis `SET NX EX`), namespaced per tenant.
- **REQ-IG-11** WHEN the same batch is submitted twice THE SYSTEM SHALL publish each event
  exactly once and report the rest as `duplicates`, still returning `202`.
- **REQ-IG-12** WHEN two identical batches arrive **concurrently** THE SYSTEM SHALL still
  publish each event exactly once. The claim must be atomic, not check-then-set.
- **REQ-IG-13** Idempotency keys SHALL expire after a configurable TTL (default 24h), because
  an unbounded dedup set grows forever.
- **REQ-IG-14** WHEN Redis is unavailable THE SYSTEM SHALL **fail open** — publish anyway and
  increment a metric. At-least-once with duplicates is better than losing telemetry; this is
  a deliberate trade recorded in an ADR.

### Publishing

- **REQ-IG-15** THE SYSTEM SHALL publish to `telemetry.raw.v1` keyed by `sessionId`, via an
  `EventPublisher` **port** so the Kinesis adapter can be added in Phase 10 without touching
  business logic.
- **REQ-IG-16** The producer SHALL set `enable.idempotence=true` and `acks=all`, so a
  producer-side retry cannot duplicate or reorder a record.
- **REQ-IG-17** THE SYSTEM SHALL return `202` only after the broker acknowledges. Returning
  before the ack would make the `202` meaningless.
- **REQ-IG-18** Events SHALL be serialized as Avro using the Phase 1 schemas.

### Backpressure and resilience

- **REQ-IG-19** THE SYSTEM SHALL bound in-flight batches with a semaphore. WHEN no permit is
  available within a short timeout it SHALL return `503` + `Retry-After` rather than queueing.
- **REQ-IG-20** THE SYSTEM SHALL enforce a per-tenant rate limit and return `429` +
  `Retry-After` when exceeded. `429` (your quota) and `503` (our capacity) are deliberately
  distinct so a client can tell whose problem it is.
- **REQ-IG-21** A Resilience4j circuit breaker and time limiter SHALL guard the publisher.
- **REQ-IG-22** `Retry-After` SHALL be documented as requiring client-side jitter; without it
  every rejected client retries in lockstep and the next spike is worse than the first.

### Concurrency model

- **REQ-IG-23** THE SYSTEM SHALL run on **virtual threads**, and the ADR SHALL record why
  this rather than WebFlux for a blocking-I/O-bound workload.
- **REQ-IG-24** THE SYSTEM SHALL fan out independent work with `CompletableFuture` on a named
  executor, never the common `ForkJoinPool`.

### Operability

- **REQ-IG-25** THE SYSTEM SHALL expose Actuator health with **separate** liveness and
  readiness groups. Readiness reflects dependencies; liveness must NOT, or a dependency
  outage triggers a restart loop that makes it worse.
- **REQ-IG-26** THE SYSTEM SHALL expose Prometheus metrics: accepted, duplicate, rejected
  events, in-flight permits, publish latency.
- **REQ-IG-27** THE SYSTEM SHALL drain in order on SIGTERM per BS-01, and SHALL NOT
  acknowledge a batch it has not durably published.

---

## Non-functional requirements

| ID | Requirement | Target | Verified by |
|---|---|---|---|
| NFR-IG-01 | p99 latency for a 100-event batch | < 150 ms local | Phase 9 load test |
| NFR-IG-02 | Sustained throughput | ≥ 5,000 events/s local | Phase 9 |
| NFR-IG-03 | Zero loss on graceful shutdown under load | 0 | `RollingRestartChaosIT` |
| NFR-IG-04 | Startup to ready | < 5 s | measured in CI |

> Targets chosen for the exercise; Phase 9 replaces them with measurements.

---

## Out of scope for Phase 2

- Consuming the events (Phase 3)
- Kinesis adapter (Phase 10)
- Kubernetes deployment (Phase 11)
- The load test itself (Phase 9)

---

## Acceptance checklist

- [ ] Service boots; `/actuator/health/readiness` reports UP
- [ ] A valid batch returns `202` and lands on `telemetry.raw.v1` keyed by `sessionId`
- [ ] Resubmitting the same batch reports duplicates and publishes nothing new
- [ ] Two identical batches sent **concurrently** publish each event exactly once
- [ ] An oversized batch returns `413`; an invalid one returns `400` naming the field
- [ ] A request with no token returns `401`; the body cannot override the tenant
- [ ] Saturating the semaphore returns `503` + `Retry-After`
- [ ] Exceeding the tenant quota returns `429` + `Retry-After`
- [ ] Redis down → events still publish, `idempotency.unavailable` metric increments
- [ ] Verified output in `MILESTONES.md`
