# Phase 5 — Query API: Requirements

**Status:** in progress · **Depends on:** Phase 4 (hot-store contract: `specs/04-sink-writer/hot-store-contract.md`)
· **Delivers:** what a dashboard reads: a one-call title overview, historical windows, and a live stream

## Why

A title page needs three unrelated facts at once (current numbers, recent history, what the
title is), and a live chart needs new windows pushed to it without polling. Both must stay
responsive when one dependency is slow, and one slow viewer must never cost the others.

## Requirements (EARS; no technology named)

### Title overview `[J6]`

- **REQ-QA-01** WHEN a client asks for a title overview THE SYSTEM SHALL gather the latest
  metrics, the recent history and the title metadata concurrently, not one after another. `[J6]`
- **REQ-QA-02** THE SYSTEM SHALL bound the whole overview request by ONE time budget
  (target 300 ms), measured from the moment the request arrives, not per call. `[J6]`
- **REQ-QA-03** WHEN a part has not answered within the budget, or fails THE SYSTEM SHALL
  return the remaining parts, substitute an empty value for the failed one, and name it in a
  `degraded` list. `[J6]`
- **REQ-QA-04** WHEN every part fails THE SYSTEM SHALL still answer successfully, with every
  part listed as degraded. `[J6]`
- **REQ-QA-05** WHEN the capacity reserved for blocking work is full THE SYSTEM SHALL degrade
  the affected part immediately instead of waiting for capacity. `[J6] [J2]`

### Blocking work and the event loop `[J2]`

- **REQ-QA-06** THE SYSTEM SHALL run every blocking call (durable-store reads, the catalog
  call) on capacity reserved for it, never on the threads that accept and write connections. `[J2]`
- **REQ-QA-07** THE SYSTEM SHALL bound that capacity (workers and waiting work), so a slow
  dependency cannot grow it without limit. `[J2]`

### Historical query

- **REQ-QA-08** WHEN a client asks for a metric over a time range THE SYSTEM SHALL return the
  matching windows oldest first, each with its sample count, and report which tier answered.
- **REQ-QA-09** WHEN the metric is not one the system produces THE SYSTEM SHALL answer "not
  found"; WHEN the range is empty or inverted THE SYSTEM SHALL answer "bad request". Errors
  use the problem-detail media type.

### Live stream `[SD3]`

- **REQ-QA-10** THE SYSTEM SHALL read the shared live feed ONCE per instance, regardless of how
  many clients are connected, and fan each entry out to the clients of that instance. `[SD3]`
- **REQ-QA-11** THE SYSTEM SHALL deliver a client only the entries of the tenant it asked for
  (and only the metrics it named, when it named any). `[SD3]`
- **REQ-QA-12** THE SYSTEM SHALL label every delivered entry with its position in the shared feed. `[SD3]`
- **REQ-QA-13** WHEN a client reconnects presenting the last position it saw THE SYSTEM SHALL
  first deliver the entries after that position, then continue live, delivering no entry twice
  and leaving no gap. `[SD3]`
- **REQ-QA-14** WHEN a client cannot keep up THE SYSTEM SHALL deliver the latest value of each
  series rather than queue a backlog, using fixed-length windows (target 250 ms). `[SD3]`
- **REQ-QA-15** WHILE a stream is idle THE SYSTEM SHALL send a keep-alive at a fixed interval
  (target 15 s) so intermediaries do not close it. `[SD3]`
- **REQ-QA-16** WHEN the position a client presents is malformed THE SYSTEM SHALL answer "bad request".

### Technology choice `[J2]`

- **REQ-QA-17** THE SYSTEM SHALL use the non-blocking request model here only because the
  workload is long-lived streams with demand control; the ingest path stays on the
  thread-per-request model (ADR-0006, ADR-0014). `[J2]`

### Operations

- **REQ-QA-18** THE SYSTEM SHALL expose liveness that depends on no data store, and readiness
  that reflects only whether the process can take traffic.

## Non-functional

- 300 ms, 250 ms and 15 s are **targets** until Phase 9 measures them.
- A history window older than the durable tier retains is out of scope.

## Honest gaps (deliberately not built)

- **No authentication or authorization.** The OpenAPI contract declares `bearerAuth`, but
  nothing validates a token, and `tenantId` is taken from the caller. Any client can read any
  tenant. This must be closed before any non-local use.
- Replay is bounded by the live feed's retention (a trimmed ring buffer). A client that was
  away longer than that gets a gap, not an error.
- The overview's title metadata is a stub standing in for a control plane that does not exist.
- Replay and the live feed are one Redis stream shared by all instances; it is a single point
  of load, measured in Phase 9, not here.
