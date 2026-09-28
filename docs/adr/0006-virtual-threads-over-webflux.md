# ADR-0006 — Virtual threads rather than WebFlux

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 2

## Context

The ingest gateway is I/O-bound: every request blocks on Redis, then on a Kafka
acknowledgement. Under the traditional Servlet model each request occupies a platform thread
for that whole duration, so concurrency is capped by the thread pool — typically a couple of
hundred — and a slow dependency exhausts it.

Java 21 offers two escapes, and choosing between them is a standard senior interview question.

## Decision

**Spring MVC on virtual threads** (`spring.threads.virtual.enabled=true`). Not WebFlux.

## The comparison

| | MVC + virtual threads (chosen) | WebFlux / Reactor |
|---|---|---|
| Programming model | ordinary blocking code | `Mono`/`Flux` chains |
| Debugging | normal stack traces | reassembled, often unhelpful |
| Concurrency ceiling | memory, not pool size | event loop + backpressure |
| A blocking call in the wrong place | fine — it just blocks a virtual thread | **stalls the event loop; catastrophic** |
| Library requirements | any, including JDBC | the whole chain must be non-blocking |
| Team ramp-up | none | genuinely steep |
| Backpressure | you implement it (we use a semaphore) | built into the protocol |

The decisive rows are the two in the middle.

**Blocking is safe here and fatal there.** A blocked virtual thread *unmounts* from its
carrier: it costs memory, not an OS thread. A blocking call inside a Reactor chain pins an
event-loop thread and can stall every other request on it. WebFlux's power comes with a rule
the whole call graph must obey, and one accidental `block()` in one library breaks it.

**Every dependency here is blocking anyway.** The Kafka producer, the Redis client, and
JDBC in Phase 6 are all blocking APIs. Adopting WebFlux would mean wrapping them on
`boundedElastic` — which is a thread pool, reintroducing the exact limit we were trying to
escape, with reactive syntax on top.

## What we give up

Real costs, not hand-waving:

- **Backpressure is not free.** Reactor's `request(n)` protocol propagates demand end to
  end; with virtual threads nothing stops you creating a million of them. We implement it
  explicitly with a bounded `Semaphore` (REQ-IG-19). That is more code, and it only bounds
  what we remembered to bound.
- **No streaming operators.** Phase 5's SSE endpoint genuinely benefits from `Flux` and
  `Sinks.Many`, so `query-api` **will** use WebFlux. Different workload, different answer —
  and Phase 5 benchmarks both rather than assuming.
- **Pinning.** Under Java 21, a virtual thread that blocks inside a `synchronized` block
  pins its carrier, which can starve the carrier pool. We prefer `ReentrantLock` around
  blocking calls, and Phase 9 measures it with `-Djdk.tracePinnedThreads`.
  **VERIFY:** JEP 491 reportedly removes this pinning — check your JDK before relying on
  either the problem or the fix.

## Consequences

**Positive**
- The code reads as ordinary imperative Java, so the interesting part of a stack trace is
  the interesting part.
- Concurrency is bounded by memory rather than by a pool size someone guessed in 2019.
- Any library works, including the blocking Kafka and Redis clients we already use.

**Negative**
- Backpressure must be built by hand and is only as good as our discipline.
- Two concurrency models in one system once `query-api` arrives. Justified by the different
  workloads, but it is real cognitive load.
- Pinning is a live caveat until the JDK behaviour is confirmed.

## Interview note

The weak answer is "virtual threads are the new way". The strong one names the workload:
**I/O-bound with blocking clients and no streaming requirement, so virtual threads give
WebFlux's scalability without its programming model** — and then concedes that `query-api`
uses WebFlux precisely because its workload is streaming. Phase 5 benchmarks both under the
same load and records the numbers, so the opinion has data behind it.
