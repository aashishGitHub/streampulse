# Phase 4 — Sink writer: Requirements

**Status:** in progress · **Depends on:** Phase 3 (aggregates on `telemetry.agg.v1`) ·
**Delivers:** every aggregate stored durably exactly once in effect, a fast projection for
readers, and a bounded, backpressured batch writer

## Why

The processor emits one final aggregate per metric per window. Nobody can query a topic, so
something must put those values where a dashboard can read them: a durable store for history
and correctness, a fast store for "latest" and the live feed. Doing that one row at a time
cannot keep up; doing it in batches risks losing or duplicating records when a batch fails
half-way, when the process restarts, or when the store goes away. This phase is the proof that
it does neither. The shapes of both stores are fixed in `hot-store-contract.md`.

## Requirements (EARS; no technology named)

### Correctness: no loss, no double count

- **REQ-SK-01** WHEN an aggregate is received THE SYSTEM SHALL store it durably BEFORE
  reporting it as consumed. `[M2]`
- **REQ-SK-02** WHEN the same aggregate is delivered more than once THE SYSTEM SHALL hold
  exactly one stored value for it. `[M2]`
- **REQ-SK-03** WHEN two computations of the same window arrive, in either order, THE SYSTEM
  SHALL keep the one computed later. `[M2]`
- **REQ-SK-04** WHEN the process fails after a durable write but before consumption was
  reported THE SYSTEM SHALL re-process those aggregates without changing a stored value or
  the number of stored windows. `[M2]`

### Bad records versus a bad store

- **REQ-SK-05** WHEN a record cannot be decoded, or the durable store rejects that record
  deterministically, THE SYSTEM SHALL preserve its original bytes and key in a dead-letter
  destination with the reason, and SHALL continue with the healthy records. `[M2]`
- **REQ-SK-06** WHILE the durable store is unavailable THE SYSTEM SHALL retry without bound
  and with increasing delay, and SHALL NOT dead-letter any healthy record. `[M2]`
- **REQ-SK-07** WHEN a record is stored durably THE SYSTEM SHALL update the fast projection
  (latest value per series, live feed). WHEN the projection fails THE SYSTEM SHALL still
  report the record as consumed, because the projection can be rebuilt from the durable
  store. `[M2]`

### Batch writer

- **REQ-SK-08** THE SYSTEM SHALL group records into batches and write a batch when it reaches
  a size threshold or when a maximum wait has passed since the first record of the batch.
  `[J5]`
- **REQ-SK-09** WHEN the intake buffer stays full for longer than a bounded timeout THE
  SYSTEM SHALL reject the submission with a failure, never block without bound and never drop
  it silently. `[J5] [M4]`
- **REQ-SK-10** WHEN a submission is accepted THE SYSTEM SHALL complete it only after its
  batch is durably written, or fail it explicitly. An accepted submission is never lost
  silently. `[J5] [M2]`
- **REQ-SK-11** WHEN shutdown is requested THE SYSTEM SHALL stop accepting submissions, write
  everything already accepted, then stop, within a bounded time. `[J5]`
- **REQ-SK-12** WHEN a batch write fails THE SYSTEM SHALL retry it a bounded number of times
  with increasing delay and then write its records one at a time to isolate the faulty one.
  WHEN every record fails THE SYSTEM SHALL treat the store as unavailable. `[J5]`

### Operations

- **REQ-SK-13** THE SYSTEM SHALL report freshness (time between a window's end and its
  durable write), intake buffer depth, batch write latency and dead-letter count. `[M4]`
- **REQ-SK-14** THE SYSTEM SHALL report consumption progress only after every record of a
  received group is durably written, so consumer lag measures work not yet stored. `[M4]`
- **REQ-SK-15** THE SYSTEM SHALL expose liveness that does not depend on any store, and
  readiness that reflects whether it can accept work. `[M4]`

## Non-functional

- Batch size 200, max wait 100 ms, buffer 2000, offer timeout 1 s. **Targets** until Phase 9.
- Delivery is at-least-once; the claim is *at-least-once delivery + idempotent write =
  effectively-once*. The live feed (fast projection stream) may therefore contain a record
  more than once; the durable store never does.

## Out of scope

Rebuilding the projection from the durable store (a job, later), schema migrations (Flyway
is deferred; the table is created idempotently at startup), schema-registry framing, the
read API (Phase 5).
