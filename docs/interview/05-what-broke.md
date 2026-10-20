# What broke when it was actually run

These stories answer "tell me about a hard bug", "tell me about a time you were wrong" and "how do you know your tests work". Every one of them happened in local runs of this project, on a laptop with Docker Compose and a single Kafka broker, and none happened in production. Say that out loud when you tell them. The fictional design scenarios called BS-01 to BS-10 are a different thing: they are design exercises, and you never describe one of them as an incident you had.

Each story is written the way you would tell it: the situation, what I saw, what it really was, how I fixed it and how I know the fix works, and the lesson. Learn three or four well and keep the rest in reserve. After each story there is the real code, copied from the repository, and a reference you can open.

## How to tell one in two minutes

Start with one sentence of context, then give the symptom with a number in it, then the real cause in terms of mechanism. Describe the fix and, most importantly, how you know it works, which means naming the test and the mutation that proves the test can fail. Finish with one sentence of lesson. Interviewers press hardest on the "how do you know" step, and this repository is built to answer that question.

---

## 1. The breaker that made a silent data-loss bug worse

**The story.** While adding a circuit breaker to the ingest publisher I had to ask what happens to a batch that fails to publish. The answer was worse than I expected. The service claims every event id in Redis before it publishes, so that a retried request is recognised as a duplicate. When the publish failed, the claims stayed behind. The client then retried, every id looked like a duplicate, and ingest answered 202 Accepted with nothing published, so the client dropped its copy and the events were gone for good while being reported as a success. A breaker makes this much worse because it fails fast for its entire open window of thirty seconds, so every batch in that window would be swallowed instead of the occasional one.

**The fix and how I know it works.** I treated a claim as a reservation, and a reservation that is never used has to be given back, so a failed publish now releases the claims before the error is rethrown. Two tests pin the behaviour in both directions. One shows that a failed publish leaves no claim behind so the retry works, and the other makes the release itself fail and asserts the damage, so nobody can later delete the release as defensive code that never fires.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:171-186`
```java
try {
    publishAndWait(command, newEvents);
} catch (RuntimeException e) {
    // COMPENSATING ACTION. These ids were claimed a few lines above but never made it
    // to the broker, so the reservation has to be given back.
    //
    // Without this the client's retry finds every id already claimed, gets a 202 with
    // nothing published, drops its copy, and the events are gone for good -- reported
    // as success. The circuit breaker makes it strictly worse: it fails fast for its
    // entire open window, so EVERY batch in those 30 seconds would be swallowed.
    //
    // release() is best-effort and never throws, so the caller still sees the real
    // publish failure rather than a secondary one from Redis. See IdempotencyStore.
    idempotencyStore.release(command.tenantId(), claim.claimed());
    throw e;
}
```

The comment in the code is the explanation: the ids were claimed a few lines earlier but never reached the broker, so the reservation has to be given back, and the release is best-effort so the caller still sees the real publish failure.

**The lesson.** An idempotency key turns a retry into a no-op, which is exactly why a key claimed for work that never happened is dangerous. For every failure path I now ask what the retry will see.

**If they push:** "What if the release itself fails?" Then that retry is lost until the claim's twenty-four hour expiry, which is the opposite failure, and it is why the release is best-effort and counted rather than allowed to mask the original error.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:171-186` (the compensating release), `services/ingest-gateway/src/main/java/com/streampulse/ingest/domain/IdempotencyStore.java:65` (the release contract), `MILESTONES.md:423` (the write-up).

---

## 2. Nine of ten tests passed with the decorators in the wrong order

**The story.** The ingest publisher is wrapped so that a retry sits outside a circuit breaker, which sits outside a time limiter, which sits around the publish call. When I swapped two of those lines on purpose to see what would happen, nine of the ten tests in that suite stayed green. Only the one test written for that specific property failed, and it failed with the breaker expected to be open but still closed. The reason it matters is that with the breaker inside the time limiter, a broker that hangs rather than errors never trips the breaker, because the breaker only sees calls that return. With virtual threads there is no thread pool to exhaust and no thread-count alarm, just a growing pile of blocked threads each holding an in-flight permit.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:184-192`
```java
Supplier<CompletionStage<Void>> timed =
        TimeLimiter.decorateCompletionStage(timeLimiter, timeoutScheduler, call);

// MIDDLE: count failures, and reject outright once the rate is past the threshold.
Supplier<CompletionStage<Void>> guarded = CircuitBreaker.decorateCompletionStage(circuitBreaker, timed);

// OUTER: ride over a single fast blip. Shares the scheduler with the time limiter --
// it only sleeps between attempts, so one thread covers both jobs.
Supplier<CompletionStage<Void>> retried = Retry.decorateCompletionStage(retry, timeoutScheduler, guarded);
```

Read from the inside out, the time limiter cuts a hanging call, the breaker counts that timeout as a failure, and the retry rides over a single fast blip. Retry is outermost so that every attempt is counted by the breaker.

**The lesson.** A passing test suite only proves the code passes the tests. Mutating the code on purpose is how I find out whether the tests would have noticed, and I now do that for the properties that matter.

**If they push:** "Why not retry timeouts?" Each attempt holds an in-flight permit, so retrying a five second timeout would turn one permit-held failure into about fifteen seconds of held capacity, which makes an incident worse.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java:184-192` (the decoration order), `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisherTest.java:200` (the ordering proof), `MILESTONES.md:451` (the mutation write-up).

---

## 3. Killing the stream processor lost on-time events (the best senior story, and still open)

**The story.** ADR-0012 claims that Kafka Streams exactly-once means no loss and no double counting across a crash. I tested it by running `kill -9` on the processor partway through a run, restarting it, and then comparing every closed window in Postgres with what I had sent, using an accounting script with a hook that runs a command at a chosen batch. There were no duplicates, but the windows spanning the forty to fifty second outage held only a fraction of their events: 1,482 of 4,000 events were lost in one run and 2,554 of 8,000 in another.

**What it really was.** Kafka did not lose the data. The aggregation step logged `Skipping record for expired window` once for every missing event, and the counts matched exactly, with the task's dropped-records counter agreeing. Raw events are keyed by session, so one aggregate's events arrive from twelve raw partitions. When the processor works through the backlog after a restart, later events can reach the window before earlier events from other partitions, stream time jumps past windows that still have on-time events, and the five second grace period treats them as late. I ruled out my own test by rerunning with the closing event sent long after the processor had recovered, and the loss was the same; the stream time at the drops was earlier than that event.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:109-121`
```java
.windowedBy(TimeWindows.ofSizeAndGrace(config.windowSize(), config.grace()))
.aggregate(
        WindowAccumulator::empty,
        (key, event, acc) -> acc.add(ObservationMapper.toObservation(event)),
        Materialized.<String, WindowAccumulator, WindowStore<Bytes, byte[]>>as(AGGREGATE_STORE)
                .withKeySerde(Serdes.String())
                .withValueSerde(new WindowAccumulatorSerde()))
// Emit only when the window AND its grace period have closed. Without this, every
// late event re-emits the window and a downstream consumer sees a stream of
// revisions instead of one answer. The cost: output waits for stream time to
// advance, so a quiet partition holds its last window open.
.suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded())
        .withName("close-windows"))
```

The window, the grace period and the suppress that holds back each result until its window closes are all in these lines, and the grace is the number that is too small for a restart backlog.

**What I have not done.** I have not fixed it. Every fix changes a documented guarantee: a larger grace makes every window slower by the same amount, emitting revised windows gives up the one-final-answer contract, and holding stream time back until the backlog drains is an architectural change that needs an ADR. The mechanism above is likely but not proven, and I have not verified whether the `max.task.idle.ms` setting helps.

**The lesson.** Exactly-once is a statement about Kafka topics and offsets, and it says nothing about whether event-time logic sees events in an order its grace period can absorb. The service's own metrics also had no late-drop counter, so in production this would have been invisible.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:109-121` (window, grace, suppress), `scripts/accounting-check.py:126-130` (the hook that kills and restarts a service mid-run), `MILESTONES.md:1903` (the full evidence).

---

## 4. "Docker is broken" was wrong, twice

**The story.** Integration tests hung for twenty minutes with no error and I blamed the Docker daemon, committed the tests marked as unverified, and wrote that diagnosis into the log. It was wrong. `docker info` had answered the whole time. The build had pinned Testcontainers to an old Docker Desktop socket path, and after an upgrade that socket still existed and still accepted a connection, then never replied. The check in the build was whether the socket file exists, and a dead socket passes that check.

**The fix.** I ordered the candidates by what Docker Desktop serves today and wrote the ten-second `curl --unix-socket` probe into the comment so the next person can re-check in seconds instead of losing an afternoon.

**Example:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:165-169`
```kotlin
val dockerDesktopSocket = listOf(
    File(home, ".docker/run/docker.sock"),                                  // Docker Desktop, current
    File("/var/run/docker.sock"),                                           // Linux, CI, and the macOS symlink
    File(home, "Library/Containers/com.docker.docker/Data/docker.raw.sock"), // older Docker Desktop
).firstOrNull { it.exists() }
```

**The lesson.** Silence looks exactly like a dead dependency and often is not one. I now probe the thing the code actually talks to before blaming the platform, and I wrote the correction into the log instead of quietly fixing it.

**Code:** `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts:165-169` (the ordered candidates), `MILESTONES.md:370` (the correction).

---

## 5. A bug that only exists when two branches meet

**The story.** After rebasing the Keycloak branch onto the gRPC branch, all four health-probe tests failed with `gRPC server failed to bind port 9090: Address already in use`. Neither branch was wrong alone. Each test class passes alone, because one Spring context binds port 9090 happily, and it only fails when a second context starts in the same JVM, which means it appears in a full build and in CI and never while you work on the failing class.

**Example:** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:55-61`
```java
// streampulse.ingest.grpc.port=0 asks the OS for a free port. Without it the gRPC server
// binds the configured 9090, and the SECOND @SpringBootTest context in the JVM dies with
// "Address already in use" -- a failure that appears only once more than one context runs,
// which is to say only in a full build and never when you run the class on its own.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "streampulse.ingest.grpc.port=0")
```

The fix is the property in the last lines: asking the operating system for a free port.

**The lesson.** Any listener on a fixed port is a shared global between test contexts, in the same way a static field is. `RANDOM_PORT` covers HTTP, but every other listener needs the same treatment explicitly.

**Code:** `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java:55-61` (the property and the comment), `MILESTONES.md:1143` (the write-up).

---

## 6. The live feed failed every idle poll, and every test was too fast to see it

**The story.** On the running stack the query service logged `Redis command timed out` forever while it was idle. The Redis stream reader blocks for two seconds by default, and the Redis command timeout was also two seconds, so an empty poll always hit the limit. Every unit and integration test finished faster than two seconds, so none of them could see it.

**The fix.** The block time is now derived as half the command timeout, so the blocking read always returns before the client gives up on it. The regression test idles the feed for three command timeouts and asserts that there are no failed reads, and it failed for the right reason before the fix.

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

**The lesson.** Two timeouts that interact have to be derived from each other rather than set independently, and some bugs only appear when a system is idle.

**Code:** `services/query-api/src/main/java/com/streampulse/query/config/QueryConfig.java:91-99` (the derived block time), `services/query-api/src/test/java/com/streampulse/query/adapter/out/redis/LiveFeedIdleIT.java:42-48` (the regression test).

---

## 7. A randomised concurrency test stayed green with the lock removed

**The story.** In the sink's batch writer I removed the lock to see what would happen. The randomised repeated test, run with sixteen and forty-eight threads, stayed green. Only a deterministic test that stalls the enqueue at the right moment went red. The race is nanoseconds wide, so a hundred green runs only show that the interleaving was not hit, not that it cannot happen. This result came from an agent's report, and I re-ran the tests myself.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:120-139`
```java
// The read lock makes "is it open?" and "enqueue" one step with respect to close().
// Without it: submit sees open, close() sets closed, the flusher sees an empty queue and
// exits, then submit enqueues: an accepted record that nothing will ever write.
// ReentrantReadWriteLock rather than synchronized: synchronized pins a virtual thread's
// carrier while it blocks, and submit blocks in offer().
lock.readLock().lock();
try {
    if (closed) {
        return rejected(SubmissionRejectedException.Reason.CLOSED);
    }
    if (!queue.offer(new Pending<>(value, future), offerTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
        return rejected(SubmissionRejectedException.Reason.BUFFER_FULL);
    }
    return future;
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return rejected(SubmissionRejectedException.Reason.INTERRUPTED);
} finally {
    lock.readLock().unlock();
}
```

The comment explains the race: submit sees the writer open, close marks it closed, the flusher sees an empty queue and exits, and then submit enqueues a record that nothing will ever write.

**The lesson.** Randomised concurrency tests give false confidence. Force the interleaving you are afraid of instead of hoping a random run finds it.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:120-139` (the lock around check and enqueue), `docs/deep-dive.md` section 4 (the longer treatment).

---

## 8. A fault-injection proxy that clients would have bypassed

**The story.** I put toxiproxy in front of Kafka so that tests could cut the broker off on purpose. A plain TCP proxy only intercepts a Kafka client's bootstrap connection. The broker then tells the client its advertised address, and the client talks to that address directly, so every fault I injected would have done nothing to a running service while the tests passed. My first check did not notice because it opened a fresh client for every assertion.

**The fix.** I added a dedicated listener that advertises the proxy's own port, plus a check that asserts the advertised address. Repointing the proxy at the wrong listener made that check fail and name the address it found.

**Example:** `docker/compose.yml:65-66`
```yaml
KAFKA_LISTENERS: PLAINTEXT://:19092,CONTROLLER://:9093,PLAINTEXT_HOST://:9092,CHAOS://:29093
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:19092,PLAINTEXT_HOST://localhost:9092,CHAOS://localhost:29092
```

**Example:** `docker/toxiproxy/chaos-check.sh:39-40`
```bash
# Disabling the proxy only proves a NEW client is cut off. A long-lived client that bootstrapped
# through the proxy then talks to the broker's advertised address, so that address must be the proxy.
```

**The lesson.** Prove that the fault actually bites, meaning that disabling the proxy makes the dependency unreachable, rather than only proving that traffic flows through it.

**Code:** `docker/compose.yml:65-66` (the extra listener and its advertised address), `docker/toxiproxy/chaos-check.sh:39-40` (why the check exists), `MILESTONES.md:1640` (the write-up).

---

## 9. My first load test would have measured the quota, not the system

**The story.** The first ramp stopped at 3,000 events a second with 33.4 percent of requests refused, all with 429. That is exactly what the per-tenant token bucket predicts: it refills at 2,000 events a second, so at 3,000 offered, one minus 2,000 over 3,000, which is 33.3 percent, must be refused. The ramp was correct, it had found the quota, so to find capacity I had to lift the quota, and with it lifted ingest alone was clean at 40,000 events a second and began returning 503 at 45,000. The old load generator also obeyed `Retry-After`, so at saturation it slowed itself down and measured its own politeness, which is why the saturation search runs with that switched off.

**Example:** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java:149-156`
```java
private void pause(String retryAfterHeader) {
    if (!honourRetryAfter) {
        return;
    }
    long until =
            System.nanoTime() + Pacing.retryAfterNanos(retryAfterHeader, RETRY_AFTER_FALLBACK, RETRY_AFTER_CAP);
    pausedUntil.accumulateAndGet(until, Math::max);
}
```

**What is still unproven.** I do not know which mechanism issued the 503 responses. The circuit breaker never opened, with no failed calls, so it was not the breaker. The in-flight permit bound is the likely cause but I have not seen it directly. All of this is one run on one laptop with the load generator on the same machine.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java:149-156` (no-backoff mode), `MILESTONES.md:1740` (the runs and the honest gaps).

---

## 10. A metric that lied about freshness

**The story.** The load generator reported a freshness p99 of 39 seconds in one run and 113 seconds in another, while the per-window rows in Postgres said 5.3 seconds. A run's last window stays open until later events arrive, which is the quiet-stream limit, so the next run's first events closed it and it was recorded in the next run's numbers, very late. A warm-up does not fix it, because the warm-up leaves its own last window open. I confirmed it with a per-window query: the previous run's windows were 34 and 24 seconds late and every window of the current run was 5.3 seconds.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java:75-77`
```java
public void freshness(Duration lag) {
    freshness.record(lag);
}
```

The metric records the lag between window end and the durable write for every window the sink writes, which is why a late-closing window from an earlier run lands in the current histogram.

**The lesson.** I check a percentile against the raw rows before I believe it, and I know what can poison a histogram.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java:75-77` (where freshness is recorded), `MILESTONES.md:1996` (the per-window evidence).

---

## 11. I over-reported test counts, and corrected it

**The story.** Early entries in my log showed per-module test totals under a `$ ./gradlew build` prompt. Gradle prints no such table: a script produced it by summing test result files, and it was still counting twenty-five results from a module I had deleted, so every total was twenty-five too high and a figure of 132 was really 107. It was found because a later run's total dropped, and someone asked why instead of quietly reporting the bigger number.

**The lesson.** I never put reconstructed output under a command prompt, and any counting script has to read the real module list.

**Code:** `MILESTONES.md:1109` (the correction), `settings.gradle.kts:1` (the module list that the counting script now reads).

---

## 12. CI has failed on every run, because a test names threads after one operating system

**The story.** I had assumed continuous integration was fine because the build passes on my machine. When I looked at the GitHub Actions history, every run on main since the twelfth of October had failed or been cancelled. The last three failed in the same place: a query-api test called `BlockingIsolationTest` asserts that request threads are Netty event-loop threads by checking that their names contain `http-nio`. On my Mac that is true. On the Linux runner Reactor Netty uses the epoll transport, so the threads are named `webflux-http-epoll-2`, and the assertion fails with exactly that list in the message.

**What it really means.** The positive assertion fails loudly on Linux, but the negative assertion below it is the worse problem. It says no blocking port may run on a thread whose name contains `http-nio`, and on Linux no thread name contains that string, so that half of the test would pass even if a blocking call did run on the event loop. The test has quietly stopped testing what it claims to test on the platform that matters.

**Example:** `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:108-112`
```java
assertThat(REQUEST_THREADS).isNotEmpty().allMatch(name -> name.contains("http-nio"));
assertThat(PORT_THREADS).hasSize(4);
assertThat(PORT_THREADS)
        .noneMatch(name -> name.contains("http-nio"))
        .allMatch(name -> name.startsWith("blocking-"));
```

**The fix I would make, not applied yet.** Match the event-loop threads by a pattern that covers every transport instead of one operating system's name, for example `name.matches(".*http-(nio|epoll|kqueue).*")` in both assertions. Gradle stops at the first failing module, so there may be other failures behind this one that I cannot see yet. An earlier run on the twelfth of October failed in thirteen seconds for a reason I did not examine.

**The lesson.** "Passes on my machine" is not evidence, and a thread-name assertion is a platform assumption. I also learned to check the CI history instead of assuming, because I had written in my own notes that I did not know whether it had ever run.

**Code:** `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java:108-112` (the assertions), `.github/workflows/ci.yml:46-49` (what CI runs).

---

## Which to pick for which question

For "a hard bug" use story 1 if you want something that was fixed and story 3 if you want to show how you investigate. For "a time I was wrong" use story 4 or story 12. For "how do you know your tests work" use stories 2, 7 and 12. For "tell me about reliability" use stories 3 and 8. For "how do you measure" use stories 9 and 10.
