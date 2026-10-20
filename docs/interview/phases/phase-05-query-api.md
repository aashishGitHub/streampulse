# Phase 5 — Query API and the live feed

**Status:** done, with no authentication. Measured under light read load only. Not measured: concurrent
SSE streams per instance, which store served the reads, the degraded-part rate.

**One line to say first:** "A WebFlux read side: an overview endpoint that returns within a 300 ms
budget even when a part fails, and an SSE feed that replays what you missed then goes live."

## Code map

| Thing | Where |
|---|---|
| Overview with parallel fan-out in one deadline | `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java` |
| SSE endpoint, replay then live | `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java` |
| Live feed reader over Redis Streams | `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java` |
| Wiring (including the block time) | `services/query-api/src/main/java/com/streampulse/query/config/QueryConfig.java` |
| Regression test for the idle-poll bug | `services/query-api/src/test/java/com/streampulse/query/adapter/out/redis/LiveFeedIdleIT.java` |
| Config (budget, executor, heartbeat, replay) | `services/query-api/src/main/resources/application.yml` |
| Decision | `docs/adr/0014-webflux-sse-redis-streams.md` |

---

## Beginner

### B1. What is Server-Sent Events and what is `Last-Event-ID` for?

**Answer:** Server-Sent Events is a one-way, long-lived HTTP response in which the server keeps writing events to the browser. It is simpler than a WebSocket when the client only listens, and browsers reconnect on their own. Each event carries an id. When the connection drops and the browser reconnects, it sends the last id it saw in the `Last-Event-ID` header, and the server replays what was missed and then continues live. In this service the event id is the Redis stream entry id, so one value is both the event id and the resume point. If the header is present but not a valid entry id, the endpoint answers 400 instead of guessing.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:36-41`
```java
    @GetMapping(path = "/v1/stream/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<MetricEvent>> stream(
            @RequestParam String tenantId,
            @RequestParam(required = false) List<String> metrics,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        EntryId resumeAfter = parseResumePoint(lastEventId);
```
The header is optional: without it the client gets live entries only, with it the controller resumes after that entry.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:36-48` (the endpoint), `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:89-94` (the event id is the entry id), `services/query-api/src/test/java/com/streampulse/query/adapter/in/web/QueryControllersTest.java:175` (a malformed id is a 400)

### B2. Why a 300 ms budget on the overview endpoint?

**Answer:** One overview request needs three things: the latest values, recent history, and the title metadata. I start all three at once and give them one shared deadline of 300 milliseconds, taken from the start of the request, instead of letting each wait as long as it likes. A part that misses the deadline or fails is replaced by an empty value and named in a `degraded` list, and the response is still a 200, so one slow dependency does not fail the whole page. The 300 milliseconds is a target I chose, and I have not measured how often parts degrade.

**Example:** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java:58-63`
```java
        CompletableFuture<Part<List<LatestMetric>>> latest =
                guarded("latest", deadline, () -> latestPort.latest(tenantId, titleId), List.of());
        CompletableFuture<Part<List<MetricPoint>>> history =
                guarded("history", deadline, () -> historyPort.range(recent), List.of());
        CompletableFuture<Part<TitleMetadata>> metadata =
                guarded("metadata", deadline, () -> catalogPort.title(tenantId, titleId), null);
```
All three share the same `deadline` and each carries its own fallback value.

**Code:** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java:52-82` (the fan-out and join), `services/query-api/src/main/resources/application.yml:57-61` (the budget), `services/query-api/src/test/java/com/streampulse/query/application/TitleOverviewServiceTest.java:45` (a 1 second call is degraded and the answer arrives at the budget)

### B3. Why does the endpoint need a `tenantId`, and is that secure?

**Answer:** The `tenantId` scopes every read to one tenant, and a request without one is rejected with a 400. It is not security. The query API has no authentication, so `tenantId` is simply whatever the caller says, and any client can read any tenant. The OpenAPI contract declares a bearer scheme, but nothing validates a token. I treat this as a stated gap that has to be closed before any use outside a laptop, and I would say so plainly in an interview instead of presenting the parameter as an access control.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/web/OverviewController.java:24-27`
```java
    public Mono<TitleOverview> overview(@PathVariable String titleId, @RequestParam String tenantId) {
        if (tenantId.isBlank()) {
            throw new InvalidRequestException("tenantId must not be blank");
        }
```
The check only rejects a blank value; there is no step that proves the caller is allowed to read that tenant.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/web/OverviewController.java:23-30` (the endpoint), `specs/05-query-api/requirements.md:77-79` (the stated gap), `services/query-api/src/test/java/com/streampulse/query/adapter/in/web/QueryControllersTest.java:105` (a missing tenantId is a 400)

### B4. What does the heartbeat on the SSE stream do?

**Answer:** The heartbeat is an SSE comment line sent on a timer, every 15 seconds in the configuration. A comment carries no data and no event id, so the browser ignores it and the resume point does not move. Its only job is to put bytes on an otherwise idle connection so that a proxy or load balancer does not close it for being quiet. The stream of real events is merged with the stream of heartbeats, so either can arrive at any time. The 15 seconds is a target, and I have no verified idle timeout for any particular load balancer to quote, so I would check the setting of the one actually in front of the service.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Heartbeat.java:20-24`
```java
    public <T> Flux<ServerSentEvent<T>> merge(Flux<ServerSentEvent<T>> events) {
        Flux<ServerSentEvent<T>> beats = Flux.interval(period)
                .onBackpressureDrop()
                .map(tick -> ServerSentEvent.<T>builder().comment(COMMENT).build());
        return Flux.merge(events, beats);
```
The beats drop under back-pressure, so a slow client never builds a queue of heartbeats.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Heartbeat.java:9-25` (the class), `services/query-api/src/main/resources/application.yml:65-66` (the 15 second period), `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/HeartbeatTest.java:25` (an idle stream gets a comment)

### B5. How does the API report bad input or a busy server?

**Answer:** Every error leaves as `application/problem+json`, which is what the OpenAPI contract promises. A missing or blank tenant, an inverted time range, an unparseable timestamp and a malformed `Last-Event-ID` are all 400. A metric name the system does not produce is a 404. If the blocking executor is full when the metric history endpoint needs it, the answer is a 503 with a message to retry shortly. The overview is different on purpose: when the executor is full it degrades the affected parts and still answers 200, instead of returning a 503. Controller tests cover the 400 and 404 cases; I did not find a test of the 503 mapping itself.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/web/ProblemAdvice.java:20-33`
```java
    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> invalid(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(UnknownMetricException.class)
    ResponseEntity<ProblemDetail> unknownMetric(UnknownMetricException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown metric", e.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<ProblemDetail> saturated(RejectedExecutionException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Busy", "Blocking capacity is exhausted; retry shortly.");
    }
```
One advice class maps three exception types to three statuses, so the rest of the code throws domain exceptions and never builds an HTTP error.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/web/ProblemAdvice.java:16-41` (the mapping), `services/query-api/src/main/java/com/streampulse/query/application/MetricQueryService.java:24-38` (where the 404, the 400 and the rejection come from), `services/query-api/src/test/java/com/streampulse/query/adapter/in/web/QueryControllersTest.java:139-151` (404 and inverted-range tests)

---

## Mid

### M1. The history call takes 2 seconds. What does the client get?

**Answer:** The client gets a 200 at about the 300 millisecond budget, with `history` named in the `degraded` list and an empty history array. `orTimeout` fires inside the shared deadline, and the fallback replaces that part. What `orTimeout` does not do is cancel the call, so the slow query keeps its worker thread until it returns. That is why the executor is bounded: 8 threads and a queue of 16 that rejects when full, so slow calls cannot pile up without limit, and a rejection degrades the part immediately. A JDBC query timeout of one second, set in whole seconds, eventually stops a stuck query. In the mutation check the agent reported, removing `orTimeout` turned the test for a slow call red.

**Example:** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java:94-102`
```java
        // WHY the executor is bounded: orTimeout completes this future but does NOT cancel the call, which
        // keeps its worker until it returns. An unbounded pool would pile up abandoned calls behind a slow
        // dependency; a bounded one rejects instead, and a rejection degrades the part (REQ-QA-05).
        return running.orTimeout(remainingNanos, TimeUnit.NANOSECONDS)
                .thenApply(value -> new Part<>(value, false))
                .exceptionally(failure -> {
                    LOG.warn("overview part '{}' degraded: {}", part, failure.toString());
                    return new Part<>(fallback, true);
                });
```
The timeout and the fallback are on the returned future; the work itself is still running on its worker.

**Code:** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java:84-103` (the guarded call), `services/query-api/src/main/java/com/streampulse/query/application/BoundedExecutor.java:19-37` (fixed size, bounded queue, abort policy), `services/query-api/src/main/java/com/streampulse/query/config/QueryConfig.java:53-58` (the query timeout), `services/query-api/src/main/resources/application.yml:62-64` (size 8, queue 16), `MILESTONES.md:1343-1345` (the mutation, an agent's report)

**If they push:** "What happens when 8 workers and 16 queued calls are all busy?" The next submission is rejected and the part is degraded at once; `saturatedExecutorDegradesInsteadOfBlocking` asserts the answer comes back in under 100 milliseconds.

### M2. How does replay-then-live avoid losing or duplicating events at the seam?

**Answer:** I subscribe to the live feed into a buffer first, then read the range of entries after `Last-Event-ID` from Redis, then drop any buffered live entry whose id is at or below the last id already sent. Subscribing first means nothing published during the range read is missed, and the id filter means nothing the read already returned is sent twice. Ids are compared as a pair of numbers, millisecond and sequence, because compared as strings `9-0` sorts after `10-0`. Only the live part is coalesced, once every 250 milliseconds, with the latest value per series; replayed entries are sent as they are. A heartbeat comment every 15 seconds keeps proxies from closing an idle stream. The integration test shows resume on a real Redis, but it does not prove behaviour under a Redis outage or at fan-out scale.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:58-69`
```java
            ConnectableFlux<MetricUpdate> held = live.replay(SEAM_BUFFER);
            Disposable subscription = held.connect();
...
            Flux<MetricUpdate> replayed = feed.replayAfter(after)
                    .filter(update -> wanted(update, tenantId, metrics))
                    .filter(update -> update.id().compareTo(lastSent.get()) > 0)
                    .doOnNext(update -> lastSent.set(update.id()));
...
            return Flux.concat(replayed, coalescer.coalesce(afterTheSeam)).doFinally(signal -> subscription.dispose());
```
`connect()` happens before `replayAfter` is read, and `lastSent` is what both the replay filter and the live filter compare against.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:50-71` (resume), `services/query-api/src/main/java/com/streampulse/query/domain/EntryId.java:5-6` (numeric ordering), `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/SseStreamControllerTest.java:107` (an entry landing during the replay is not lost), `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/SseResumeIT.java:72-105` (resume then live on a real Redis)

**If they push:** "What if more than 1,024 entries arrive while the replay is being read?" I have not verified what happens beyond the 1,024-entry seam buffer; the constant's comment says it is the number of entries a resuming client can accumulate in that window, and I did not find a test that exceeds it.

### M3. Tell me about the bug where the live feed failed every idle poll.

**Answer:** I only saw it on the running stack: the query API logged `Redis command timed out` over and over while nothing was happening. The live feed does a blocking read on the Redis stream, and the stream receiver's default block time was 2 seconds, which was also the configured Redis command timeout. An empty poll therefore always ran into the command limit and failed, and the retry loop kept retrying. The fix is to block for half the command timeout, so the read always returns before the client gives up. Every earlier test finished in less than 2 seconds, so none could see it. The regression test, `LiveFeedIdleIT`, sets the command timeout to one second, stays idle for three seconds, and asserts zero read failures; it failed for the right reason before the fix. The lesson is that timeouts that interact must be derived from each other.

**Example:** `services/query-api/src/main/java/com/streampulse/query/config/QueryConfig.java:91-99`
```java
        // Half the command timeout: the blocking read must always return before Lettuce gives up on it.
        Duration commandTimeout =
                redisProperties.getTimeout() == null ? Duration.ofSeconds(2) : redisProperties.getTimeout();
        return new RedisLiveFeed(
                redis,
                properties.stream().key(),
                properties.stream().replayLimit(),
                properties.stream().autoStart(),
                commandTimeout.dividedBy(2));
```
The poll timeout is not its own setting; it is computed from the Redis command timeout.

**Code:** `services/query-api/src/test/java/com/streampulse/query/adapter/out/redis/LiveFeedIdleIT.java:25-49` (the regression test), `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:51-55` (the constraint, written down), `MILESTONES.md:1390-1396` (found only by running it), `docs/interview/05-what-broke.md:153` (story 6)

**If they push:** "How would you have caught it earlier?" With a test that stays idle longer than the command timeout, which is what `LiveFeedIdleIT` now does; every other test was faster than 2 seconds.

### M4. Why WebFlux here but MVC with virtual threads in ingest?

**Answer:** This service holds many long-lived idle streams and fans out reads, which suits a non-blocking model: demand signalling for slow readers, and operators for merging a heartbeat, windowing and replay-then-live. The ingest gateway does blocking work from end to end, so one virtual thread per request is simpler there, and ADR-0006 stands for it. The cost of WebFlux is one rule: never block an event-loop thread. So every blocking call, such as JDBC and the catalogue stub, runs on one bounded, named executor, and a test asserts it. Two programming models in one repo is a real cost that I accepted because the workloads differ. I did not measure MVC against WebFlux, so this is a design argument, not a benchmark.

**Example:** `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:108-112`
```java
        assertThat(REQUEST_THREADS).isNotEmpty().allMatch(name -> name.contains("http-nio"));
        assertThat(PORT_THREADS).hasSize(4);
        assertThat(PORT_THREADS)
                .noneMatch(name -> name.contains("http-nio"))
                .allMatch(name -> name.startsWith("blocking-"));
```
The request thread is on the event loop and every port call is on a `blocking-` thread; matching `http-nio` covers the Boot 3.5 name, whereas an assertion on the old name would pass without checking anything.

**Code:** `docs/adr/0014-webflux-sse-redis-streams.md:21-34` (the comparison), `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:27-34` (what the test proves and the thread-name note), `MILESTONES.md:1346-1349` (the gotcha the agents hit)

**If they push:** "Why not make the JDBC call reactive?" The ADR's choice is to isolate blocking calls on a bounded executor so one deadline and one fallback rule cover all three parts; the reactive Redis client is called from that blocking port.

### M5. Why one shared Redis reader per instance, and what happens to a slow client?

**Answer:** The service runs one reader of the shared Redis stream per instance and fans its output out to every connected client through a hot multicast sink. That way the Redis cost grows with the number of instances, not with the number of viewers. The sink is a best-effort one: a subscriber that cannot keep up misses entries instead of stalling the reader or the other clients. What keeps a slow client current is the per-client coalescer, which holds the latest update per series and emits it on each tick, so a slow client costs one map entry per series instead of a queue. The cost is that intermediate values are dropped by design; a consumer that needs every window uses the history endpoint.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:44-46`
```java
    // directBestEffort: a subscriber that cannot keep up misses entries instead of stalling the reader or the
    // other clients. Each client's Coalescer, not this sink, is what keeps a slow client current.
    private final Sinks.Many<MetricUpdate> hot = Sinks.many().multicast().directBestEffort();
```
The sink choice is a deliberate trade: drop for the slow subscriber so nobody else waits.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:29-33` (one reader per instance), `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Coalescer.java:27-40` (latest per series per tick), `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/CoalescerTest.java:80` (a slow consumer gets the latest, not a backlog)

**If they push:** "Where is the cost of one reader per instance?" Every instance reads the whole stream, which holds all tenants, and discards the entries of tenants its clients did not ask for; that is the scaling limit, and sharding the stream is not done.

### M6. Why does the coalescer drain its map when a tick is processed, not when it fires?

**Answer:** A tick that waits for a slow client should not carry a snapshot. If the map were drained when the timer fired, a slow client would accumulate one batch per missed window. Instead the tick queues only a number, and the map is drained when that tick is finally processed, so whichever tick runs first takes whatever is newest. The backlog therefore holds at most one batch in flight. The pending map is keyed by series, each update replaces the previous one for its series, and the drained list is sorted by entry id so the client sees updates in stream order. The window is 250 milliseconds, which is a target that I did not tune against measurements.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Coalescer.java:34-38`
```java
            // WHY the map is drained when a tick is PROCESSED, not when it fires: a tick that waits for a slow
            // client queues only a Long, and the first one to be processed takes whatever is newest. The backlog
            // therefore holds at most one batch in flight, never one batch per missed window.
            Flux<Long> ticks = Flux.interval(window).onBackpressureDrop();
            return Flux.merge(ticks, intake).concatMapIterable(tick -> drain(pending), 1);
```
The `concatMapIterable` with prefetch of 1 does the draining, and the ticks are dropped when the client is behind.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Coalescer.java:14-52` (the class), `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/CoalescerTest.java:24` (a burst collapses to the last value per series), `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/CoalescerTest.java:80` (slow consumer)

---

## Senior

### S1. How would you scale the SSE feed to many viewers?

**Answer:** Today the cost grows with instances, not only with viewers, because every instance reads the whole shared stream, which holds every tenant's entries, and each client filters out the tenants it did not ask for. The stream is also trimmed to a maximum length across all tenants, so a busy system shortens what a client can resume from. Options I would look at are a stream or consumer group per tenant or shard, a separate fan-out tier in front of the instances, and tuning the coalescing window from measurements. None of that is built. I have not measured the number of concurrent streams per instance at all, so I cannot say where it breaks. I would measure it by opening an increasing number of clients against one instance and watching event-loop latency, memory and Redis load, which is a plan, not a result.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:84-87`
```java
    private static boolean wanted(MetricUpdate update, String tenantId, List<String> metrics) {
        return update.tenantId().equals(tenantId)
                && (metrics == null || metrics.isEmpty() || metrics.contains(update.metric()));
    }
```
The tenant filter runs per client after the shared reader, so the stream still carries every tenant's entries.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:42` (the per-client filter on the shared feed), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/redis/RedisHotProjection.java:27` (the stream length cap), `docs/adr/0014-webflux-sse-redis-streams.md:59-74` (consequences), `docs/deep-dive.md:387` (section 7)

**If they push:** "Why not Redis pub/sub for the fan-out?" Pub/sub has no replay, so `Last-Event-ID` could not work; ADR-0014 chose a stream for that reason.

### S2. The overview "never fails". Is that a good property?

**Answer:** It protects the page from one slow dependency, which is why I built it, but it has a real cost: it can hide an outage behind 200 responses. If Redis is down, every overview still answers with empty latest values and a degraded flag. So the degraded part names have to be visible in the response body, which they are, and ideally also in a metric and an alert. I did not find a metric for the degraded rate in the query API code, and I have not measured that rate, so that part is a gap. All three parts failing still returns a 200 with everything degraded, by a stated requirement. The health probes are state probes only, so a Redis or Postgres blip does not restart or de-route instances.

**Example:** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java:70-78`
```java
            if (latestPart.degraded()) {
                degraded.add("latest");
            }
            if (historyPart.degraded()) {
                degraded.add("history");
            }
            if (metadataPart.degraded()) {
                degraded.add("metadata");
            }
```
The names are collected from the parts and returned in the body; nothing here increments a counter.

**Code:** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java:52-82` (never fails, degraded names), `services/query-api/src/main/resources/application.yml:43-49` (state probes only), `services/query-api/src/test/java/com/streampulse/query/application/TitleOverviewServiceTest.java:80` (every call failing still answers)

**If they push:** "How would you alert on it?" Count degraded parts per name, and alert on a sustained rate instead of any single degraded response; I would add that counter, since it does not exist today.

### S3. What did you measure about reads, and how far can you trust it?

**Answer:** In the Phase 9 run, overview reads under write load showed a p50 of about 24 milliseconds and a p99 of 27 to 30 milliseconds, at about 52 requests a second from four threads, while the write path ran at 1,000 and 2,000 events per second. An idle 15 second sample gave a p50 of 27 and a p99 of 37 milliseconds. This is light, unsaturated load from one Python client opening a new connection per request, on one laptop with every service and the load generator sharing it. It is not a capacity test, and I did not check whether Redis or Postgres served the reads. "Not slower under load" is within the noise of a 15 second idle sample, so I would not claim "faster". The catalogue stub is configured to sleep 20 milliseconds and the three parts run in parallel, so I suspect much of the p50 is that sleep, but I did not test that.

**Code:** `MILESTONES.md:2003-2011` (the read workload and the table), `MILESTONES.md:2039-2042` (the honest gaps), `docs/interview/06-numbers.md:26` (the quotable row), `services/query-api/src/main/resources/application.yml:61` (the stub catalogue latency)

**If they push:** "So what is your read capacity?" I do not know; only one low read rate was run, so I would say it is unmeasured and describe how I would ramp it.

### S4. What happens to the live feed when Redis goes away?

**Answer:** The shared reader retries forever with exponential backoff, from half a second up to ten seconds, and counts each failure. It reads from an explicit cursor, the id of the last entry it saw, rather than from `$`, which means newer than whenever the server handles the read. A restart or a re-subscribe after an outage therefore continues from where it stopped, and nothing in between is skipped. At first start with no cursor it begins at the current tail of the stream, or at the beginning if the stream is empty. A malformed stream entry is skipped and never allowed to kill the shared reader. The overview marks the Redis part degraded and the probes stay up. The honest gap is that no dedicated automated test covers the retry loop; I only observed it by hand by booting with no Redis. I also have not verified how the receiver behaves on an idle poll with the latest offset, so the explicit cursor is my defence either way.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:126-132`
```java
    private Flux<MapRecord<String, String, String>> read() {
        return Flux.defer(() -> {
            String known = cursor.get();
            Mono<String> start = known != null ? Mono.just(known) : tailId();
            return start.flatMapMany(id -> receiver.receive(StreamOffset.create(streamKey, ReadOffset.from(id))));
        });
    }
```
`Flux.defer` re-reads the cursor on every re-subscribe, which is what makes the retry resume instead of restart.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:73-89` (start and the retry), `specs/05-query-api/design.md:79` (the failure table, with the missing test stated), `docs/adr/0014-webflux-sse-redis-streams.md:75-81` (the VERIFY note)

**If they push:** "How do you know the feed is healthy when idle?" The `readFailures` counter should stay at zero on an idle feed; a non-zero value there is how the idle-poll bug showed itself.

### S5. A client reconnects after a long outage. What does it get, and what could it miss?

**Answer:** The resumable window is bounded in two ways. The stream is trimmed to roughly 10,000 entries across all tenants, with approximate trimming, so a busy system shortens the window. The replay range read is also capped by the replay limit, 1,000 entries by default. If the client's `Last-Event-ID` has already been trimmed away, it gets whatever remains after that id, so a longer outage gives a gap, not an error, and the history endpoint is what covers it. Reading the code, if more than 1,000 entries are newer than the client's id, the replay returns the first 1,000 and then the stream continues with live entries, which would leave a silent gap in between. I did not find a test for that case, so that is my reading, not a verified behaviour.

**Example:** `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:113-119`
```java
        return redis.<String, String>opsForStream()
                .range(
                        streamKey,
                        Range.rightUnbounded(Range.Bound.inclusive(after.toString())),
                        Limit.limit().count(replayLimit))
                .flatMap(record -> Mono.justOrEmpty(toUpdate(record)))
                .filter(update -> update.id().compareTo(after) > 0);
```
The `Limit` is where the replay cap lives, and the final filter drops the boundary entry so it is not sent twice.

**Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java:109-120` (the replay read), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/redis/RedisHotProjection.java:27-38` (the stream length cap and approximate trimming), `services/query-api/src/main/resources/application.yml:68` (the replay limit)

**If they push:** "How would you close the gap?" By telling the client to refetch from the history endpoint when its id is older than the oldest retained entry; I have not built that signal.

---

## Say this honestly

- No authentication; tenant is caller-asserted.
- Title metadata comes from a stub catalogue; the real control plane exists but is not wired in here.
- 300 ms, 250 ms and 15 s are targets. Concurrent SSE streams per instance: not measured.

## Stories and numbers

[What broke](../05-what-broke.md): stories 6, 10. [Numbers](../06-numbers.md): read-latency rows.
