# Phase 3 — Stream processor: Design

## Flow of control and data

```
telemetry.raw.v1 (12p, key=sessionId, ts=occurredAt)
   │  bytes
   ▼
decode ──fail──────────────────────────────┐
   │ ok                                    │
   ▼                                       ▼
validate ──invalid─────────────────▶ telemetry.raw.v1.dlt  (original bytes + dlt-reason header)
   │ valid
   ▼
dedup by eventId   (window store, retention = window + grace, still keyed by sessionId, so
   │                a duplicate is always in the same partition as its original)
   ▼
re-key  tenant:title:region          ← repartition (the cost of ordering by session upstream)
   ▼
10 s tumbling window, grace 5 s
   ▼
aggregate (events, errors, distinct sessions)
   ▼
suppress until window closes         ← emits only when STREAM TIME passes window end + grace
   ▼
flatMap → 2 × MetricAggregate (CONCURRENT_VIEWERS, ERROR_RATE), isFinal=true
   ▼
telemetry.agg.v1 (6p, key=tenant:title:region:metric:windowStart)
```

Processing guarantee `exactly_once_v2`: output records, state-store changelog and input
offsets commit atomically (Kafka → Kafka only). ADR-0012.

## Keys and ordering

| Topic / store | Key | Why |
|---|---|---|
| `telemetry.raw.v1` | `sessionId` | per-session order; even spread |
| dedup store | `eventId` (within the session partition) | duplicates land in the same partition |
| window state | `tenant:title:region` | aggregation unit; forces a repartition |
| `telemetry.agg.v1` | `tenant:title:region:metric:windowStart` | consumers upsert by key |

Avro strings decode as `Utf8`, not `String`. Every key is built with `toString()`; a `Utf8`
never equals a `String`, so a map keyed by one silently misses the other.

## Metric definitions

- `CONCURRENT_VIEWERS` = number of distinct `sessionId` that emitted at least one event in the
  window. An approximation of concurrency: a session silent for the whole window is not
  counted. Heartbeats every ~10 s make that a small error.
- `ERROR_RATE` = events of type `ERROR` ÷ all valid events in the window.
- `sampleCount` = all valid events in the window. A low count means a low-confidence value.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Bytes are not an event | original bytes → raw DLT with reason; stream continues | `undecodableBytesGoToDltWithReasonHeader` |
| Blank id / future event time | → raw DLT; not aggregated | `invalidEventGoesToDlt` |
| Unknown event type | counted in totals, not an error | `unknownTypeCountsTowardTotalsOnly` |
| Duplicate eventId in window | counted once | `duplicateEventIdCountedOnce` |
| Event after window, within grace | included | `outOfOrderWithinGraceIsCounted` |
| Event after grace | dropped, drop metric incremented | `lateAfterGraceIsDroppedNotEmitted` |
| Window not yet closed | no output | `noOutputBeforeWindowCloses` |
| Window closed | exactly one record per metric | `emitsOneFinalAggregatePerMetricAfterGrace` |
| Processor restarts mid-window | state restored from changelog; no duplicate publish | covered by exactly-once v2; end-to-end `StreamProcessorIT` (optional) |
| Quiet partition | last window stays open until a later event arrives | documented; tests pipe a tick record |

## Decisions

- ADR-0012: Kafka Streams, exactly-once v2, what it does and does not cover.
- ADR-0004/0005: Avro, versioned topics.
