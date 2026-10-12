# ADR-0013 — Postgres first, offsets last: the sink is durable-then-commit

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 4 · **Builds on** ADR-0012

## Context

ADR-0012 stops at the edge of Kafka: exactly-once v2 covers Kafka to Kafka, and nothing that
leaves Kafka. The sink-writer reads `telemetry.agg.v1` and writes to two stores that Kafka
cannot make part of its transaction. Whatever it does, delivery to it is at-least-once, so
correctness has to come from the write, not the delivery.

There are two stores because they answer different questions:

| Store | Role | Lost data means |
|---|---|---|
| Postgres | history, correctness, source of truth | data loss |
| Redis | latest value per series, live feed | a stale dashboard until rebuilt |

## Decision

1. **Postgres is the durable, idempotent store.** The primary key is the aggregate's identity
   (tenant, title, region, metric, window start). The upsert only replaces a row whose
   `computed_at` is older or equal, so replays and reordering cannot move a value backwards.
2. **Redis is a rebuildable projection.** It is written after Postgres. A Redis failure is
   logged, counted, and does not block the acknowledgement, because everything in Redis can
   be recomputed from Postgres. The cost: the Redis view can lag until a rebuild.
3. **Offsets are committed only after the durable write.** The Kafka listener submits every
   record of a poll to the batch writer, waits for all of them on the listener thread, and
   only then acknowledges. A crash anywhere earlier means redelivery, which idempotency
   absorbs. Auto-commit is off; acknowledgement is manual.
4. **A poison record and an outage are different failures and are handled differently.**
   - Poison (undecodable, or the store rejects that one record every time): preserve the
     original bytes and key on `telemetry.agg.v1.dlt` with a `dlt-reason`, and carry on.
   - Outage (the store is down): retry forever with exponential backoff, never dead-letter.
     Dead-lettering a healthy record because Postgres was briefly down would lose data that
     was never bad.
   - Telling them apart: when a batch keeps failing, write its records one at a time. If some
     succeed, the ones that fail are poison. If none succeed, the store is down.

## Consequences

**Positive**
- No loss and no double count in effect, provable by an integration test that fails the ack
  path after the durable write and then counts rows and checks the committed offset.
- Back-pressure is honest: a slow Postgres fills the bounded buffer, the listener stops
  polling, and consumer lag rises, which is the signal to alert on.

**Negative**
- Delivery is at-least-once: the Redis live feed may repeat a record after a redelivery.
- A poison record alone in its batch cannot be told from an outage by the isolation rule. The
  decoder rejects the common deterministic causes before the writer to keep that rare.
- On an outage, isolation costs one connection attempt per record; the pool timeout bounds it.
- `computed_at <= EXCLUDED.computed_at` lets an equal timestamp re-apply. That is deliberate:
  a replay of the same record must be a no-op in effect, not rejected.

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Commit offsets first, write after | A crash loses everything between the commit and the write. |
| Kafka transactions spanning Postgres | Not possible: the database is not a Kafka resource. An outbox or two-phase commit would add machinery this write does not need. |
| Dead-letter after N failed retries | Turns an outage into data loss. |
| Write Redis first | A reader could see a value that Postgres never stored. |
| Auto-commit with `enable.auto.commit=true` | Commits on a timer, independent of whether the write finished. |
