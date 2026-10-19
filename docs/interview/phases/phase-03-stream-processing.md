# Phase 3 — Stream processing (Kafka Streams)

**Status:** done, with one open defect. Exactly-once held at the Kafka level, but killing the processor
and restarting it **dropped on-time events** (1,482 of 4,000 and 2,554 of 8,000). Not fixed. Not
built: a late-drop metric, idle-partition heartbeats, a schema registry, HyperLogLog sessions.

**One line to say first:** "A Kafka Streams job that validates, de-duplicates, and aggregates events
into 10-second event-time windows with 5 seconds of grace, emitting exactly one final record per
window per metric."

## Code map

| Thing | Where |
|---|---|
| The topology (decode, DLT, dedup, re-key, window, suppress) | `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java` |
| Duplicate drop, before the repartition | `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/EventIdDeduplicator.java` |
| Pure aggregation state (events, errors, session set) | `services/stream-processor/src/main/java/com/streampulse/processor/domain/WindowAccumulator.java` |
| Validation and the DLT reasons | `services/stream-processor/src/main/java/com/streampulse/processor/domain/EventValidator.java` |
| Window, grace, future-skew, exactly-once config | `services/stream-processor/src/main/resources/application.yml` |
| Topology tests with `TopologyTestDriver` | `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java` |
| A recovery test (happy path only) | `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorRecoveryIT.java` |
| Decisions | `docs/adr/0012-kafka-streams-primary.md` |

## Flow

```
telemetry.raw.v1 (key = session, 12 partitions)
  ─ decode + validate ──▶ rejected ──▶ telemetry.raw.v1.dlt   (original bytes + dlt-reason header)
  ─ dedup by event id  (state store, range = window + grace = 15 s, still keyed by session)
  ─ re-key to tenant:title:region      ← one repartition topic (the price of keying by session upstream)
  ─ 10 s tumbling window, 5 s grace ─ aggregate (events, errors, distinct sessions)
  ─ suppress until window closes       ← one final answer per window; costs freshness
  ─▶ telemetry.agg.v1 (6 partitions, key = tenant:title:region:METRIC:windowStart)
```

---

## Beginner

### B1. What is event time, and how does it differ from processing time?

**A strong answer contains**
- Event time is when it *happened* (the device's timestamp); processing time is when the system *sees*
  it. They differ because of delay, retries and outages.
- Windows here use event time, so a late arrival still lands in the window it belongs to. Ingest sets
  the record timestamp to `occurredAt`, so no custom extractor is needed.
- The risk: an event can arrive too late for its window (B3).

**Open** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`

### B2. What does a 10 s tumbling window with 5 s grace mean?

**A strong answer contains**
- Fixed, non-overlapping 10 s buckets of event time. Grace is how long after a window ends it still
  accepts late events; it closes when stream time reaches `window end + grace`.
- So a final result cannot appear sooner than that: freshness is bounded below by window plus grace.
- Both values are configured **targets**.

**Open** `services/stream-processor/src/main/resources/application.yml`

### B3. What happens to an event that arrives after its window's grace period?

**A strong answer contains**
- The windowed aggregation drops it; Kafka Streams logs `Skipping record for expired window` and
  counts it in a built-in dropped-records metric. A test proves a late event is dropped, not emitted.
- This is intended for genuinely late events. The interesting case is *on-time* events dropped after a
  long outage (S4).

**Open** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java`

### B4. What is a dead-letter topic and what goes into it?

**A strong answer contains**
- A side topic for records the pipeline cannot process, so one bad record does not stop the stream.
- Here: undecodable bytes, or events failing validation (invalid event id, invalid tenant, blank
  session, event time too far in the future). Original bytes are kept with a `dlt-reason` header; the
  reason is an enum because it becomes a low-cardinality metric tag.

**Open** `services/stream-processor/src/main/java/com/streampulse/processor/domain/EventValidator.java`

### B5. Why validate by hand instead of letting the deserialiser throw?

**A strong answer contains**
- A deserialisation exception can stop the stream thread. One corrupt record would halt the pipeline.
- Decoding inside the topology turns it into a routed record: a dead letter.
- A future-timestamp guard (5 minutes) stops a device with a bad clock advancing stream time past real
  windows.

**Open** `services/stream-processor/src/main/resources/application.yml`

---

## Mid

### M1. Why `suppress(untilWindowCloses)`, and what does it cost?

**A strong answer contains**
- Without it every late event re-emits the window and downstream sees a stream of revisions, not one
  answer. With it, one final record per window.
- Cost: output waits for stream time to advance, so a quiet partition holds its last window open.
- Mutation: removing it turned 8 topology tests red.

**Open** `docs/adr/0012-kafka-streams-primary.md`

### M2. Why is the dedup before the repartition?

**A strong answer contains**
- Raw events are keyed by session, and a retried event carries the same session, so a duplicate is on
  the same partition and a task-local store sees both copies.
- After re-keying, a duplicate could land on a different task, and a local store would miss it.
- The dedup range equals window plus grace (15 s); beyond it the window would drop the event anyway.
  At a hypothetical 1 M events/s that is about 30 M ids in state (arithmetic, not measured).

**Open** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/EventIdDeduplicator.java`

### M3. Why are all operators explicitly named?

**A strong answer contains**
- Unnamed operators get names from their position, which also name internal topics and state stores.
  Adding one operator renames the others and orphans existing state.
- Naming is a compatibility decision, like a schema.

**Open** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`

### M4. How do you test a topology without a broker?

**A strong answer contains**
- `TopologyTestDriver`, with the topology as a pure function with an injected clock.
- Stream time only advances with later events, so tests pipe a "tick" record to close a window.
- The state store cache is set to zero so window updates are not delayed.
- Three mutation checks were run: remove suppress (8 red), grace 5 s to 0 (2 red), dedup that never
  remembers (1 red).

**Open** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java`

### M5. What does the recovery test prove, and what does it not?

**A strong answer contains**
- `StreamProcessorRecoveryIT` shows an open window survives a restart and is published once, and a
  published window is not republished.
- The "crash" is a closed Spring context with one partition, not `kill -9` and not many partitions.
  That is exactly why it could not catch the loss in S4. The spec says recovery was verified for the
  happy path only.

**Open** `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorRecoveryIT.java`

---

## Senior

### S1. Where exactly does exactly-once end?

**A strong answer contains**
- `exactly_once_v2` makes read, process and write-to-Kafka atomic: output records, the state changelog
  and the consumed offsets commit in one transaction. It does **not** cover anything that leaves Kafka.
- The sink to Postgres is at-least-once, made safe by an idempotent upsert plus ack-after-write
  (Phase 4).
- And S4 shows a further limit: Kafka-level exactly-once did not make the *aggregates* correct.
- Honest phrasing: "effectively once = at-least-once delivery plus idempotent processing".

**Open** `docs/adr/0012-kafka-streams-primary.md`

### S2. How would you size grace?

**A strong answer contains**
- Freshness floor = window + grace; measured aggregates land 5.2 to 5.4 s after window end.
- Size it from the observed lateness distribution, not by guessing, and from the longest outage you
  intend to survive (S4).
- A bigger grace raises latency for *every* window permanently. Alternative: emit provisional results
  and let the upsert overwrite them (revisions), which gives up the one-final-answer contract.

**Open** `docs/interview/05-what-broke.md` story 3.

### S3. What is the quiet-stream limit and how do you handle it in production?

**A strong answer contains**
- Windows close on stream time, which only advances with new events, so a quiet partition holds its
  last window open indefinitely (stated in ADR-0012).
- Options: a synthetic heartbeat per partition, monitoring the age of the oldest open window, or
  accepting and documenting it.
- It appeared for real: a load run's last window was closed by the *next* run's first events, which
  corrupted the load generator's own freshness histogram.

**Open** `docs/adr/0012-kafka-streams-primary.md`

### S4. Tell me about killing the processor. What happened and what would you do?

**A strong answer contains**
- `kill -9` mid-run, restart; no duplicates, but windows spanning the 40 to 50 s outage held a fraction
  of their events. The count of `Skipping record for expired window` warnings equalled the missing
  events exactly (1,482 and 2,554), and the task's dropped-records counter agreed.
- Likely mechanism (consistent with evidence, **not proven**): events of one aggregate come from 12
  raw partitions; working off the backlog feeds the repartition out of event-time order, stream time
  jumps past windows that still have on-time events, and 5 s grace treats them as late. The loss was
  clustered (18 of 100 sessions appeared), where random loss would touch about 79.
- Ruled out my own test: the stream time at the drops was earlier than the closing event I sent.
- Options and costs: a larger grace; emit revisions; hold stream time until the backlog drains. Each
  changes a documented guarantee, so it needs an ADR. Also add a late-drop metric: today it is silent.
- **Unverified:** whether `max.task.idle.ms` helps. It applies within a task, not across upstream
  tasks writing to one repartition partition. Check the Kafka Streams docs before proposing it.

**Open** `docs/interview/05-what-broke.md` story 3.

### S5. How would you prove no loss end to end?

**A strong answer contains**
- A sent-versus-stored invariant per closed window: unique ids, re-sent duplicates, count, distinct
  sessions and error ratio, compared with Postgres.
- Mutation-test the checker (under-count one event; it must go red on that window).
- Then inject real faults during the run; that step is what exposed the defect. The happy-path run
  alone passed.
- Limits: one aggregate key, no late events.

**Open** `scripts/accounting-check.py`

### S6. What is the repartition costing, and what would you change?

**A strong answer contains**
- Keying by session upstream gives per-session order and cheap dedup, but aggregation needs another
  key, so one extra write and read through an internal topic.
- A single hot `tenant:title:region` maps to one task; salting is described but not built.
- Possible redesign: aggregate before re-keying (partial aggregates per partition) to cut repartition
  volume and reduce the out-of-order exposure. A hypothesis, not tested.

**Open** `docs/deep-dive.md` section 2.

---

## Say this honestly

- Single broker, replication factor 1; `acks=all` is checked as configuration only.
- No schema registry; the wire format is raw Avro binary.
- The grace and window are targets. The freshness *result* is measured.
- Not tested: graceful SIGTERM, a second instance taking over, a short outage.
- The repartition topic's partition count is unconfirmed (VERIFY).

## Stories and numbers

[What broke](../05-what-broke.md): stories 3, 10. [Numbers](../06-numbers.md): freshness, accounting and
crash rows.
