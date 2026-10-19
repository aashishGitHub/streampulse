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

**A strong answer contains**
- It stops calling a failing dependency for a while so callers fail fast and the dependency can recover.
- Closed (calls pass, failures counted), open (calls refused for a wait period), half-open (a few probe
  calls decide whether to close again).
- Here it wraps the Kafka publisher; thresholds are targets.

**Open** `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md`

### B2. What is the difference between load testing and chaos testing?

**A strong answer contains**
- Load testing raises demand to find limits and tail latency. Chaos or fault injection breaks a dependency
  or the network to see whether the system degrades and recovers as designed.
- Both are needed: a system can be fast and fragile.

**Open** `specs/09-resilience/requirements.md`

### B3. Why measure instead of trusting configuration values?

**A strong answer contains**
- Every limit in the earlier phases (timeouts, pool sizes, thresholds) was chosen to be explainable and
  labelled a target. A measurement is the only thing that turns a target into a fact.
- The repo has about thirty "Phase 9 will measure this" promises; this phase pays them off one by one.

**Open** `specs/09-resilience/tasks.md`

### B4. Why does a 429 from the default configuration not mean the system is saturated?

**A strong answer contains**
- The default per-tenant quota (2,000 events/s refill) is hit long before capacity, so a default ramp stops
  at about 3,000 events/s with 33% refused, exactly what 1 - 2000/3000 predicts.
- To find capacity the quota had to be lifted.

**Open** `docs/interview/06-numbers.md`

---

## Mid

### M1. Why does the load generator need a no-backoff mode?

**A strong answer contains**
- A polite client that obeys `Retry-After` slows itself down at saturation, so the offered rate falls and
  you measure the client's politeness, not the server's ceiling.
- The search holds the requested rate, reports 429 and 503 separately, and warns when the client could not
  offer the rate it was asked for (a breach there says something about the load generator).

**Open** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java`

### M2. What does the saturation finder decide, and how did you check it?

**A strong answer contains**
- A step breaches when its error rate exceeds 1% (a target for "saturated"; exactly 1% does not breach). It
  stops at the first breach, names the dominant cause, and reports the last clean rate.
- Three mutations, each turning the right test red: remove the backoff switch, change `>` to `>=`, remove
  the stop-at-first-breach.

**Open** `tools/loadgen/src/main/java/com/streampulse/loadgen/SaturationFinder.java`

### M3. How can a fault-injection proxy be silently bypassed?

**A strong answer contains**
- A proxy in front of Kafka only intercepts the bootstrap call; the broker then returns its *advertised*
  address and clients connect there directly, so injected faults do nothing.
- Fix: a dedicated listener that advertises the proxy's own port, and a check that asserts the advertised
  address. The first check missed it because it used a fresh client per assertion.

**Open** `docker/toxiproxy/chaos-check.sh`

### M4. How does the accounting check work, and how do you know it can fail?

**A strong answer contains**
- It sends events with known ids, times and sessions into one aggregate key, re-sends batches as a retrying
  client would, advances stream time with a closing event, then compares every closed window with Postgres:
  count, distinct sessions, error ratio.
- To prove it can fail, make it under-count one event; it must go red on exactly that window (it did).

**Open** `scripts/accounting-check.py`

### M5. What did the ingest JVM do while it was shedding load?

**A strong answer contains**
- Heap rose and fell between 45 and 313 MiB and live threads stayed at 34 to 35 while 105,800 events were
  refused, and the server-side rejected count matched the client's 503 count exactly.
- The circuit breaker never opened (no failed calls), so the 503s were not the breaker.
- Limit: 16 seconds of overload cannot rule out a slow leak.

**Open** `docs/interview/05-what-broke.md` story 9.

---

## Senior

### S1. You killed the processor and lost data. Walk me through the investigation.

**A strong answer contains**
- Symptom: windows spanning the outage held a fraction of their events (1,482 of 4,000; 2,554 of 8,000).
- Narrowing: one aggregate-topic record per window, so the sink did not overwrite a good value; the
  processor's WARN count for expired windows equalled the missing events exactly, and the task's
  dropped-records counter agreed.
- Ruling out my test: a rerun with the closing event sent long after recovery still lost data, and the
  stream time at the drops was earlier than that event.
- Mechanism (likely, not proven) and the three fix options with their costs. See Phase 3, S4.

**Open** `docs/interview/05-what-broke.md` story 3.

### S2. Your load generator reports latency from the moment a request is sent. What is the problem?

**A strong answer contains**
- That is the *coordinated omission* risk: when the server is slow, the generator falls behind schedule
  and the delay is not counted as latency, so percentiles look better than a user would experience.
- The code mitigates it by reporting the offered rate and the maximum send lag beside the percentiles, and
  the class comment says so; it does not correct for it.
- A fix is to measure from the *intended* send time.

**Open** `tools/loadgen/src/main/java/com/streampulse/loadgen/Runner.java`

### S3. Design a load-test methodology you would trust.

**A strong answer contains**
- Separate quota from capacity; warm up; hold the rate (no backoff); step up; stop at the first breach;
  record hardware; repeat for variance; run the generator off the machine; measure server-side too (heap,
  threads, queues) not only client latency.
- Cross-check client numbers against server counters (the 105,800 equalled 1,058 x 100).
- Treat any percentile as suspect until it is checked against raw rows (the freshness story).

**Open** `specs/09-resilience/tasks.md`

### S4. What is the honest answer to "how many events per second does it handle?"

**A strong answer contains**
- "On one M2 Pro laptop with a single Kafka broker, the ingest gateway alone was clean at 40,000 per second
  and shed load at 45,000, in one run per configuration, with the load generator on the same machine. It is
  not a capacity figure for the system or for production." The full pipeline was measured only at 1,000 and
  2,000 per second.
- Never say "it handles 45k" without the conditions.

**Open** `docs/interview/06-numbers.md`

### S5. What is still unproven in ingest, and how would you finish it?

**A strong answer contains**
- Which mechanism issued the 503s (the breaker is ruled out; the 64-permit bound is likely but unproven).
  Add a permit gauge, log the 503 type, repeat with a finer step.
- Whether virtual threads pin under load: `-Djdk.tracePinnedThreads` was promised in ADR-0006 and has not
  been run. Flame graphs likewise.

**Open** `docs/adr/0006-virtual-threads-over-webflux.md`

---

## Say this honestly

- The crash test fails and the bug is unfixed; the task stays unticked. Fixing it changes a documented
  guarantee, so it needs an ADR.
- Only a full cut-off (`enabled: false`) has been used through the proxy; latency and bandwidth faults have not.
- The full pipeline was measured at low rates only. The earlier specs still carry "target" labels (task T9-50).

## Stories and numbers

[What broke](../05-what-broke.md): stories 3, 8, 9, 10. [Numbers](../06-numbers.md): all Phase 9 rows.
