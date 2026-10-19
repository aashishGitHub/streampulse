# Phase 2 — Ingest gateway

**Status:** done. Measured on one machine: clean at 40,000 events/s with the quota lifted, 503s from
45,000. Not built: a Resilience4j bulkhead, authorisation (scopes), a real SIGTERM test, gRPC load
balancing config, any gRPC benchmark.

**One line to say first:** "A stateless front door that authenticates, rate-limits per tenant,
de-duplicates, and publishes to Kafka, and does not answer 202 until the broker has acknowledged."

## Code map

| Thing | Where |
|---|---|
| REST controller (202, DTO to command) | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestController.java` |
| The use case: quota, permit, claim, publish, release | `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java` |
| Error mapping (429, 503, 400) | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java` |
| Idempotency claims in Redis | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java` |
| Token-bucket quota (Lua, atomic) | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisTokenBucketRateLimiter.java` |
| Breaker, timeout, retry decorator | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java` |
| Kafka producer adapter | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java` |
| Wiring (plain `new`, no proxies) | `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/IngestConfig.java` |
| All the numbers | `services/ingest-gateway/src/main/resources/application.yml` |
| Decisions | `docs/adr/0006-virtual-threads-over-webflux.md`, `0007-fail-open-on-idempotency-store-failure.md`, `0009-circuit-breaker-and-timeout-on-the-publisher.md`, `0010-grpc-as-a-second-ingest-protocol.md`, `0011-keycloak-for-local-oidc.md` |

## Flow

```
Client ──POST + Bearer──▶ security filter (JWT valid? audience? tenant claim?)
        ──▶ IngestController (@Valid, 1 to 500 events; tenant from the token, never the body)
        ──▶ IngestService.ingest
              0 quota   : Redis token bucket        ── over quota        ▶ 429 + Retry-After
              1 permit  : fair semaphore, 250 ms    ── none free         ▶ 503 + Retry-After
              2 claim   : SET NX EX per event id    ── duplicates counted, not republished
              3 publish : Retry( CircuitBreaker( TimeLimiter( Kafka send ) ) ), wait for ack
              4 release the permit (finally); on a failed publish also release the id claims
        ◀── 202 {accepted, duplicates, degraded}
```

---

## Beginner

### B1. Why does the endpoint return 202, not 201 or 200?

**A strong answer contains**
- 202 means "accepted for processing". The events are durable in Kafka, but the aggregates do not
  exist yet.
- It is returned only after the broker acknowledges: the service waits on the publish before
  replying, so 202 is a durability promise.
- The body reports `accepted`, `duplicates` and `degraded`.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`

### B2. Where does the tenant come from, and why not the request body?

**A strong answer contains**
- From the verified JWT's tenant claim. A body-supplied tenant would let a caller write into another
  tenant's data.
- The request DTO has no tenant field, so there is nothing to override. A missing claim fails closed.
- A test sends a body claiming another tenant and asserts the token wins.

**Open** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/IngestEndToEndIT.java`

### B3. What does 429 mean, and what does 503 mean? Why two codes?

**A strong answer contains**
- 429: *you* exceeded *your* per-tenant quota. 503: *we* are saturated or a dependency is down.
- A retry elsewhere can help with 503, never with 429.
- Both carry `Retry-After`; the client should back off with jitter.
- Operators can tell "one tenant is noisy" from "we are out of capacity".

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java`

### B4. What is an idempotency key and how is one used here?

**A strong answer contains**
- A client may retry after a timeout; the server must not apply it twice.
- Each event id is claimed in Redis with `SET NX EX`. The first claim wins; a repeat is counted as a
  duplicate and not published.
- Keys expire after 24 hours, so de-duplication is bounded in time, and include the tenant so one
  tenant's ids never suppress another's.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java`

### B5. What do `enable.idempotence=true` and `acks=all` do on the producer?

**A strong answer contains**
- Idempotence: the broker de-duplicates producer retries using a producer id and sequence numbers.
- `acks=all`: acknowledged only when all in-sync replicas have the record.
- Neither stops a *client* retry reaching ingest twice; the Redis claim does that.
- Caveat: with one broker, `acks=all` is verified as configuration, not as behaviour under replica loss.

**Open** `services/ingest-gateway/src/main/resources/application.yml`

### B6. Why are events keyed by session id, and what is the record timestamp?

**A strong answer contains**
- Kafka orders only within a partition; the same key lands on the same partition, so a session stays in
  order. High cardinality spreads the load; tenant as key would not.
- The record timestamp is set to the event's own `occurredAt`, so Kafka Streams gets event time with
  no custom extractor.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java`

---

## Mid

### M1. Walk through `ingest` in order. Why is the quota checked before the permit?

**A strong answer contains**
- Quota, then permit, then claim, then publish, then release in `finally`.
- A throttled tenant should cost one Redis call, not a permit that other tenants need. A test pins
  "a rejected batch leaves the in-flight permits untouched".

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`

### M2. Why `SET NX EX` instead of get-then-set, and why a Lua script for the bucket?

**A strong answer contains**
- Check-then-act races: two requests both see "not claimed". The claim and the bucket update must be
  one atomic step on the server.
- A concurrency test (200 iterations across 8 threads) fails against a check-then-set store, and a
  single run would miss it, which is why it repeats.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisTokenBucketRateLimiter.java`

### M3. Why a distributed token bucket, not Resilience4j's `RateLimiter`?

**A strong answer contains**
- A per-JVM limiter multiplies with the replica count; the quota is per tenant globally.
- Two dials: capacity (10,000, the burst) and refill (2,000/s, the sustained rate). A fixed window
  allows a double burst at the boundary.

**Open** `services/ingest-gateway/src/main/resources/application.yml`

### M4. Why is the breaker a hand-written decorator and not `@CircuitBreaker`?

**A strong answer contains**
- The service is built with plain `new`, so there is no proxy to apply an annotation and none of the
  self-invocation traps.
- The decoration order is visible in code and unit-tested.
- It is built from the registry, so it still appears in actuator and Micrometer.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java`

### M5. How does gRPC reuse the REST rules, and how is backpressure expressed in gRPC?

**A strong answer contains**
- Same use case, same token decoder, same tenant resolver, so quota, dedup, permits and breaker apply
  identically. A parity test sends the same batch over REST and gRPC and compares the records.
- Automatic flow control is turned off; credit for 50 more messages is granted only after the previous
  ones are durably acknowledged, so the client's HTTP/2 window stalls naturally.

**Open** `docs/adr/0010-grpc-as-a-second-ingest-protocol.md`

### M6. Why is the drain a `SmartLifecycle` bean, not `@PreDestroy`?

**A strong answer contains**
- Ordering and bounded time: lifecycle phases stop in descending order, and a `@PreDestroy` gives you
  neither a phase nor a deadline.
- In-flight permits double as the in-flight count: the drain waits until all 64 are free or a timeout.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java`

---

## Senior

### S1. Redis is down. Walk through exactly what happens, including what goes wrong.

**A strong answer contains**
- The claim fails open: publish anyway, count `idempotency.unavailable`, return `degraded: true`
  (ADR-0007). Reason: duplicates can be reconciled downstream, a gap in telemetry cannot. It would be
  the wrong choice for payments.
- **A side effect you should know:** the quota limiter reuses the same fail-open flag and the same
  24 h TTL (read in `IngestConfig`), so during the outage *no tenant limit applies at all*.
- With fail-closed configured the client gets a 500, not a 503, which is a smaller bug of its own.

**Open** `docs/adr/0007-fail-open-on-idempotency-store-failure.md`

### S2. Tell me about the data-loss bug the circuit breaker work uncovered.

**A strong answer contains**
- Event ids were claimed in Redis *before* publishing. A failed publish left the claims behind, so the
  client's retry found every id taken and received 202 with nothing published.
- A breaker makes it worse: it fails fast for its whole open window, so every batch in that window is
  swallowed.
- Fix: release the claims when the publish fails (a reservation not used is given back). Two tests pin
  both directions, including one that makes the release fail and asserts the damage.
- A failed release is the opposite problem: that retry is lost until the TTL.

**Open** `docs/interview/05-what-broke.md` story 1.

### S3. Why MVC on virtual threads and not WebFlux? What did you give up?

**A strong answer contains**
- Every dependency is blocking (Kafka producer, Redis, JDBC). On WebFlux they would sit on
  `boundedElastic`, which is a thread pool again.
- Given up: backpressure must be built by hand (the semaphore); and pinning is a risk under Java 21 if
  something blocks inside `synchronized`. The ADR says to measure with
  `-Djdk.tracePinnedThreads`. **That measurement has not been done** (task T9-40).
- A hanging broker is quiet with virtual threads (no pool to exhaust), so the time limit is essential.

**Open** `docs/adr/0006-virtual-threads-over-webflux.md`

### S4. What breaks if you swap the breaker and the time limiter?

**A strong answer contains**
- With the breaker inside the limiter, a broker that *hangs* never trips it: the breaker only sees
  calls that return.
- Swapping the lines on purpose, nine of ten tests still passed; only one test written for that
  property failed. Proof that a green suite does not mean the property is covered.
- Retry is outermost so each attempt counts toward the breaker, and it does not retry timeouts,
  because each attempt holds a permit.

**Open** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisherTest.java`

### S5. You saw 503s at 45,000 events/s with the breaker closed. How would you prove the cause and find the real ceiling?

**A strong answer contains**
- First separate quota from capacity: the default run stopped at the quota (3,000/s, 33.4% refused,
  matching 1 − 2000/3000), so the quota had to be lifted.
- The breaker is ruled out (44,046 calls, none failed). By elimination the in-flight permit bound is
  the likely cause, but it is **not proven**: there is no permit gauge, and a rough estimate from the
  mean publish time does not obviously explain it.
- To prove it: add a permit gauge and a publish-latency timer, log which 503 type fired, move the
  load generator off the machine, repeat for variance, and use a finer step between 40k and 45k.

**Open** `docs/interview/05-what-broke.md` story 9, and `06-numbers.md`.

### S6. Is the shutdown drain actually safe on Kubernetes?

**A strong answer contains**
- Be honest: there are no Kubernetes manifests, and no test sends a real SIGTERM.
- The class javadoc describes an order (readiness first, then HTTP, then the drain), but the drain bean
  itself runs at phase 100, after the HTTP phases. **Check the real order before claiming it.**
- The worst-case sum of the configured waits (25 + 10 + 20 s) exceeds the planned 45 s grace period.
  That is arithmetic from config, not a measurement.
- After a timeout the client gets no 202 and retries; idempotency makes that safe.

**Open** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java`

---

## Say this honestly

- Oversize batches return **400**, while the spec and OpenAPI say 413. A known divergence.
- `max-batch-size` is declared in the properties record but **never read**; the 500 limit is
  hard-coded in `@Size(max = 500)`.
- There is no gauge for in-flight permits and no publish-latency metric.
- `specs/02-ingest-gateway/` contains only `requirements.md`.
- Every breaker, retry, permit and flush number is "explainable, not measured".
- A valid token with no tenant claim gives 500 on REST but `UNAUTHENTICATED` on gRPC.

## Stories and numbers

[What broke](../05-what-broke.md): stories 1, 2, 4, 5, 9. [Numbers](../06-numbers.md): saturation, quota
and latency rows.
