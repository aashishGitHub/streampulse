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

**A strong answer contains**
- A one-way, long-lived HTTP stream from server to browser; simpler than WebSocket when the client only
  listens.
- Each event carries an id. After a reconnect the client sends the last id it saw, and the server
  replays what was missed and continues live. Here the id is the Redis stream entry id.
- A malformed id returns 400.

**Open** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java`

### B2. Why a 300 ms budget on the overview endpoint?

**A strong answer contains**
- One request gathers three things (latest values, recent history, title metadata). They run
  concurrently inside one shared deadline.
- A part that misses it is marked degraded and the response is still 200, so one slow dependency does
  not fail the page. The 300 ms is a target.

**Open** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java`

### B3. Why does the endpoint need a `tenantId`, and is that secure?

**A strong answer contains**
- It scopes every read to one tenant. A request with none returns 400.
- It is **not** secure: there is no authentication, so `tenantId` is whatever the caller says.
  Say so plainly; this is a stated gap.

**Open** `services/query-api/src/main/java/com/streampulse/query/adapter/in/web/OverviewController.java`

---

## Mid

### M1. The history call takes 2 seconds. What does the client get?

**A strong answer contains**
- `orTimeout` fires inside the shared 300 ms deadline, that part is degraded, and the response is 200.
- The call itself keeps its worker; a bounded executor (8 threads, queue 16, reject on overflow)
  prevents a pile-up, and a JDBC query timeout stops the work eventually.
- Removing `orTimeout` turned the "slow call is degraded and the budget holds" test red.

**Open** `services/query-api/src/main/resources/application.yml`

### M2. How does replay-then-live avoid losing or duplicating events at the seam?

**A strong answer contains**
- Subscribe to the live feed into a buffer *first*, then read the range after `Last-Event-ID` from
  Redis, then drop live entries at or below the last id already sent.
- Ids are compared numerically, not as strings.
- Live updates are coalesced every 250 ms; replay is not; a heartbeat every 15 s keeps proxies open.

**Open** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java`

### M3. Tell me about the bug where the live feed failed every idle poll.

**A strong answer contains**
- Seen only on the running stack: `Redis command timed out`, forever, while idle.
- The blocking read defaulted to 2 s and the Redis command timeout was also 2 s, so an empty poll
  always hit the limit.
- Fix: block for half the command timeout. `LiveFeedIdleIT` idles for three timeouts and asserts zero
  read failures; it failed for the right reason first.
- Lesson: interacting timeouts must be derived from each other; every test was faster than 2 s.

**Open** `services/query-api/src/test/java/com/streampulse/query/adapter/out/redis/LiveFeedIdleIT.java`

### M4. Why WebFlux here but MVC with virtual threads in ingest?

**A strong answer contains**
- This service holds many long-lived idle streams (SSE) and fans out reads; non-blocking suits that.
- Ingest's work is blocking calls end to end, so a thread per request on virtual threads is simpler.
- A test asserts blocking work is not on the event loop, using the right Netty thread name for Boot 3.5
  (the old name would pass vacuously).

**Open** `docs/adr/0014-webflux-sse-redis-streams.md`

---

## Senior

### S1. How would you scale the SSE feed to many viewers?

**A strong answer contains**
- Today every instance reads the whole shared stream and the stream is trimmed across all tenants, so
  cost grows with instances, not just viewers.
- Options: per-tenant streams or consumer groups, a fan-out tier, and coalescing windows tuned from
  measurements.
- **Not measured:** concurrent streams per instance. Say that, then say how you would measure it.

**Open** `docs/deep-dive.md` section 7.

### S2. The overview "never fails". Is that a good property?

**A strong answer contains**
- It protects the page from one slow dependency, but it can hide a real outage behind 200 responses.
- So the degraded part names must be visible in the body, in a metric, and alerted on. The degraded
  rate was not measured.
- All three parts failing still returns 200 by design (stated requirement).

**Open** `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java`

### S3. What did you measure about reads, and how far can you trust it?

**A strong answer contains**
- Overview p50 about 24 ms and p99 27 to 30 ms at about 52 requests/s from four threads, while the
  write path ran at 1,000 and 2,000 events/s. Idle p99 was 37 ms.
- It is light, unsaturated load from one Python client with a new connection per request; it is not a
  capacity test, and I did not check which store served the reads.
- "Not slower under load" is within the noise of a 15 s idle sample; do not claim "faster".

**Open** `docs/interview/06-numbers.md`

---

## Say this honestly

- No authentication; tenant is caller-asserted.
- Title metadata comes from a stub catalogue; the real control plane exists but is not wired in here.
- 300 ms, 250 ms and 15 s are targets. Concurrent SSE streams per instance: not measured.

## Stories and numbers

[What broke](../05-what-broke.md): stories 6, 10. [Numbers](../06-numbers.md): read-latency rows.
