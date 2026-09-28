# ADR-0009 — A circuit breaker, a timeout and a retry around the event publisher

**Status:** accepted · **Date:** 2026-09-29 · **Phase:** 2 · **Requirements:** REQ-IG-21, REQ-RS-01, REQ-RS-02, REQ-RS-06

## Context

Every publish crosses the network to Kinesis (or Kafka). That dependency will, at some
point, be slow, hanging, or gone. Before this ADR the gateway had no answer to any of the
three: it called the SDK and waited for whatever happened.

The consequences differ sharply, and the last one is the dangerous one.

| Dependency state | Without protection | Why it is bad |
|---|---|---|
| Rejects fast | caller gets an error quickly | tolerable — the client retries |
| Slow but answering | each caller holds an in-flight permit for seconds | permits run out, ingest starts returning 503 to *everyone* |
| Hanging, no answer | callers park forever | **worst case**: no error rate to alert on, no thread-pool exhaustion to spot, the service looks alive and quietly stops working |

Virtual threads make the third case quieter still. There is no pool to exhaust and no
thread-count alarm to trip — just an ever-growing pile of blocked virtual threads, each
still holding an in-flight permit, until the semaphore is empty.

## Decision

Wrap `EventPublisher` in `ResilientEventPublisher`: a **retry** outside a **circuit
breaker** outside a **time limiter**, applied as a decorator rather than an annotation.

```
Retry ( CircuitBreaker ( TimeLimiter ( publish ) ) )
```

This matches the documented default aspect order of the Resilience4j Spring Boot starter
(Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead, outside in). Composing it
by hand means the order is a line of code rather than a property nobody looks up.

## Why a decorator and not `@CircuitBreaker`

The annotation needs a Spring proxy, and a proxy only intercepts calls arriving from
outside the bean — the same self-invocation trap as `@Transactional`. `IngestService` is
deliberately constructed with plain `new` so it stays unit-testable in milliseconds, so
there is no proxy to rely on.

The decorator also makes the composition **visible**. The order is a line of code in
`IngestConfig`, not an aspect-ordering property that has to be looked up, and the whole
thing is testable without a Spring context.

## Why that order, and what breaks if you swap it

This is the part that is easy to get wrong and hard to notice.

The time limiter is **inside**, so its `TimeoutException` is a failure the breaker
**observes and counts**. Slowness is therefore what opens the breaker — and slowness is
the failure mode that matters most.

Reverse them and the breaker sits inside a timeout that cuts the call off before the
breaker ever learns anything. A permanently hanging dependency would never trip it.

This is not a theoretical concern. It was verified by mutation: swapping the two lines and
re-running the suite produces

```
THE ORDERING PROOF: a hanging broker times out, and the breaker counts it FAILED
    expected: OPEN
     but was: CLOSED
```

**Nine of the ten tests still passed with the wrong order.** Only the test written
specifically for it caught the fault — which is a fair summary of why "which patterns did
you use" is a much easier question than "in what order, and what breaks if you swap them".

## Retry outside the breaker, and the tax that comes with it

Retry is **outermost**, so the breaker counts every individual attempt rather than one
result per batch. With a window that needs 4 recorded calls and 3 attempts per batch, a
dependency failing outright trips the breaker after 2 batches instead of 4 — three times
sooner, which is what you want from a breaker.

Verified by mutation, moving retry inside the breaker:

```
THE ORDERING PROOF: the breaker counts every ATTEMPT, not every batch FAILED
    expected: OPEN
     but was: CLOSED
```

The tax is that retry must be told **not** to retry `CallNotPermittedException`. The
breaker stays open for 30 seconds; a 300ms retry budget cannot outwait it, so retrying
only delays the 503 the client needs.

## Retry is only safe for FAST failures

This is the rule that is easy to state and easy to get wrong. The question is not *"is
this error transient?"* but *"is it transient **and** cheap?"*

Every attempt runs while the batch still holds an in-flight permit, and permits are the
gateway's capacity. Retrying a failure that takes 5 seconds to surface turns one held
permit into 15 seconds of held permit — multiplying consumption of the very resource the
breaker exists to protect, at the worst possible moment.

So the exclusion list is where the thinking lives, and it is defined on
`ResilientEventPublisher.retryPolicy(...)` rather than in Spring configuration: which of a
publisher's failures are worth another attempt is knowledge about the publisher, not about
wiring. It also means the tests run the exact policy production runs.

| Excluded | Why |
|---|---|
| `CallNotPermittedException` | breaker is open for 30s; a sub-second retry budget cannot succeed |
| `TimeoutException` | slow failure — retrying triples the permit hold during the incident |
| `IllegalArgumentException` | the broker rejected *this* batch; attempt two fails identically |

Everything else is retried. A stricter design would whitelist known-transient types, but
that needs the `EventPublisher` port to classify its own failures — Kinesis and Kafka throw
entirely unrelated exceptions, and naming either in the decorator would couple it to one of
them. Worth revisiting once both adapters' failure modes are mapped.

### Jitter is the point, not a refinement

Backoff uses `IntervalFunction.ofExponentialRandomBackoff(100ms, 2.0, 0.5)`.

Without randomisation, a hundred callers that failed at the same instant retry at the same
instant: the retry wave is exactly as synchronised as the failure was, and the second spike
lands on a dependency that has not recovered from the first.

The test asserts the property directly — the observed intervals are **not all identical**.
Mutating to `ofExponentialBackoff` (no jitter) fails it:

```
[a fixed backoff would produce only two distinct values]
Expecting size of: to be greater than 2 but was 2
```

### Retry makes duplicates more likely, and that is accepted

Kinesis `PutRecords` is **not** idempotent. Kafka's producer has `enable.idempotence` with
a producer id and per-partition sequence numbers, so the broker can drop a duplicate from a
retried batch; Kinesis has no equivalent, so a retried publish after a partial success
genuinely duplicates records.

Consistent with ADR-0007 and the release-on-failure decision below: duplicates are absorbed
by idempotent aggregation downstream, a gap is unrecoverable. It is worth knowing that this
is a real difference between the two backbones and not a detail — see ADR-0008.

## The coupling nobody expects: the breaker needed an idempotency fix first

Adding the breaker surfaced a latent data-loss bug.

`IngestService` claims event ids in Redis (`SET NX EX`) *before* publishing. If the publish
then failed, the claims stayed behind. The client's retry found every id already claimed,
got `202 Accepted` with zero events published, and dropped its copy.

Silent, permanent data loss, reported as success. No metric moves, no alert fires.

A circuit breaker makes this strictly worse: it fails fast for its entire open window, so
**every** batch in those 30 seconds would be swallowed rather than the occasional one.

The fix is `IdempotencyStore.release(...)`, called as a compensating action when the
publish fails. A claim is a **reservation**, and a reservation that is never used has to be
given back.

Both the fix and the failure mode are pinned by tests, so nobody later deletes `release()`
as defensive code that never fires:

- `REQ-IG-22: a failed publish leaves no claim behind, so the retry works`
- `without the release, that retry is silently swallowed -- the bug being guarded`

Releasing can cause a duplicate: if the publish actually succeeded but we could not observe
it (a timeout firing while the write landed), the retry publishes it again. That is the same
trade as ADR-0007, made deliberately in the same direction — duplicates are absorbed by
idempotent aggregation downstream, a gap is unrecoverable.

## What the client sees

`PublishUnavailableException` → **503 + `Retry-After`**, not 500.

Nothing is wrong with the batch; the same bytes will succeed once the dependency recovers,
so the client must keep them and come back. A client that sees 500 reasonably concludes its
request is malformed and stops retrying — and that telemetry is gone.

A genuine rejection of *this* batch (record too large, bad key) still surfaces as 500,
because retrying it unchanged will fail identically and somebody needs to look at it.

The `Retry-After` is derived from the breaker's own `waitDurationInOpenState` rather than
configured separately, so the two cannot drift. Telling a client to return in 5s while the
breaker stays open for 30 buys five guaranteed rejections.

> Implementation note: there is no `getWaitDurationInOpenState()`. Resilience4j exposes
> `getWaitIntervalFunctionInOpenState()`, an `IntervalFunction` (attempt → millis), because
> it also supports a wait that grows on each reopen. For a fixed wait, attempt 1 is the
> configured value.

## Configuration

Built from the auto-configured `CircuitBreakerRegistry`, not a standalone
`CircuitBreaker.of(...)`. That one choice is what puts it in `/actuator/circuitbreakers`,
the health indicator and the Micrometer gauges — a standalone breaker behaves identically
and is invisible, which is the worst property a breaker can have when "is it open right
now?" is the first question asked in an incident.

Defaults in `application.yml` under `streampulse.ingest.publisher-resilience`. They are
chosen to be *explainable*, not measured; Phase 9 replaces them with real numbers. What
matters today is knowing which way each dial cuts:

| Setting | Value | Lower/shorter | Higher/longer |
|---|---|---|---|
| `failure-rate-threshold` | 50% | trips sooner, more false trips | tolerates more breakage |
| `sliding-window-size` | 20 | reacts faster, noisier | steadier, slower to notice |
| `minimum-number-of-calls` | 10 | can trip on a tiny sample | slower to react in a real outage |
| `slow-call-duration-threshold` | 2s | more calls counted slow | slowness goes unnoticed |
| `wait-duration-in-open-state` | 30s | probes sooner, may re-break it | longer total refusal |
| `permitted-calls-in-half-open-state` | 5 | closes on thin evidence | more load on something fragile |
| `call-timeout` | 5s | cuts off healthy-but-slow work | hangs persist longer |

`minimum-number-of-calls` is the one worth explaining: without it, a window that has seen
two calls, both failed, reads as 100% failure. At 04:00 with almost no traffic, two unlucky
calls would take the publisher out for 30 seconds.

`call-timeout` must stay above `slow-call-duration-threshold`, or every slow call becomes a
timeout and the slow-call signal never fires.

## Consequences

**Good**
- A hanging broker can no longer silently drain the in-flight permits.
- Slow-but-successful calls are treated as the failure they are.
- Clients get an actionable 503 with a Retry-After they can trust.
- Breaker state is visible in Actuator and Micrometer for free.
- A real data-loss bug was found and fixed, with both directions pinned by tests.

**Costs / open items**
- One more moving part between the use case and the broker.
- Thresholds are guesses until Phase 9 measures them.
- Cancelling a future on timeout is a request, not a guarantee — the record may still land,
  so duplicates are possible on retry. Accepted, consistent with ADR-0007.
- **Not yet done:** Resilience4j `Bulkhead` (REQ-RS-05). The in-flight `Semaphore` is
  already a semaphore bulkhead in effect; whether to replace it with the library's is a
  Phase 9 decision, once there are measurements to justify either way.

## Alternatives rejected

**No breaker, just a timeout.** Bounds each call but not the aggregate. Under a real outage
every request still waits the full timeout before failing, so the permits stay occupied and
throughput collapses to `permits / timeout`.

**Retry instead of a breaker.** Retrying into a dependency that is already failing adds
load exactly when it can least take it — a retry storm. Retry and a breaker solve different
problems and belong together: retry absorbs the single blip, the breaker stops retry from
becoming the outage. Note that the breaker is also what bounds the retry — once it opens,
retries stop entirely.

**Retrying everything, including timeouts.** Superficially more resilient, actually the
opposite: it converts a dependency's slowness into a capacity collapse on our side, because
each retried slow call keeps holding a permit.

**`@CircuitBreaker` annotations.** Needs a proxy the use case does not have, and hides the
ordering in aspect configuration instead of stating it in code.
