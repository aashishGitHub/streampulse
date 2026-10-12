# Phase 3 — Stream processor: Requirements

**Status:** implemented (Day 2); REQ-SP-11 verified end to end for the happy path only, not for a mid-window crash · **Depends on:** Phase 1 (contracts), Phase 2 (ingest) · **Delivers:**
windowed aggregates of raw telemetry, one final record per metric per window

## Why

Raw events arrive at up to a million per second at a live-sports peak. Nobody can read that,
and storing every event in a hot store is unaffordable. Dashboards need *"how many viewers,
what error rate, per title, per region, per 10 seconds"*. This phase turns the firehose into
that, correctly, even when events arrive late, out of order, or twice.

## Requirements (EARS; no technology named)

### Validation

- **REQ-SP-01** WHEN an input record cannot be decoded THE SYSTEM SHALL route the original
  bytes, unchanged, to the raw dead-letter destination with the reason, and continue. `[M2]`
- **REQ-SP-02** WHEN a decoded event is invalid (blank identifier, or an event time further
  in the future than the permitted skew) THE SYSTEM SHALL route it to the same dead-letter
  destination with the reason, and exclude it from every aggregate. `[M2]`
- **REQ-SP-03** THE SYSTEM SHALL treat an event whose type it does not recognise as valid and
  count it toward totals, so adding an event type never breaks processing. `[M7]`

### Aggregation

- **REQ-SP-04** THE SYSTEM SHALL aggregate valid events into fixed 10-second windows by
  **event time**, per tenant, title and region. `[SD1]`
- **REQ-SP-05** THE SYSTEM SHALL produce, per window, a concurrent-viewers value (distinct
  sessions seen) and an error-rate value (error events ÷ all events), each with its sample
  count. `[SD1] [R2]`
- **REQ-SP-06** THE SYSTEM SHALL count an event identifier at most once per window, so a
  duplicate published upstream does not inflate a metric. `[M2]`
- **REQ-SP-07** WHEN an event arrives after its window but within the grace period THE SYSTEM
  SHALL include it in that window's value. `[SD1]`
- **REQ-SP-08** WHEN an event arrives after its window's grace period has passed THE SYSTEM
  SHALL drop it from aggregation and count the drop in a metric, never silently. `[SD1] [R2]`

### Emission

- **REQ-SP-09** THE SYSTEM SHALL publish exactly one final record per metric per window,
  and only after the window and its grace period have closed. `[M2]`
- **REQ-SP-10** THE SYSTEM SHALL key each published record by tenant, title, region, metric
  and window start, so a consumer can upsert by key. `[M2] [M3]`
- **REQ-SP-11** WHEN the processor fails and restarts THE SYSTEM SHALL NOT publish a window
  twice nor lose one it had already accepted input for. `[M2]`

### Operations

- **REQ-SP-12** THE SYSTEM SHALL expose liveness that does not depend on the broker, and
  readiness that does. `[R4]`
- **REQ-SP-13** THE SYSTEM SHALL record the dropped-late count, dead-letter count and
  processing lag as metrics. `[R2]`

## Non-functional

- Window 10 s, grace 5 s. These are **targets** until Phase 9 measures them.
- Final-aggregate freshness floor = window + grace = 15 s after window start. That is the
  cost of tolerating late events.
- Per-session ordering relies on the input being keyed by session. Aggregation re-keys by
  tenant/title/region, which costs a repartition.

## Out of scope

Hopping windows, rebuffer ratio, startup-time and bitrate metrics, interactive queries over
the state store, schema-registry framing.
