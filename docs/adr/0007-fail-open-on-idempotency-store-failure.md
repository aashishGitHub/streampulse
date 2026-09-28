# ADR-0007 — Fail open when the idempotency store is unavailable

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 2

## Context

Deduplication depends on Redis. Redis will, at some point, be unavailable — a restart, a
network partition, a pool exhaustion. The gateway must decide, in advance, what to do when
it cannot tell whether an event is a duplicate.

There is no option that is simply correct. There are two options with different losses.

## Decision

**Fail open**: publish the events, increment `streampulse.ingest.idempotency.unavailable`,
and report `degraded: true` in the response. Configurable via
`streampulse.ingest.fail-open-on-idempotency-error`, which defaults to `true`.

## The trade

| | Fail OPEN (chosen) | Fail CLOSED |
|---|---|---|
| Redis down | duplicates enter the pipeline | ingest returns 503; clients retry |
| Worst case | inflated metrics for the outage window | **telemetry lost** if clients give up |
| Recoverable? | yes — duplicates can be reconciled later | no — the data was never sent |
| Blast radius | downstream aggregates | every player, immediately |

The deciding question is: **which loss is recoverable?**

A duplicate is a wrong number that can be reconciled — downstream aggregation already
tolerates redelivery, since Kafka is at-least-once and consumers are idempotent by design. A
gap in telemetry is gone forever: the player has moved on, the session ended, nobody is
going to re-emit it.

Note also that failing closed does not actually prevent duplicates. It converts them into
**client retries**, which arrive later and — with Redis still down — are not deduplicated
either. You get the duplicates anyway, plus an outage.

## What makes this acceptable

Failing open is only defensible because of three things that are true here:

1. **Producer idempotence is still on.** `enable.idempotence=true` means Kafka-level retries
   cannot duplicate; only genuine client resubmissions can.
2. **Duplicates are visible.** The counter is an SLI, and Phase 8 alerts when it rises, so
   the degradation is never silent.
3. **The window is bounded.** Redis outages are minutes, not days, and the response carries
   `degraded: true` so a client can note it.

## This would be the wrong decision for

- **Payments, orders, anything financial.** A duplicate charge is not reconcilable in the
  way a duplicate metric is. Fail closed, every time.
- **A system without idempotent consumers.** Our duplicates are absorbed downstream; if they
  were not, this would just move the problem.

The flag exists so the answer can change per deployment without a code change — and so the
decision is visible in configuration rather than buried in a catch block.

## Consequences

**Positive**
- A Redis outage degrades the pipeline instead of stopping it.
- The degradation is measured, alertable, and reported to the caller.
- The choice is explicit and reversible, not an accident of exception handling.

**Negative**
- Metrics can be wrong during an outage, and someone may act on them. Mitigation: the
  counter is on the Phase 8 dashboard next to the metrics it affects.
- It is easy to forget this is happening. Mitigation: `degraded: true` in the response body,
  and a WARN log line — deliberately not ERROR, because this is a handled degradation and
  paging someone for it would be crying wolf.
