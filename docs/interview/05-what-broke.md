# What broke when it was actually run

These are the answers to "tell me about a hard bug", "tell me about a time you were wrong", and
"how do you know your tests work". Every one happened **in local runs of this project** (a laptop,
Docker Compose, a single Kafka broker), not in production. Say so. The fictional design scenarios
BS-01 to BS-10 are a different thing: they are design exercises and never "an incident I had".

Each story is told as: **situation → what I saw → what it really was → fix → the lesson**, with
where the evidence lives. Pick three or four and know them cold; the rest are reserves.

---

## 1. The breaker that made a silent data-loss bug worse (best "hard bug" story)

- **Situation.** Adding a circuit breaker to the ingest publisher made me ask what happens to a
  batch that fails to publish.
- **What it really was.** `IngestService` claimed every event id in Redis (`SET NX EX`) *before*
  publishing. When the publish failed, the claims stayed. The client retried, every id looked like a
  duplicate, ingest answered **202 Accepted with zero events published**, and the client dropped its
  copy. Silent, permanent loss reported as success. A breaker makes it worse: it fails fast for its
  whole 30 s open window, so every batch in that window would be swallowed.
- **Fix.** `IdempotencyStore.release(...)` as a compensating action: a claim is a reservation, and an
  unused reservation is given back. Two tests pin it in both directions, including one that drives a
  fake whose release fails and asserts the damage, so nobody deletes `release()` later as dead code.
- **Lesson.** Idempotency keys turn a retry into a no-op, which is exactly why a key claimed for work
  that never happened is dangerous. Ask "what does the retry see?" for every failure path.
- **Evidence.** `MILESTONES.md`, "A data-loss bug the circuit breaker work uncovered";
  `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`.

## 2. Nine of ten tests passed with the decorators in the wrong order

- **Situation.** The publisher is wrapped as `CircuitBreaker(TimeLimiter(publish))`.
- **What I saw.** Swapping the two lines on purpose, nine of ten tests stayed green. Only the test
  written for that one property failed: `a hanging broker times out, and the breaker counts it FAILED`
  (expected OPEN, was CLOSED).
- **Why it matters.** With the breaker inside the time limiter, a broker that *hangs* (rather than
  errors) never trips it. With virtual threads there is no pool to exhaust and no thread-count alarm,
  just a growing pile of blocked threads each holding an in-flight permit.
- **Lesson.** A passing suite proves the code passes the tests. Mutating the code is how you learn
  whether the tests would notice. This repo uses deliberate mutations as a habit (see `MILESTONES.md`,
  search "mutation").
- **Evidence.** `MILESTONES.md`, "Verified — circuit breaker and time limiter";
  `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md`.

## 3. Killing the stream processor lost on-time events (best "senior" story; still open)

- **Situation.** ADR-0012 claims Kafka Streams exactly-once v2 means no loss and no double counting
  across a crash. I tested it: `kill -9` on the processor partway through a run, restart, then compare
  every closed window in Postgres with what was sent (`scripts/accounting-check.py --at`).
- **What I saw.** No duplicates, but the windows spanning the 40 to 50 s outage held only a fraction
  of their events: 1,482 of 4,000 lost, then 2,554 of 8,000 in a second run.
- **What it really was.** Not Kafka losing data. The aggregation step logged
  `WARN Skipping record for expired window` **once per missing event**, matching exactly, with the
  task's `dropped_records_total` agreeing. Raw events are keyed by session, so one aggregate's
  events are spread across 12 partitions. Catching up a backlog, the processor fed later events to
  the window before earlier ones from other partitions, stream time jumped past windows that still
  had on-time events, and the 5 s grace treated them as late.
- **How I ruled out my own test.** The first run's closing event raced the recovery, so I re-ran with
  it sent long after processing resumed. Still lost, and the stream time at each drop was earlier
  than the closing event.
- **What I have not done.** Fixed it. Every fix changes a documented guarantee: a larger grace
  (freshness gets slower by the same amount), emitting revised windows (no longer one final answer
  per window), or holding stream time back until the backlog drains. That is an ADR decision.
- **Lesson.** "Exactly-once" is a statement about Kafka topics and offsets. It says nothing about
  whether event-time logic sees events in an order its grace period can absorb. Also: the service's
  own metrics had no late-drop counter, so this would have been invisible in production.
- **Evidence.** `MILESTONES.md`, "Phase 9 · T9-34"; `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`.

## 4. "Docker is broken" was wrong, twice (best "time I was wrong" story)

- **Situation.** Integration tests hung for 20 minutes with no error. I blamed the Docker daemon and
  committed the tests marked unverified.
- **What it really was.** The build pinned Testcontainers to an old Docker Desktop socket path. After
  an upgrade that socket still existed and still accepted `connect()`, then never replied. The check
  was `if (socket.exists())`, and a dead socket passes it. `docker info` worked the whole time.
- **Fix.** Order the socket candidates by what Docker Desktop serves today, and put the ten-second
  `curl --unix-socket` probe in the comment.
- **Lesson.** Silence looks like a dead dependency and often is not one. Probe the thing the code
  actually talks to before blaming the platform. I also wrote the correction into the log rather
  than quietly fixing it.
- **Evidence.** `MILESTONES.md`, "CORRECTION: Docker Desktop was never broken".

## 5. A bug that only exists when two branches meet

- **What I saw.** After rebasing the Keycloak branch onto the gRPC branch, all four `HealthProbesIT`
  tests failed: `gRPC server failed to bind port 9090: Address already in use`.
- **What it really was.** Two `@SpringBootTest` contexts in one JVM, both starting a gRPC server on
  the fixed port. Run either class alone and it passes; it only fails in a full build or CI.
- **Lesson.** Any listener on a fixed port is a shared global between test contexts, like a static
  field. `RANDOM_PORT` covers HTTP; every other listener needs `port=0` explicitly.
- **Evidence.** `MILESTONES.md`, "A defect found only by combining the two branches".

## 6. The live feed failed every idle poll, and every test was too fast to see it

- **What I saw.** On the running stack, query-api logged `Redis command timed out` forever while idle.
- **What it really was.** The Redis stream reader blocks for 2 s by default, and the Redis command
  timeout was also 2 s. A blocking read that finds nothing times out at exactly the command limit.
  Every test finished faster than 2 s, so none could see it.
- **Fix.** Derive the block time as half the command timeout; `LiveFeedIdleIT` is the regression
  test, and it failed for the right reason first.
- **Lesson.** Two timeouts that interact must be derived from each other, not set independently. And
  some bugs only appear when a system is idle.
- **Evidence.** `MILESTONES.md`, "Day 5 · the whole pipeline, running", item 1.

## 7. A randomised concurrency test that stayed green with the lock removed

- **What I saw.** In the sink's batch writer, removing the lock left the `@RepeatedTest(100)` race test
  green at 16 and 48 threads. Only a deterministic test that stalls `offer` went red.
- **Lesson.** Randomised concurrency tests give false confidence. Force the interleaving you fear.
- **Evidence.** `MILESTONES.md`, "Days 3-4 · services/sink-writer, services/query-api" (an agent
  ran this mutation; I re-ran the tests); `docs/deep-dive.md` section 4.

## 8. A fault-injection proxy that clients would have bypassed

- **Situation.** I put toxiproxy in front of Kafka so tests could cut the broker off on purpose.
- **What it really was.** A TCP proxy only intercepts a Kafka client's *bootstrap* connection. The
  broker then tells the client its advertised address, and the client talks there directly. Every
  injected fault would have done nothing to a running service, and the tests would have "passed".
  My first check did not catch it because it opened a fresh client per assertion.
- **Fix.** A dedicated `CHAOS` listener that advertises the proxy's own port, plus a check on the
  advertised address. Repointing the proxy at the wrong listener made the check fail and name the
  address.
- **Evidence.** `MILESTONES.md`, "Phase 9 · T9-02"; `docker/toxiproxy/chaos-check.sh`; `docker/compose.yml`.

## 9. My first load test would have measured the quota, not the system

- **What I saw.** The first ramp stopped at 3,000 events/s with 33.4% refused, all 429.
- **What it really was.** The per-tenant token bucket refills at 2,000/s, so 1 - 2000/3000 = 33.3%
  must be refused. The ramp was correct; it found the quota. Capacity needed the quota lifted:
  then 40,000 clean and 503s at 45,000.
- **Also.** The old load generator obeyed `Retry-After`, so at saturation it slowed itself down and
  measured its own politeness. The saturation search runs with backoff off.
- **Evidence.** `MILESTONES.md`, "Phase 9 · T9-10" and "Phase 9 · T9-11".

## 10. A metric that lied about freshness

- **What I saw.** The load generator reported freshness p99 of 39 s and 113 s. Postgres said 5.3 s.
- **What it really was.** A run's last window stays open until later events arrive (the quiet-stream
  limit). The next run's first events close it, so it is recorded 24 s to over 100 s late inside the
  next run's numbers. A warm-up does not help, because the warm-up leaves its own window open.
- **Lesson.** Check a percentile against the raw rows before believing it. The per-window query
  showed the leftovers instantly.
- **Evidence.** `MILESTONES.md`, "Phase 9 · T9-13" and "Day 5 · the whole pipeline, running", item 2.

## 11. I over-reported test counts, and corrected it

- **What happened.** Early entries showed per-module test totals under a `$ ./gradlew build` prompt.
  Gradle prints no such table; a script produced it, and it was still counting 25 results from a
  deleted module. Totals were 25 too high (132 was really 107).
- **Lesson.** Never put reconstructed output under a command prompt, and make the counting read the
  real module list. Found because a later run's total *dropped* and someone asked why.
- **Evidence.** `MILESTONES.md`, "CORRECTION: every test total before this entry was 25 too high".

---

## How to tell one in two minutes

1. One sentence of context ("ingest claims event ids in Redis before publishing").
2. The symptom, with the number ("202 Accepted with zero events published").
3. The real cause, in mechanism terms.
4. The fix and **how I know it works** (the test, and the mutation that proves the test can fail).
5. The general lesson, one sentence.

Interviewers probe step 4 hardest. "How do you know?" is the question this repo is built to answer.
