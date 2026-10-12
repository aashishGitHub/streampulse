# ADR-0014 — WebFlux and Redis Streams for the query API

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 5 · **Complements** ADR-0006

## Context

ADR-0006 chose MVC on virtual threads for the ingest gateway and promised that `query-api`
would be the place WebFlux is justified. The query API has two workloads:

1. request/response reads that fan out to three dependencies under one latency budget;
2. long-lived server-to-client streams, one per open dashboard, fed by a shared feed.

## Decision

**WebFlux (Reactor on Netty) for the whole service.** Blocking calls (JDBC, the catalog stub,
the reactive Redis client used synchronously for the overview) run on ONE bounded, named,
rejecting executor. The live feed is ONE shared Redis Stream reader per instance
(`StreamReceiver`) feeding a hot multicast `Sinks.Many`; each client filters its tenant from
that, replays missed entries with a range read, and is rate-limited by a coalescing window.

## Why WebFlux here and not virtual threads

| | WebFlux (chosen here) | MVC + virtual threads |
|---|---|---|
| 10k idle streams | an event loop plus per-subscriber state | 10k parked virtual threads, each holding a stack |
| Slow reader | demand signalling: stop requesting, drop or conflate upstream | you write the bounded queue and the drop policy |
| Merge a heartbeat into a stream | `Flux.merge` | a second thread or a scheduler and shared state |
| Operators for windows and replay-then-live | built in (`buffer`, `concat`, `replay`) | hand-written |
| Blocking JDBC | must be isolated explicitly (the J2 trap) | free |
| Debuggability | assembled traces | ordinary stack traces |

Streaming with demand control is what Reactor was built for; the ingest path has no
streaming requirement, so ADR-0006 stands there. Two models in one repo is a real cost,
accepted because the workloads differ.

## The J2 trap, and how it is contained

One blocking call on an event-loop thread stalls every connection on that loop. So every
blocking port is invoked from `blockingExecutor` (named `blocking-N`, fixed size, bounded
queue, `AbortPolicy`). A test asserts no port ever runs on an event-loop thread (named `webflux-http-nio-N` under Spring's `ReactorResourceFactory`, not Reactor Netty's own `reactor-http-nio-N`), and
that a saturated executor degrades instead of waiting. The pool of JDBC connections is sized
to the executor so the executor, not the pool, is the limit.

## The J6 shape

One deadline per request. Each call is `supplyAsync` on the bounded executor, then
`orTimeout(remaining)`, then `exceptionally(fallback)`; `allOf` joins. `orTimeout` completes the
future but **does not cancel the running call**, so a timed-out call still occupies a worker.
The bound plus rejection is what stops that from becoming a leak. Rejection degrades a part
immediately.

## Why a Redis Stream for the live feed

The sink writer already writes the aggregate to a stream with a bounded length. The entry id is
a total order, so it doubles as the SSE `id:` and the `Last-Event-ID` resume point, with no
second sequence to keep. Pub/sub has no replay; Kafka would give each query instance its own
consumer group and rebalancing for a UI feed.

## Consequences

**Positive**
- A client that drops reconnects without a gap, within the stream's retention.
- Cost scales with instances (one reader each), not with clients.
- A slow client sees the latest value, not a backlog.

**Negative**
- Replay is bounded by stream trimming (`MAXLEN ~ 10000` across ALL tenants): a busy system
  shortens the resumable window. A longer outage needs the history endpoint.
- Every instance reads the whole stream and discards other tenants' entries. Fine at this
  scale; sharding the stream per tenant is the next step and is not done.
- Coalescing drops intermediate values by design. A consumer that needs every window uses the
  history endpoint.
- No authentication yet; see the requirements' honest gaps.

## Interview note

The weak answer is "WebFlux is faster". The strong one says WebFlux earns its cost only where
the work is streaming with backpressure, names the single rule it imposes (never block a loop),
and shows the tests that enforce that rule. **VERIFY:** `StreamReceiver` behaviour on an idle
poll with the "latest" offset is read from its documented design, and the explicit-cursor
approach here is a deliberate response; confirm against the Spring Data Redis docs for your version.
