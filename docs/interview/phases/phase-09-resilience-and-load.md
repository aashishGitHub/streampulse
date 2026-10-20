# Phase 9 — Resilience, load testing, JVM profiling

**Status:** in progress, and the part that is done produced the most useful results in the project.
**Done:** a fault-injection harness, a saturation search, end-to-end accounting, latency and freshness
measurements, and a crash test that **fails**. **Not done:** load drivers for the control plane, alert
engine and feed; broker-down, datastore-down and partition tests through the proxy; the pinned-thread
check; flame graphs; replacing the "targets" in the earlier specs.

**One line to say first:** "I measured it instead of guessing: on my laptop, ingest alone with the quota
lifted ran clean at 40,000 events a second and shed load at 45,000, and when I killed the stream processor
my own accounting check found that on-time events were being dropped."

## Code map

| Thing | Where |
|---|---|
| Spec, design, tasks | `specs/09-resilience/requirements.md`, `design.md`, `tasks.md` |
| Fault-injection proxy and its check | `docker/toxiproxy/toxiproxy.json`, `docker/toxiproxy/chaos-check.sh` |
| Saturation search | `tools/loadgen/src/main/java/com/streampulse/loadgen/RampRunner.java`, `SaturationFinder.java`, `RampPlan.java` |
| The sender (with a no-backoff mode) | `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java` |
| End-to-end accounting | `scripts/accounting-check.py` |

---

## Beginner

### B1. What is a circuit breaker and what are its states?

**Answer:** A circuit breaker stops calling a failing dependency for a while, so that callers fail fast and the dependency gets room to recover. It has three states. Closed means calls pass through and failures are counted; open means calls are refused immediately for a wait period; half-open means a few probe calls are allowed through, and their result decides whether to close again or to open again. In this project it wraps the event publisher in the ingest gateway, together with a time limiter and a retry. Every threshold in the configuration is a starting point I chose to be explainable, not a measured value, and I have not yet run a fault-injection test that opens it. In the saturation run the breaker never opened.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:222-226`
```yaml
      failure-rate-threshold: 50         # percent failed that opens it
      slow-call-rate-threshold: 50       # percent SLOW that opens it, even if all succeed
      slow-call-duration-threshold: 2s   # above this a SUCCESS still counts as slow
      wait-duration-in-open-state: 30s   # refuse everything this long, then probe
      permitted-calls-in-half-open-state: 5
```
Point at the slow-call settings: a dependency that answers slowly can open the breaker even though every call succeeds.

**Code:** `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md:23-30` (the decision and the nesting), `services/ingest-gateway/src/main/resources/application.yml:216-229` (all thresholds, labelled as not measured)

### B2. What is the difference between load testing and chaos testing?

**Answer:** Load testing raises demand until something gives, so that I learn the limits and the tail latency of a system. Chaos testing, or fault injection, breaks a dependency or the network on purpose, to see whether the system degrades and recovers the way the design says it should. I need both, because a system can be fast and still fragile. In this repo the fault-injection side is a toxiproxy container in front of Kafka, Postgres and Redis, and so far I have used only one fault type through it, a full cut-off of a proxy. Latency and bandwidth faults have not been used, and the broker-down and datastore-down tests that would use the harness are not written yet.

**Example:** `docker/toxiproxy/chaos-check.sh:53-55`
```bash
  set_enabled "$p" false
  if "$check"; then set_enabled "$p" true; fail "$p still reachable with its proxy disabled: traffic bypasses the proxy"; fi
  echo "   unreachable once disabled"
```
Point at the check: it does not assume that disabling a proxy cuts traffic, it proves it for each of the three dependencies.

**Code:** `specs/09-resilience/requirements.md:55-70` (the resilience requirements), `docker/toxiproxy/toxiproxy.json:1-5` (the three proxies)

### B3. Why measure instead of trusting configuration values?

**Answer:** Every limit in the earlier phases, such as timeouts, pool sizes and breaker thresholds, was chosen because I could explain it, and each one is labelled as a target. A target is a guess with a reason attached, and only a measurement turns it into a fact. The repo has about thirty "Phase 9 will measure this" promises, and this phase pays them off one at a time. I am not finished: the task that replaces the target labels in the earlier specs is still open. The numbers I do have come from one laptop, a single Kafka broker and Docker Compose, so they are evidence for this setup and not production figures.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:216-218`
```yaml
    # Circuit breaker + timeout around the publisher (REQ-IG-21 / REQ-RS-02, REQ-RS-06).
    # Starting points chosen to be explainable, not measured — Phase 9 replaces them.
    publisher-resilience:
```
Point at the second comment: it is the promise this phase is supposed to pay, and it has not been paid for the breaker yet.

**Code:** `specs/09-resilience/tasks.md:84-89` (the close-out tasks, still unticked), `specs/09-resilience/requirements.md:9-15` (why this phase exists)

### B4. Why does a 429 from the default configuration not mean the system is saturated?

**Answer:** The default per-tenant quota refills at 2,000 events a second with a burst of 10,000, and that limit is reached long before the service runs out of capacity. My first ramp therefore stopped at 3,000 events a second with 33.4 percent of requests refused, all of them 429. That is exactly what the quota predicts: offering 3,000 against a 2,000 refill means one minus 2,000 over 3,000, which is 33.3 percent, refused. The ramp was correct, it had found the quota and not the capacity. To find capacity I had to lift the quota with environment overrides, and I knew the override had taken effect because no 429 appeared at any step. This was one run on one laptop.

**Example:** `services/ingest-gateway/src/main/resources/application.yml:191-192`
```yaml
    quota-capacity: 10000
    quota-refill-per-second: 2000
```
Point at the two numbers: capacity is the burst, refill is the sustained rate, and the second one is what the first ramp measured.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/SaturationReport.java:16-21` (the QUOTA and CAPACITY causes), `tools/loadgen/src/main/java/com/streampulse/loadgen/SaturationFinder.java:45-53` (how the dominant cause is chosen)

---

## Mid

### M1. Why does the load generator need a no-backoff mode?

**Answer:** The original runner obeys the `Retry-After` header. That is polite, but at saturation it slows itself down, so the offered rate falls and I would be measuring the client's politeness instead of the server's ceiling. The Phase 3 run could never have found a limit for that reason. The saturation search constructs the runner with the backoff switched off, so it holds the requested rate, and it reports 429 and 503 separately. It also warns when the client could not offer the rate it was asked for, because a breach in that case says something about the load generator. A test pins the behaviour: with every response a 429, a runner told not to back off must keep sending, and I confirmed by mutation that removing the switch turns that test red.

**Example:** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java:149-152`
```java
    private void pause(String retryAfterHeader) {
        if (!honourRetryAfter) {
            return;
        }
```
Point at the early return: with the flag off, a 429 or 503 never sets the pause that would lower the offered rate.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java:48-62` (the six-argument constructor), `tools/loadgen/src/test/java/com/streampulse/loadgen/RunnerTest.java:122-131` (the test that went red in the mutation)

**If they push:** "How do you know the test really catches it?" I removed the flag check in `pause` and ran the suite; the no-backoff test failed, expecting more than 10 requests and getting 2, and I restored the code afterwards.

### M2. What does the saturation finder decide, and how did you check it?

**Answer:** It walks the ramp steps in order and declares a step saturated when its error rate is above 1 percent. Exactly 1 percent is not a breach, and the 1 percent itself is a target for what counts as saturated, not a measured number. It stops at the first breach, because pushing a server that is already failing harder measures nothing new, and it names the dominant cause by comparing the 429, 503 and other counts. It reports the last clean rate before the breach. I checked it with three mutations, each of which turned the right test red: removing the backoff switch, changing the greater-than to greater-or-equal, and removing the stop at the first breach. Each was restored afterwards.

**Example:** `tools/loadgen/src/main/java/com/streampulse/loadgen/SaturationFinder.java:11-18`
```java
    /** REQ-RL-01. A target for what counts as "saturated"; the measured rate is the deliverable. */
    static final double ERROR_RATE_LIMIT = 0.01;

    private SaturationFinder() {}

    public static boolean breaches(StepResult step) {
        return step.errorRate() > ERROR_RATE_LIMIT;
    }
```
Point at the strict greater-than: the boundary test fails if it becomes greater-or-equal.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/SaturationFinder.java:20-43` (the analysis loop), `tools/loadgen/src/test/java/com/streampulse/loadgen/SaturationFinderTest.java:65-69` (exactly one percent is not a breach), `tools/loadgen/src/main/java/com/streampulse/loadgen/RampRunner.java:18-29` (stop at first breach)

**If they push:** "Is 1 percent the right limit?" I do not know; it is a choice I labelled as a target, and I would revisit it against the latency a real client could tolerate.

### M3. How can a fault-injection proxy be silently bypassed?

**Answer:** A TCP proxy in front of Kafka only intercepts the bootstrap connection. The broker then answers with its advertised address, and a long-lived client connects there directly, so every fault I inject at the proxy would do nothing and the tests would pass for the wrong reason. The fix is a dedicated `CHAOS` listener on the broker that advertises the proxy's own address, and a check that asserts the advertised address. My first check missed the problem because it opened a fresh client for every assertion, so each one went through the proxy again. I proved the new check can fail by repointing the proxy at the internal listener through the toxiproxy API: the check failed and named the advertised address.

**Example:** `docker/compose.yml:62-66`
```yaml
      # CHAOS (Phase 9): a client bootstraps via the proxy, then connects to whatever address the
      # broker ADVERTISES. Without a listener advertising the proxy's own port, clients bypass
      # toxiproxy after the first metadata call and every injected Kafka fault does nothing.
      KAFKA_LISTENERS: PLAINTEXT://:19092,CONTROLLER://:9093,PLAINTEXT_HOST://:9092,CHAOS://:29093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:19092,PLAINTEXT_HOST://localhost:9092,CHAOS://localhost:29092
```
Point at `CHAOS://localhost:29092` in the advertised listeners: it is the proxy's port, not the broker's.

**Code:** `docker/toxiproxy/chaos-check.sh:39-46` (the advertised-address assertion), `docker/toxiproxy/toxiproxy.json:2` (the Kafka proxy pointing at the CHAOS listener on 29093)

**If they push:** "Does the proxy prove any service handles a broker outage?" No. The harness is proven; no service has been run through it, and the broker-down test is still unwritten.

### M4. How does the accounting check work, and how do you know it can fail?

**Answer:** It sends events with known ids, times and sessions into one aggregate key, using a unique region for each run. It re-sends every fifth batch byte for byte, the way a retrying client would, then waits past the last window's close and sends one closing event to advance stream time. After that it compares every closed window in Postgres with what it sent: the event count, the number of distinct sessions and the error ratio. With 2,000 and with 30,000 events, every closed window matched, and the 400 and 6,000 re-sent duplicates were not double-counted. To prove it can fail, I made it under-count one event in its own bookkeeping, and it went red on exactly that window. That mutation tests the verdict logic, not loss detection end to end. It covers closed windows and one aggregate key only.

**Example:** `scripts/accounting-check.py:188-191`
```python
        ok = (viewers is not None and errors is not None
              and errors[1] == exp["events"] and viewers[1] == exp["events"]
              and int(viewers[0]) == len(exp["sessions"])
              and abs(errors[0] - exp["errors"] / exp["events"]) < 1e-9)
```
Point at the three comparisons: sample count against events sent, distinct sessions against viewers, and error ratio against the errors sent.

**Code:** `scripts/accounting-check.py:1-14` (what it answers and what it does not), `scripts/accounting-check.py:150-152` (the byte-identical re-send)

**If they push:** "Why a script and not an integration test?" A Testcontainers version would need Kafka, Postgres, Redis and four services in one test, and it would duplicate what this script already checks against the real stack.

### M5. What did the ingest JVM do while it was shedding load?

**Answer:** I sampled the ingest JVM every 2 seconds through the whole ramp. While 105,800 events were being refused, heap used rose and fell between about 45 and 313 MiB, and live threads stayed at 34 to 35. The server-side rejected count matched the client's 503 count exactly, since 1,058 batches of 100 events is 105,800. That fits shedding by rejection rather than by queueing. The circuit breaker never opened, with 44,046 successful and 0 failed publisher calls, so the 503s did not come from the breaker. The limits matter: it was only about 16 seconds of overload on one laptop, which cannot rule out a slow leak, and I did not read the JVM's configured maximum heap.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:123-127`
```java
        if (!acquired) {
            metrics.recordRejectedForCapacity(command.size());
            throw new BackpressureException(
                    "ingest saturated: no capacity within " + permitWait.toMillis() + "ms", permitWait);
        }
```
Point at the bounded wait: a request that cannot get an in-flight permit within the wait is refused instead of queued, which is the shedding behaviour the heap and thread numbers are consistent with.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:113-131` (permit acquire and release), `services/ingest-gateway/src/main/resources/application.yml:184-185` (64 in-flight batches, 250 ms permit wait), `docs/interview/05-what-broke.md:125-133` (story 9)

**If they push:** "So the 64-permit bound caused the 503s?" It is the likely cause by elimination, but I did not see a response body or a log line, so I call it unproven.

### M6. How does the load generator tell you the client, not the server, was the limit?

**Answer:** For every step it computes the offered rate, which is the events it actually sent divided by the wall time, and compares it with the rate it was asked for. If the offered rate is below 90 percent of the target, the step is flagged as client-limited, and the report names the first such step. A breach at a client-limited step would describe the load generator, not the service. At the breach step in the saturation run the generator offered about 44,357 events a second of the 45,000 requested, which is about 98.6 percent, so it was not the limit there. The 90 percent cutoff is a heuristic I chose, and the generator shared the laptop's CPU with ingest, so the real ceiling is probably higher; that direction is a guess, not a measurement.

**Example:** `tools/loadgen/src/main/java/com/streampulse/loadgen/StepResult.java:22-24`
```java
    public boolean clientLimited() {
        return offeredRate() < targetRate * CLIENT_LIMITED_BELOW;
    }
```
Point at the multiplication by the 0.9 constant: the check is relative to what was asked for, so it works at any rate.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/StepResult.java:7-8` (the cutoff constant), `tools/loadgen/src/test/java/com/streampulse/loadgen/SaturationFinderTest.java:101-115` (a client-limited step is flagged)

**If they push:** "What would you do about it?" Run the generator on another machine, which I did not do, and repeat the ramp to get a variance estimate, which I also did not do.

---

## Senior

### S1. You killed the processor and lost data. Walk me through the investigation.

**Answer:** ADR-0012 claims that after a crash nothing is lost and nothing is counted twice, so I tested it: `kill -9` on the stream processor partway through an accounting run, restart, and compare every closed window. There were no duplicates, but the windows that spanned the 40 to 50 second outage held only a fraction of their events: 1,482 of 4,000 missing in the first run and 2,554 of 8,000 in the second. I narrowed it down. The aggregate topic had one record per window per metric, so the sink had not overwritten a good value. The processor logged one expired-window warning per missing event, matching the count exactly, and the task's dropped-records counter agreed. To rule out my own test, I re-ran with the closing event sent long after recovery; data was still lost, and stream time at the drops was earlier than that event. The mechanism, likely but not proven, is that catching up a backlog across 12 partitions advances stream time past windows that still have on-time events. It is unfixed, and each fix changes a documented guarantee.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:109-121`
```java
                .windowedBy(TimeWindows.ofSizeAndGrace(config.windowSize(), config.grace()))
                ...
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded())
                        .withName("close-windows"))
```
Point at the grace period on the window and the suppress step: output waits until stream time passes the window plus grace, and records arriving after that are dropped with the warning I counted.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:109-121` (window, grace and suppression), `scripts/accounting-check.py:97-98` (the `--at` hook that runs kill and restart at a chosen batch), `docs/interview/05-what-broke.md:45-67` (story 3)

**If they push:** "What are the fixes?" A larger grace, which makes freshness slower by the same amount; emitting revised windows, which ends the one final answer per window; or holding stream time back until the backlog drains. Choosing one changes a guarantee, so it needs an ADR, and I have not made it. I also did not try a graceful restart, a shorter outage or a second instance. See Phase 3, S4.

### S2. Your load generator reports latency from the moment a request is sent. What is the problem?

**Answer:** That is the coordinated omission risk. When the server is slow, the generator cannot send on schedule, so it falls behind, and the delay it suffers while waiting to send is not counted as latency. The percentiles then look better than a real user's experience would be. The class comment on the runner says so directly: latency is measured from the moment the request is sent, so under a slow server the offered rate drops instead of the latency growing. The code mitigates this by reporting the offered rate and the maximum send lag beside the percentiles, and by flagging client-limited steps, but it does not correct for it. A proper fix is to measure from the intended send time, and I have not done that.

**Example:** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java:15-20`
```java
/**
 * Flow: one scheduler thread builds a batch at each fixed-rate tick, takes an in-flight permit,
 * and hands the POST to a virtual thread. Latency is measured from the moment the request is
 * sent, so when the server is slow the offered rate drops rather than the latency growing; that
 * is why offered events/s and max send lag are reported next to the percentiles.
 */
```
Point at the second sentence: it is a stated limitation, and the maximum send lag is the signal to read next to it.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java:91-92` (the send lag is recorded when a permit is taken), `tools/loadgen/src/main/java/com/streampulse/loadgen/RunSummary.java:9-25` (the offered and lag fields in the summary)

**If they push:** "Does it change your 40,000 figure?" The clean steps offered within 0.35 percent of their target rate (the worst was 14,949.9 against 15,000), and the breach step offered 98.6 percent, so the rate was held. The latency percentiles at saturation are still the less trustworthy part.

### S3. Design a load-test methodology you would trust.

**Answer:** I would separate quota from capacity first, as I learned from my first ramp, then warm up, hold the rate with no backoff, step up in fixed increments and stop at the first breach. I would record the hardware, repeat for variance, which I did not do, and run the generator off the machine, which I also did not do. I would measure server-side as well as client latency, meaning heap, threads and queues, and cross-check client numbers against server counters: the client's 1,058 refused batches of 100 events equalled the server's 105,800 rejected events. Finally I would treat any percentile as suspect until it is checked against raw rows, which is the lesson of the freshness story. What I ran follows most of that list but with one run per configuration and a shared machine.

**Example:** `tools/loadgen/src/main/java/com/streampulse/loadgen/RampRunner.java:18-29`
```java
    /** Stops at the first breach: pushing a server that is already failing harder measures nothing new. */
    public static SaturationReport run(RampPlan plan, StepExecutor executor) throws IOException, InterruptedException {
        List<StepResult> results = new ArrayList<>();
        for (long rate : plan.rates()) {
            StepResult result = new StepResult(rate, executor.run(rate, plan.stepSeconds()));
            results.add(result);
            if (SaturationFinder.breaches(result)) {
                break;
            }
        }
        return SaturationFinder.analyse(results);
    }
```
Point at the `break`: it is the stop-at-first-breach rule, and a mutation that removed it turned a test red.

**Code:** `tools/loadgen/src/main/java/com/streampulse/loadgen/RampPlan.java:25-32` (the plan always tries the ceiling), `specs/09-resilience/tasks.md:17-43` (the ramp tasks and what each one measured)

**If they push:** "What would you add first?" A second machine for the generator and three repeats of each step, so the gap between 40,000 and 45,000 comes with a variance estimate.

### S4. What is the honest answer to "how many events per second does it handle?"

**Answer:** On one Apple M2 Pro laptop with a single Kafka broker, the ingest gateway alone, with the per-tenant quota lifted, was clean at 40,000 events a second and began shedding with 503 at 45,000, with an 11.8 percent error rate at that step. That was one run per configuration, the load generator shared the machine, and nothing downstream was running. It is not a capacity figure for the system or for production. The full pipeline was measured only at 1,000 and 2,000 events a second, where ingest p99 was 76.5 ms at 1,000, and 70.9 and 59.0 ms in two runs at 2,000, with freshness of about 5.3 seconds per window. I never say "it handles 45k" without those conditions.

**Code:** `docs/interview/06-numbers.md:14-27` (the measured rows and their sources), `docs/interview/06-numbers.md:65-72` (how to say a number out loud), `specs/09-resilience/tasks.md:26-43` (T9-11 and T9-13 as recorded)

**If they push:** "Why is the pipeline number so much lower than the ingest number?" It is not a comparison: the pipeline runs were deliberately held under the default quota and did not look for a limit. The downstream stages' own limits were not found.

### S5. What is still unproven in ingest, and how would you finish it?

**Answer:** Two things. First, which mechanism issued the 503s. The circuit breaker is ruled out by its metrics, and the 64-permit bound is the likely cause by elimination, but I did not see the response body or a log line, and my rough estimate from the mean publish time suggests only a handful of permits would be in use at that rate, so the explanation is not obvious. To finish it I would add a permit gauge, log the 503 type, and repeat with a finer step. Second, whether virtual threads pin under load. ADR-0006 promised a run with `-Djdk.tracePinnedThreads`, and I have not done it; flame graphs are not done either, and both are open tasks. The ADR itself carries a VERIFY note that a newer JDK may remove this pinning, so I would check the JDK before relying on either the problem or the fix.

**Example:** `docs/adr/0006-virtual-threads-over-webflux.md:53-57`
```text
- **Pinning.** Under Java 21, a virtual thread that blocks inside a `synchronized` block
  pins its carrier, which can starve the carrier pool. We prefer `ReentrantLock` around
  blocking calls, and Phase 9 measures it with `-Djdk.tracePinnedThreads`.
  **VERIFY:** JEP 491 reportedly removes this pinning — check your JDK before relying on
  either the problem or the fix.
```
Point at the word measures: the ADR made a promise that this phase has not kept yet.

**Code:** `docs/adr/0006-virtual-threads-over-webflux.md:53-57` (the promise), `specs/09-resilience/tasks.md:77-83` (T9-40 and T9-41, unticked), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:123-127` (the permit-wait 503 path)

**If they push:** "Why not fix it now?" Without evidence on which path issued the 503s, I would be changing code to match a guess. The permit gauge makes the next run decisive.

---

## Say this honestly

- The crash test fails and the bug is unfixed; the task stays unticked. Fixing it changes a documented
  guarantee, so it needs an ADR.
- Only a full cut-off (`enabled: false`) has been used through the proxy; latency and bandwidth faults have not.
- The pinned-thread check and the flame graphs are not done (tasks T9-40 and T9-41).
- The full pipeline was measured at low rates only. The earlier specs still carry "target" labels (task T9-50).

## Stories and numbers

[What broke](../05-what-broke.md): stories 3, 8, 9, 10. [Numbers](../06-numbers.md): all Phase 9 rows.
