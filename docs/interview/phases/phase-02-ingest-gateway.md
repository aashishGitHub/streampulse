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

**Answer:** 202 Accepted means the service has taken responsibility for the events but has not finished everything they will cause. In my gateway the events are acknowledged by the Kafka broker before I reply, because the use case blocks on the publish future with `join()`, so a 202 is a durability promise and not a hope. What does not exist yet is any aggregate: the stream processor has not seen the events, so a 201 Created would claim a resource that is not there, and the client might discard its local copy on the strength of a lie. The body reports `accepted`, `duplicates` and `degraded`, and a batch made only of already-seen ids still gets a 202 with `accepted` 0, because from the client's side the outcome is the same. The honest limit is that locally I run one broker, so `acks=all` is configuration I read, not behaviour I saw under replica loss.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:201-204`
```java
    private void publishAndWait(IngestCommand command, List<IngestCommand.IngestEvent> events) {
        var scoped = new EventPublisher.TenantScopedEvents(command.tenantId().value(), events);
        try {
            publisher.publishAll(scoped).join();
```
The `join()` is the point: the method does not return until the broker has acknowledged the whole batch, and only then can the controller build the 202.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:193-220` (publish and wait), `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestController.java:77-87` (the 202 and the response body)

### B2. Where does the tenant come from, and why not the request body?

**Answer:** The tenant comes from the `tenant` claim of the verified JWT, read by `TenantResolver` from the authentication that Spring Security has already validated, and never from the request body. If a caller could name the tenant in the body, any authenticated caller could write telemetry into another tenant's data, which is the worst failure a multi-tenant system can have. The safest way not to trust a field is not to have one, so the request DTO has no tenant field at all. A token with no tenant claim fails closed instead of falling back to a default. A test posts an event whose body claims `tenantId: sportsnow` under a `streamflix` token and asserts that the record is written as `streamflix`. One wrinkle I have not resolved is that the missing-claim case returns 500 on REST but `UNAUTHENTICATED` on gRPC.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/TenantResolver.java:87-95`
```java
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);
        ...
            throw new IllegalStateException("Token has no '" + TENANT_CLAIM + "' claim; the issuer is misconfigured");
        ...
        return TenantId.of(tenant);
```
Point at the throw: a missing claim is an error, not a default, and `TenantId.of` validates the format before anything downstream sees the value.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestDtos.java:48-56` (the event DTO has no tenant field), `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/IngestEndToEndIT.java:347-371` (the body cannot override the tenant)

### B3. What does 429 mean, and what does 503 mean? Why two codes?

**Answer:** A 429 says that you exceeded your own per-tenant quota, and a 503 says that we are saturated or a dependency is down. The distinction matters because a retry against another instance can help with a 503 but never with a 429, since the quota lives in Redis and is global. It also matters to operators, who can tell "one tenant is noisy" apart from "we are out of capacity" without reading logs. Both responses carry a `Retry-After` header, and the client is expected to back off with jitter, because without jitter every rejected client returns at the same instant and the second spike is worse than the first. I log both at debug level on purpose, since they are the system working as designed and should not page anyone.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java:50-77`
```java
    @ExceptionHandler(BackpressureException.class)
    ResponseEntity<ProblemDetail> handleBackpressure(BackpressureException e) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Ingest is at capacity");
        ...
    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ProblemDetail> handleQuotaExceeded(QuotaExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Tenant quota exceeded");
```
Two exception types, two statuses, each with its own problem type URI, so the code that throws them never decides the HTTP status.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java:29-40` (why they differ), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:107-127` (where each is thrown)

### B4. What is an idempotency key and how is one used here?

**Answer:** An idempotency key lets a server recognise a repeat of something it already did, so a client can retry after a timeout without the effect being applied twice. Here every event id is claimed in Redis with `SET NX EX`: the first claim wins, and a repeat is counted as a duplicate and not published. The key includes the tenant, so one tenant's ids can never suppress another tenant's events. Keys expire after 24 hours, which bounds the memory but also means de-duplication is time-limited, and a retry arriving a day later is treated as new. There is also an optional `Idempotency-Key` request header, but it is only echoed back as the batch id, and the real de-duplication is per event id. A cost I have not measured is that the claim is one Redis call per event id in a loop.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java:74-76`
```java
                String key = id.idempotencyKey(tenant);

                Boolean isNew = redis.opsForValue().setIfAbsent(key, "1", ttl);
```
`setIfAbsent` with a TTL is the `SET NX EX` command: one atomic round trip that both tests and claims the key.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java:65-99` (claimAll), `services/ingest-gateway/src/main/resources/application.yml:186` (the 24 hour TTL)

### B5. What do `enable.idempotence=true` and `acks=all` do on the producer?

**Answer:** With idempotence on, the broker de-duplicates producer retries by tracking a producer id and sequence numbers per partition, so a retry after a lost acknowledgement cannot append the record twice or reorder it. With `acks=all`, the broker only acknowledges once all in-sync replicas have the record. Neither setting stops a client from sending the same event twice through two separate HTTP requests, and that is what the Redis claim is for, so the two layers solve different problems and I need both. The caveat is that I run one broker with replication factor 1, so I have verified `acks=all` as configuration and not as behaviour under replica loss. The config also carries a `VERIFY` note on the exact limit for `max.in.flight.requests.per.connection`, which I have not checked against current Kafka docs.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:75-77`
```yaml
        enable.idempotence: true      # PID + sequence numbers: a producer retry cannot
                                      # duplicate or reorder a record
        acks: all                     # every in-sync replica must have it before we ack
```
Both are labelled correctness settings in the file, not tuning knobs, and the throughput settings below them (`linger.ms`, `batch.size`) are the ones Phase 9 was meant to tune.

**Code:** `services/ingest-gateway/src/main/resources/application.yml:70-90` (the producer block), `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java:24-33` (the layering explained)

### B6. Why are events keyed by session id, and what is the record timestamp?

**Answer:** Kafka orders records only within a partition, and the key decides the partition, so keying by session id keeps every event of one viewing session on one partition and therefore in order. A session id has high cardinality, so the load spreads across partitions; using the tenant as the key would put a whole tenant on one partition. The record timestamp is set to the event's own `occurredAt` instead of the broker's append time, which means Kafka Streams gets event time from the record without a custom timestamp extractor that would decode the payload a second time. That relies on the topic using `CreateTime`, which is the default. The price of keying by session is that the aggregation downstream needs a different key, so it pays for a repartition.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java:78-79`
```java
        var record = new ProducerRecord<>(topic, null, event.occurredAt().toEpochMilli(), event.partitionKey(), value);
```
The third argument is the timestamp (the event's `occurredAt`) and the fourth is the key, which `IngestCommand` defines as the session id.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/domain/IngestCommand.java:80-82` (partitionKey returns the session id), `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java:96-104` (a unit test pins the key)

### B7. What happens when a batch has more than 500 events?

**Answer:** The REST request is rejected by Bean Validation before it reaches the use case, with a 400 that names the offending field. The limit of 500 is hard-coded in a `@Size(max = 500)` annotation on the request record, and I have to be honest about two defects around it. The spec and the OpenAPI file say an oversize batch should return 413, but the code returns 400, so that is a known divergence I have not fixed. And `max-batch-size` exists in the typed properties record and in `application.yml`, but nothing reads it, so changing that setting changes nothing. I would fix it by reading the property in one place and mapping the failure to 413, and a test would pin both.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestDtos.java:35-39`
```java
    record EventBatchRequest(
            @NotEmpty(message = "a batch must contain at least one event")
                    @Size(max = 500, message = "a batch may contain at most 500 events")
                    @Valid
                    List<EventDto> events) {}
```
The literal 500 here is the only place the limit is enforced, and the `@Valid` is what makes the per-event constraints run.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/IngestProperties.java:33` (the unread `maxBatchSize`), `specs/02-ingest-gateway/requirements.md:51` (the spec says 413), `api/openapi/ingest-v1.yaml:70` (the OpenAPI 413 response)

---

## Mid

### M1. Walk through `ingest` in order. Why is the quota checked before the permit?

**Answer:** The order is quota, then permit, then idempotency claim, then publish, and the permit is released in a `finally`. The quota goes first because a throttled tenant should cost one Redis call and nothing else; if the permit came first, a tenant over its limit would hold permits that healthy tenants need, so its problem would become everyone's. A unit test pins this: a rejected batch leaves the in-flight permits untouched. The permit comes second because the claim and the publish are the expensive steps, and the semaphore is the backpressure bound. One cost I see when reading the code is that the quota tokens are spent before the permit is taken, and I did not find anything that gives them back if the next step returns a 503, so a capacity rejection still consumes quota. I have not tested that behaviour.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:107-135`
```java
        if (!rateLimiter.tryConsume(command.tenantId(), command.size())) {
        ...
            acquired = inFlight.tryAcquire(permitWait.toMillis(), TimeUnit.MILLISECONDS);
        ...
        try {
            return doIngest(command);
        } finally {
        ...
            inFlight.release();
        }
```
Point at the quota check sitting above `tryAcquire`, and at the `finally` that makes a permit leak impossible on any exit path.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:101-136` (ingest), `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java:288-297` (a rejected batch leaves the permits untouched)

**If they push:** "Why a semaphore and not a queue?" A bounded queue still accepts the work and defers the decision, while a semaphore refuses immediately and tells the client when to come back, and a virtual thread blocked in `tryAcquire` costs memory, not an OS thread.

### M2. Why `SET NX EX` instead of get-then-set, and why a Lua script for the bucket?

**Answer:** Both are the same lesson: check-then-act races. With get-then-set, two requests can both see the key as absent and both publish, which shows up in production as quietly doubled metrics, and with a three-call token bucket two replicas can both read five tokens and both spend three. The fix is not a faster check but making the whole decision one atomic step on the Redis server, which executes commands and scripts one at a time. For the claim that is `SET NX EX`, and for the bucket it is a Lua script that refills lazily, decides, and writes back in one go. The testing story needs care. The 200-iteration, 8-thread test runs against an in-memory fake store whose `add` is atomic, and it fails when I swap in a check-then-set fake. The one real-Redis test I found resubmits a batch sequentially, and I did not find a test class for the Lua script itself.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisTokenBucketRateLimiter.java:96-102`
```lua
            local allowed = 0
            if tokens >= requested then
              tokens  = tokens - requested
              allowed = 1
            end
            ...
            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'updatedAt', now)
```
The decision and the write happen inside one script, so no other client can run between them.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisTokenBucketRateLimiter.java:63-108` (the limiter and its script), `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java:144-205` (the repeated concurrency test)

**If they push:** "Why does the test loop 200 times?" A race is a schedule, and one run samples one schedule; an earlier single-shot version passed against a deliberately broken store, so repetition raises the chance of hitting the window, though it proves nothing by itself.

### M3. Why a distributed token bucket, not Resilience4j's `RateLimiter`?

**Answer:** Resilience4j's limiter lives inside one JVM, so with four replicas a "1000 per second" limit silently becomes 4000 per second and changes every time I scale. A tenant quota is a business fact, so it has to live in shared state, which here is Redis. The bucket has two independent dials: capacity of 10,000 is the largest burst, and a refill of 2,000 per second is the sustained rate. A fixed window would allow a double burst across the boundary, and the bucket does not. I still think Resilience4j is the right tool for protecting a downstream callee from us, and that is how I use its breaker and retry. In the load run the default quota showed up as a ceiling: 429s from 3,000 events per second with 33.4% refused, which matches 1 minus 2000 over 3000. That was one run on one laptop.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:191-192`
```yaml
    quota-capacity: 10000
    quota-refill-per-second: 2000
```
Capacity is the burst budget and refill is the long-run limit; the limiter charges one token per event, so a 100-event batch costs 100.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisTokenBucketRateLimiter.java:49-61` (why a bucket, why distributed), `MILESTONES.md:1750-1766` (Run A, the quota as a ceiling)

### M4. Why is the breaker a hand-written decorator and not `@CircuitBreaker`?

**Answer:** The annotation needs a Spring proxy, and a proxy only intercepts calls that arrive from outside the bean, which is the same self-invocation trap that catches people with `@Transactional`. `IngestService` is built with plain `new` so it stays unit-testable, so there is no proxy to rely on. A decorator makes the composition explicit: the order is a few lines of code I can read and unit-test, not an aspect-ordering property someone has to look up. The order is retry outermost, then the circuit breaker, then the time limiter around the actual publish. The breaker is still built from the Resilience4j registry, so it shows up in the actuator and in Micrometer, which matters because "is it open right now" is the first question in an incident. I have not run a game day against it, and the numbers on it are explainable, not measured.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:184-192`
```java
        Supplier<CompletionStage<Void>> timed =
                TimeLimiter.decorateCompletionStage(timeLimiter, timeoutScheduler, call);
        ...
        Supplier<CompletionStage<Void>> guarded = CircuitBreaker.decorateCompletionStage(circuitBreaker, timed);
        ...
        Supplier<CompletionStage<Void>> retried = Retry.decorateCompletionStage(retry, timeoutScheduler, guarded);
```
Read it from the inside out: time limiter wraps the call, breaker wraps that, retry wraps the breaker.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:27-96` (decorator and ordering rationale), `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/IngestConfig.java:146-162` (breaker built from the registry)

**If they push:** "Why not retry timeouts?" Every attempt holds an in-flight permit, so retrying a five-second failure triples the permit hold during an incident; the retry policy ignores timeouts, an open breaker and `IllegalArgumentException`.

### M5. How does gRPC reuse the REST rules, and how is backpressure expressed in gRPC?

**Answer:** The gRPC service is a second inbound adapter over the same use case, with the same token decoder and the same tenant resolver, so quota, de-duplication, permits and the breaker apply identically. It contains no validation or idempotency logic of its own, it only maps protobuf to the domain and back. A parity integration test sends the same event over REST, gRPC unary and gRPC streaming and compares the records on the stream. For backpressure, I turned off gRPC's automatic flow control: the server calls `request(50)` for the first window and grants the next 50 only after the previous batch has been durably acknowledged. If `ingest` is blocked on the semaphore, no credit is granted, and the client's HTTP/2 window stalls. I have not benchmarked gRPC, and I did not find a test that proves the client's window actually stalls when the permits run out.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/grpc/TelemetryIngestGrpcService.java:252-268`
```java
                IngestResult result = ingest.ingest(toCommand(tenant, buffered));
                ...
                response.onNext(Ack.newBuilder()
                        .setLastDurableSequence(lastBufferedSequence)
                ...
                buffered.clear();
                ...
                response.request(streamFlushSize);
```
The ACK is sent only after `ingest` returns, and the next window is requested only after that; moving the last line above `ingest` would bring back unbounded buffering.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/grpc/TelemetryIngestGrpcService.java:122-159` (streamEvents and disableAutoRequest), `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/ProtocolParityIT.java:257-258` (the parity test), `docs/adr/0010-grpc-as-a-second-ingest-protocol.md` (the decision)

### M6. Why is the drain a `SmartLifecycle` bean, not `@PreDestroy`?

**Answer:** `SmartLifecycle` gives me what shutdown needs and `@PreDestroy` does not: a phase that orders this bean relative to the others, and a stop method that takes a completion callback, so the work can be bounded by a timeout. Phases stop in descending order, so a higher number stops earlier. The drain uses the in-flight permits as its count: `IngestService` takes one permit per batch and releases it in a `finally`, so when all of them are free, nothing is mid-publish. The loop polls every 25 milliseconds until all 64 permits are back or the drain timeout of 20 seconds passes, and on a timeout it logs an error saying those clients will not get a 202. The callback always runs in a `finally`, otherwise the container waits out the full per-phase timeout for a bean that has already finished.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:121-136`
```java
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        int inFlight = maxInFlight - ingestService.availablePermits();
        ...
        while (System.nanoTime() < deadline) {
            if (ingestService.availablePermits() == maxInFlight) {
        ...
                Thread.sleep(25);
```
The permits double as the in-flight counter, so there is no second piece of state to keep in sync.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:97-154` (stop and drain), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:222-225` (availablePermits)

**If they push:** "Is the ordering in the class comment right?" See S6; I found that comment does not match the code.

### M7. A publish times out. What does the client see, and what can go wrong with the retry?

**Answer:** The time limiter cuts the call after five seconds and the breaker counts it as a failure. The publisher then translates it into a `PublishUnavailableException` that maps to a 503 with a `Retry-After` of two seconds, so the client knows to keep its events and come back. The danger is that a timeout means the outcome is unknown: cancelling a future is a request, not a guarantee, so the record may still land at the broker as I give up on it. A retry can therefore produce a duplicate, and I accept that on purpose for the same reason as failing open, since downstream is idempotent and a gap is worse than a duplicate. Because the publish failed from the use case's point of view, it also releases the id claims, so the retry is not mistaken for a duplicate and swallowed. I log it at warn level, and I have not demonstrated a duplicate after a timeout, so it is a documented risk, not an observed one.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:218-226`
```java
        if (cause instanceof TimeoutException e) {
        ...
            log.warn("Publish for tenant {} timed out; a retry may duplicate these events", tenantId);
            return new PublishUnavailableException(
                    "publish timed out before the broker acknowledged", RETRY_AFTER_ON_TIMEOUT, false, e);
```
The `false` says the call was attempted, as opposed to rejected by an open breaker, and the log line records the duplicate risk.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:209-233` (translate), `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java:107-126` (the 503 mapping)

### M8. Why are liveness and readiness different probe groups, and what is in each?

**Answer:** Readiness asks "can I take traffic right now" and liveness asks "am I broken beyond recovery", and the only cure Kubernetes has for a failed liveness probe is a restart. If liveness included Redis, a Redis blip would fail every pod's liveness at once, Kubernetes would restart them all, and I would have a cold JVM and cold caches on top of the original problem. So liveness contains only `livenessState`, and readiness only `readinessState` for now. The drain flips readiness to out of service and the endpoint then returns 503, while liveness stays up, because draining is not dying. A test asserts the probe shapes and asserts that after `stop()` readiness is 503 and liveness is 200. Kubernetes itself is not part of this repository's tests, so the probe wiring in a real cluster is unverified.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:124-145`
```yaml
        liveness:
        ...
          include: livenessState
        readiness:
        ...
          include: readinessState
```
Written out explicitly because the membership is the decision; the comments in that block say what would belong in readiness later.

**Code:** `services/ingest-gateway/src/main/resources/application.yml:96-145` (probe groups and why), `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:131-158` (readiness goes out of service, liveness stays up)

---

## Senior

### S1. Redis is down. Walk through exactly what happens, including what goes wrong.

**Answer:** The claim fails open: the gateway publishes anyway, increments the `idempotency.unavailable` counter, and returns 202 with `degraded: true`. That is ADR-0007, and the reason is that a duplicate can be reconciled downstream while a gap in telemetry cannot, which would be the wrong choice for payments. What bit me when I read the wiring is a side effect: the quota limiter is built with the same fail-open flag and the same 24-hour TTL as the idempotency store, so during the outage the limiter also fails open and no tenant limit applies at all. The only protection left is the in-flight permit bound. Reading the code, setting the flag to false does not give the 500 I first thought: the quota check runs first, returns false when Redis is down, and the client gets a 429, which is misleading. I read this and have not run it, and the unit test for fail-closed uses a limiter that always allows.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/IngestConfig.java:246-253`
```java
    RateLimiter rateLimiter(StringRedisTemplate redis, IngestProperties props) {
        return new RedisTokenBucketRateLimiter(
                redis,
                props.quotaCapacity(),
                props.quotaRefillPerSecond(),
                props.idempotencyTtl().toSeconds(),
                props.failOpenOnIdempotencyError());
    }
```
The last two arguments are the point: the limiter borrows the idempotency TTL and the idempotency fail-open flag, which is a coupling I did not intend.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisTokenBucketRateLimiter.java:141-150` (fail open or closed on a Redis error), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:143-156` (fail open on the claim), `docs/adr/0007-fail-open-on-idempotency-store-failure.md` (the decision)

**If they push:** "How long can a request wait on a dead Redis?" The Redis client timeout is 500 ms and the pool wait 200 ms in `application.yml`, and the limiter and the claim are two separate calls; I have not measured the latency during an outage.

### S2. Tell me about the data-loss bug the circuit breaker work uncovered.

**Answer:** While adding the breaker I asked what happens to a batch that fails to publish, and found that the use case claimed every event id in Redis before publishing. When the publish failed, the claims stayed. The client then retried, every id looked like a duplicate, and ingest answered 202 Accepted with zero events published, so the client dropped its copy: silent, permanent loss reported as success. A breaker makes it worse, because it fails fast for its whole open window, so every batch in that window would be swallowed. The fix is a compensating action: when the publish fails, release the claims, treating a claim as a reservation that is given back if unused. Two tests pin both directions, including one with a fake whose release fails that asserts the damage so nobody deletes `release()` as dead code. A failed release is the opposite problem, since that retry is lost until the TTL. This happened in local runs and tests, not in production.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:171-186`
```java
        try {
            publishAndWait(command, newEvents);
        } catch (RuntimeException e) {
        ...
            idempotencyStore.release(command.tenantId(), claim.claimed());
            throw e;
        }
```
Point at the order: release first, then rethrow the original failure, and note that `release` never throws so a Redis error cannot mask the publish error.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java:101-133` (release, best effort), `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java:401-455` (both directions pinned), `docs/interview/05-what-broke.md` (story 1)

**If they push:** "What if the process dies between claim and ack?" Nothing runs the release, so reading the code I believe the retry would be counted as duplicates until the TTL; I have not tested that.

### S3. Why MVC on virtual threads and not WebFlux? What did you give up?

**Answer:** Every dependency in the gateway blocks: the Kafka producer, the Redis client, and JDBC later. On WebFlux I would wrap them on `boundedElastic`, which is a thread pool again, with reactive syntax on top. A blocked virtual thread unmounts from its carrier, so concurrency is bounded by memory and not by a pool size someone guessed. What I gave up is built-in backpressure, so I had to build it by hand with the semaphore, and it only bounds what I remembered to bound. Pinning is the other risk: on Java 21 a virtual thread that blocks inside `synchronized` pins its carrier. The ADR says to measure with `-Djdk.tracePinnedThreads`, and that measurement has not been done (task T9-40). The ADR also carries a VERIFY note that JEP 491 reportedly removes pinning, which I have not verified. A hanging broker is quiet with virtual threads because there is no pool to exhaust, so the time limit is essential.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:12-18`
```yaml
  threads:
    virtual:
      ...
      enabled: true
```
One property turns on virtual threads for request handling; the comment above it points to ADR-0006 for why this and not WebFlux.

**Code:** `docs/adr/0006-virtual-threads-over-webflux.md` (decision, costs, VERIFY note), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:50-59` (why a semaphore works with virtual threads)

**If they push:** "Does the query side use the same model?" The ADR says query-api will use WebFlux because the SSE endpoint benefits from `Flux`, so it is two models in one system.

### S4. What breaks if you swap the breaker and the time limiter?

**Answer:** If the breaker sat inside the time limiter, the time limiter would cut a hanging call off before the breaker learned anything, so a broker that hangs instead of erroring would never trip the breaker. The breaker only counts calls that return, and hanging is the most dangerous failure because every caller keeps a virtual thread and its permit parked while the service still looks alive to a health check. When I swapped the two lines on purpose, nine of ten tests stayed green, and only the one test written for that property failed, with the breaker expected OPEN and found CLOSED. That is my evidence that a green suite does not mean the property is covered. Retry is outermost so every attempt counts toward the breaker, and it is told not to retry timeouts, an open breaker or a batch the broker rejected.

**Example:** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisherTest.java:201-221`
```java
        void timeoutsAreCountedByTheBreaker() {
        ...
            delegate.mode = Mode.HANG;
        ...
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
```
The delegate never completes, the time limiter turns each hang into a timeout, and the breaker, being outside it, opens.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:145-152` (the retry policy), `docs/interview/05-what-broke.md` (story 2)

**If they push:** "How do you know the other nine tests were not covering it?" I ran the mutation and counted; the lesson is to mutate the code and see which tests notice.

### S5. You saw 503s at 45,000 events/s with the breaker closed. How would you prove the cause and find the real ceiling?

**Answer:** First I separated quota from capacity. The default-config run stopped at the quota, with 429s from 3,000 events per second and 33.4% refused, which matches 1 minus 2000 over 3000, so I lifted the quota for the second run. Then the ingest alone ran clean at 40,000 events per second and began returning 503s at 45,000 with 11.8% errors. That is one laptop, one single-broker Kafka, one run per step, and the load generator shared the CPU, so I would not quote it as capacity. The breaker is ruled out, because its metrics show 44,046 successful calls and none failed. By elimination the in-flight permit bound is the likely cause, but it is not proven: there is no permit gauge, and a rough estimate from the mean publish time does not obviously explain it. To prove it I would add a permit gauge and a publish-latency timer, log which 503 type fired, move the load generator off the machine, repeat for variance, and use a finer step between 40,000 and 45,000.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:184-185`
```yaml
    max-in-flight-batches: 64         # the backpressure bound (REQ-IG-19)
    permit-wait: 250ms                # how long to wait for a permit before 503
```
These are the two numbers behind the suspected cause: 64 batches in flight and a 250 millisecond wait before a 503. They are explainable defaults, not measured values.

**Code:** `MILESTONES.md:1768-1810` (Run B and the JVM samples), `MILESTONES.md:1811-1840` (found by running it, and the honest gaps), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:123-127` (the capacity 503)

### S6. Is the shutdown drain actually safe on Kubernetes?

**Answer:** Not proven, and I would say so. There is no test that sends a real SIGTERM, and I have not run this on a cluster. The class Javadoc describes a ladder with readiness flipping at phase 300, HTTP at 200 and the drain at 100, but the code defines only `PHASE = 100` for this bean, and nothing registers a phase-300 bean. This bean publishes `REFUSING_TRAFFIC` inside its own `stop`, so the readiness flip happens at phase 100, after the HTTP connector's graceful shutdown, which has a much higher phase number and stops earlier. The comment in `GrpcServerLifecycle` lists the real numbers, which its author says were read from the Boot jar and I did not re-read. Whether Spring Boot itself flips readiness earlier on context close, I have not verified this. The configured waits of 25, 10 and 20 seconds sum to 55, more than the 45-second grace the config comment mentions; that is arithmetic from config, not a measurement, and I did not find that 45 seconds set under `deploy/`.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:58-112`
```java
    private static final int PHASE = 100;
    ...
    public void stop(Runnable callback) {
        running = false;
        try {
        ...
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        ...
            drain();
        } finally {
        ...
            callback.run();
        }
    }
```
The readiness flip is inside this bean's `stop`, so it can only happen at phase 100; the Javadoc's phase 300 does not exist in the code.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java:37-43` (the Javadoc ladder that does not match), `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/GrpcServerLifecycle.java:41-47` (ladder with the Boot phase numbers), `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:131-158` (calls stop directly, not SIGTERM), `services/ingest-gateway/src/main/resources/application.yml:193-195` (drain timeout and the 45 s comment)

**If they push:** "After a timeout, is the client's retry safe?" The client gets no 202 and retries, and the claim-before-publish design makes that safe only if the claims were released; if the process is killed with claims held, I believe the retry is read as a duplicate until the TTL, and I have not tested it.

---

## Say this honestly

- Oversize batches return **400**, while the spec and OpenAPI say 413. A known divergence.
- `max-batch-size` is declared in the properties record but **never read**; the 500 limit is
  hard-coded in `@Size(max = 500)`.
- There is no gauge for in-flight permits and no publish-latency metric.
- `specs/02-ingest-gateway/` contains only `requirements.md`.
- Every breaker, retry, permit and flush number is "explainable, not measured".
- A valid token with no tenant claim gives 500 on REST but `UNAUTHENTICATED` on gRPC.
- The `IngestDrainLifecycle` Javadoc ladder (300, 200, 100) does not match the code; the readiness flip
  runs at phase 100. Whether Spring Boot flips readiness earlier on its own is not verified.
- I did not find a test class for the Lua token bucket, and the 200-iteration concurrency test runs
  against an in-memory fake store, not Redis.
- The quota limiter shares the idempotency fail-open flag and TTL, so a Redis outage lifts every tenant
  limit; with the flag set to false, reading the code, the quota check would answer 429, not 500.
- `claimAll` makes one Redis call per event id; I did not measure that or pipeline it.

## Stories and numbers

[What broke](../05-what-broke.md): stories 1, 2, 4, 5, 9. [Numbers](../06-numbers.md): saturation, quota
and latency rows.
