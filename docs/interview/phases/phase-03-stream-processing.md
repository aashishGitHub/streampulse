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

**Answer:** Event time is when something happened, which here is the device's own timestamp, and processing time is when the system gets to see it. They differ because of network delay, client retries and outages, so an event can arrive long after it was produced. The windows in this job use event time, which means a late arrival still lands in the window it belongs to instead of in whichever window is open when it shows up. The ingest gateway sets the Kafka record timestamp to `occurredAt`, and the topology reads the topic without any custom timestamp extractor, so Kafka Streams takes event time straight from the record. The risk is that an event can arrive too late for its window even so, which is the grace period and the late-drop question in B3.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:86-88`
```java
        KStream<String, Decoded> decoded = builder.stream(
                        config.rawTopic(), Consumed.with(Serdes.String(), Serdes.ByteArray()))
                .mapValues(raw -> Decoded.of(raw, events, validator), Named.as("decode-and-validate"));
```
Notice that `Consumed` carries only serdes and no timestamp extractor, so the record timestamp that ingest set is what drives windows.

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java:70-78` (where the timestamp is set), `docs/adr/0012-kafka-streams-primary.md` (event time comes from the record timestamp)

### B2. What does a 10 s tumbling window with 5 s grace mean?

**Answer:** A tumbling window is a fixed, non-overlapping bucket of event time, here ten seconds wide, so an event belongs to exactly one window. Grace is how long after a window has ended it still accepts late events: the window closes only when stream time reaches the window end plus the grace. That gives a floor on freshness, because a final result cannot appear sooner than window plus grace, which is 15 seconds from the window start. In the measured full-pipeline runs the aggregates landed about 5.2 to 5.4 seconds after the window end, on one laptop with a single broker. The 10 and 5 second values are configured targets that I chose to be explainable, not values I derived from measured lateness.

**Example:** `services/stream-processor/src/main/resources/application.yml:61-62`
```yaml
    window-size: 10s
    grace: 5s
```
These two values feed `TimeWindows.ofSizeAndGrace` in the topology and, through `dedupRange`, the size of the de-duplication state as well.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:109` (the window definition), `docs/interview/06-numbers.md` (freshness row and the window target)

### B3. What happens to an event that arrives after its window's grace period?

**Answer:** The windowed aggregation drops it. Kafka Streams logs `Skipping record for expired window` and counts the drop in its built-in dropped-records metric, and the window is not re-emitted. A topology test proves exactly this: it pipes an event for window one after stream time has passed the close, and asserts that no record for that window appears and that the dropped-records counter is above zero. That behaviour is intended for events that are genuinely late. The interesting case is on-time events that get dropped after a long outage, which is the open defect in S4. The service has no counter of its own for these drops, so today only the built-in Kafka Streams metric and a warning log show them.

**Example:** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:211-222`
```java
    void lateAfterGraceIsDroppedNotEmitted() {
        heartbeat("s-1", T0.plusSeconds(5));
        tickPastWindowOne();
        ...
        heartbeat("s-late", T0.plusSeconds(6));
        heartbeat("tick-2", T0.plusSeconds(40));
        ...
        assertThat(later).noneMatch(a -> a.getWindowStart().equals(T0));
        assertThat(droppedRecords()).isGreaterThan(0.0);
```
The late heartbeat belongs to window one but arrives after stream time passed 12:00:15, so it is dropped and counted.

**Code:** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:315-320` (the dropped-records helper reads the built-in metric), `MILESTONES.md:1935-1938` (the warning seen in the crash run)

### B4. What is a dead-letter topic and what goes into it?

**Answer:** A dead-letter topic is a side topic for records the pipeline cannot process, so one bad record does not stop the stream and is not silently lost either. In this job it receives undecodable bytes and events that fail validation: an invalid event id, an invalid tenant id, a blank session id, or an event time too far in the future. The record is written with its original bytes and a `dlt-reason` header, because re-encoding a decoded event could change it, and an undecodable one cannot be re-encoded. The reason is an enum rather than free text because the name is written to a header and used as a metric tag, so it has to be stable and low-cardinality. Free text would blow up the number of metric series.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/domain/RejectionReason.java:11-17`
```java
public enum RejectionReason {
    UNDECODABLE,
    INVALID_EVENT_ID,
    INVALID_TENANT_ID,
    BLANK_SESSION_ID,
    EVENT_TIME_IN_FUTURE
}
```
Five reasons, which is the complete list of what can enter the dead-letter topic today.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/domain/EventValidator.java:31-47` (the checks and their order), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/Decoded.java:17-31` (decode or reject, keeping the raw bytes)

### B5. Why validate by hand instead of letting the deserialiser throw?

**Answer:** A deserialisation exception can stop the stream thread, so one corrupt record would halt the whole pipeline. The topology therefore reads the value as raw bytes and decodes inside a `mapValues` step, turning a decode failure into a value that carries a rejection reason and routing it to the dead-letter topic. The same validator also protects event time. It rejects an event whose timestamp is more than five minutes ahead of the clock, because a device with a wrong clock would otherwise advance stream time past every real window and make them all look late. An event from the past is deliberately valid, because lateness is the window's grace period to judge, and a device that was offline for an hour is not malformed. The five-minute limit is a configured target, not a measured value.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/Decoded.java:21-25`
```java
        try {
            event = codec.decode(raw);
        } catch (AvroDecodeException e) {
            return new Decoded(raw, null, RejectionReason.UNDECODABLE);
        }
```
The exception is caught inside the topology, so a bad record becomes data, not a crash.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/domain/EventValidator.java:41-45` (the future-skew guard), `services/stream-processor/src/main/resources/application.yml:63-64` (`max-future-skew` of 5 minutes)

---

## Mid

### M1. Why `suppress(untilWindowCloses)`, and what does it cost?

**Answer:** Without suppression, every late event inside the grace period re-emits the window, so downstream sees a stream of revisions instead of one answer. With it, the job emits one final record per window per metric, when the window and its grace have closed. The cost is freshness: output waits for stream time to advance, so a quiet partition holds its last window open, which ADR-0012 states as a limit. There is a second cost I can see in the code, which is that the suppression buffer is configured as unbounded, so the state held until windows close is not capped, and I have not measured its memory. To check that the test suite really depends on suppression, I removed it as a mutation and eight topology tests went red.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:120-121`
```java
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded())
                        .withName("close-windows"))
```
Point at `BufferConfig.unbounded()`: it is the memory cost of holding every open window until it closes.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:116-119` (the comment on why), `docs/adr/0012-kafka-streams-primary.md` (the quiet-stream consequence)

**If they push:** "Could you emit revisions instead?" Yes, and let the sink upsert overwrite them, but that gives up the one-final-answer contract that downstream relies on.

### M2. Why is the dedup before the repartition?

**Answer:** Raw events are keyed by session, and a retried event carries the same session, so a duplicate lands on the same partition as its original and a state store local to one task sees both copies. After re-keying to tenant, title and region, a duplicate could land on a different task, and a local store would miss it. The de-duplication range equals window plus grace, 15 seconds, because a duplicate arriving beyond that would be dropped by the window anyway, so remembering it longer would cost state for no benefit. The store itself keeps twice that range, 30 seconds, as retention. At a hypothetical one million events per second that is about 30 million ids in state, and that figure is arithmetic from an interview prompt, not something I measured.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/EventIdDeduplicator.java:53-60`
```java
        try (WindowStoreIterator<Long> earlier = seen.fetch(id, at - rangeMillis, at + rangeMillis)) {
            if (earlier.hasNext()) {
                metrics.duplicateDropped();
                return;
            }
        }
        seen.put(id, at, at);
        context.forward(record);
```
The lookup is relative to the event's own timestamp, not the wall clock, and a hit is dropped and counted.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:78-84` (retention is twice the range), `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:226-239` (an event id seen twice counts once)

**If they push:** "Does bigger grace cost anything here?" Yes: `dedupRange` is window plus grace, so raising grace also raises the retention and size of the de-duplication state.

### M3. Why are all operators explicitly named?

**Answer:** Kafka Streams derives the names of internal topics and state stores from an operator's position in the topology, so an unnamed operator is named by where it sits. If I add one operator later, the names of the others can shift, which renames the repartition topic and the state stores and orphans the state already on the broker. Naming every operator pins those identities, so naming is a compatibility decision in the same way that a schema is. In this topology the names are `decode-and-validate`, `dedup-by-event-id`, `key-by-aggregate`, `close-windows` and the rest, and I believe the name given to `Grouped` is what names the repartition topic, though I have not looked at the topic list on a running broker to confirm it. The class comment in the topology states the same reason.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:100-108`
```java
                .processValues(
                        () -> new EventIdDeduplicator(config.dedupRange(), metrics),
                        Named.as("dedup-by-event-id"),
                        EventIdDeduplicator.STORE)
                .selectKey(
                        ...
                        Named.as("key-by-aggregate"))
                .groupByKey(Grouped.with("by-aggregate-key", Serdes.String(), eventSerde))
```
Every step carries an explicit name, and the dedup store has a constant name too.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:47-49` (the reason, in the class comment), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/EventIdDeduplicator.java:29` (the store name)

### M4. How do you test a topology without a broker?

**Answer:** I use `TopologyTestDriver`, which runs the topology in memory with no broker, and I keep the topology a pure function of its arguments with an injected `Clock`, so there is no Spring and no wall clock inside it. Stream time only advances when a later record arrives, so most tests pipe a tick record after 12:00:15 to close window one, and one test shows that without the tick nothing is emitted. I set the state store cache to zero so that window updates are not delayed to the next commit, which would make the assertions depend on timing the topology does not promise. I also ran three mutations to see whether the tests notice: removing suppress turned eight tests red, setting grace from five seconds to zero turned two red, and a dedup that never remembers turned one red.

**Example:** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:80-129`
```java
        props.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);
        ...
        driver = new TopologyTestDriver(TelemetryTopology.build(config, validator, CLOCK, metrics), props);
        ...
    private void tickPastWindowOne() {
        heartbeat("tick", T0.plusSeconds(16));
    }
```
The first line disables the cache and the helper at the bottom is the tick that advances stream time past 12:00:15.

**Code:** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:44-50` (why a tick is needed), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:65-69` (build takes the clock)

### M5. What does the recovery test prove, and what does it not?

**Answer:** `StreamProcessorRecoveryIT` shows two things against a real broker in a container: a window that was open when the processor stopped survives a restart and is published once, and a window that was already published is not published again. It deletes the local state directory before the restart, so the open window can only come back from the changelog topics and the committed offsets. What it does not do is crash the process the hard way: the crash is a closed Spring context, not `kill -9`, and every topic has a single partition so there is one task and stream time is deterministic. That is exactly why it could not catch the loss in S4, which needed twelve raw partitions and a backlog to appear. The spec marks recovery as verified for the happy path only, and I have not tested a graceful SIGTERM or a second instance taking over.

**Example:** `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorRecoveryIT.java:112-114`
```java
            // One partition per topic: one task, so stream time is deterministic (ADR-0012).
            admin.createTopics(List.of(
                            new NewTopic(s.raw(), 1, (short) 1),
```
One partition per topic is what makes the test deterministic, and also what hides the cross-partition ordering problem.

**Code:** `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorRecoveryIT.java:270-300` (open window survives a crash), `specs/03-stream-processor/requirements.md:3` (happy path only)

### M6. Why does the accumulator keep an exact set of sessions, and what does that cost?

**Answer:** The concurrent-viewers metric is a distinct count of sessions, and the accumulator holds the exact set of session ids for the window, so the answer is exact. The price is that the accumulator is immutable, so each `add` copies the session set, which is O(sessions) per update, and the state store serialises the whole value per update anyway, so the copy does not change the order of growth. That cost is the reason a production system at larger scale would use an approximate sketch such as HyperLogLog, and I have not built that. A related point is that the key can be null for the title: `AggregateKey` treats a null title as its own group, so events without a title never inflate a real title's count. I have not measured how large a window's session set gets under load.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/domain/WindowAccumulator.java:27-29`
```java
    public WindowAccumulator add(Observation event) {
        Set<String> next = new HashSet<>(sessions);
        next.add(event.sessionId());
```
The copy on every event is visible in the first line of the body, and it is the cost the class comment names.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/domain/WindowAccumulator.java:8-16` (the trade-off in the class comment), `services/stream-processor/src/main/java/com/streampulse/processor/domain/AggregateKey.java:7-11` (a null title is its own group)

### M7. Why does readiness include the Streams state and liveness does not?

**Answer:** Readiness asks whether this instance can process records right now, and the Kafka Streams state is exactly the answer to that, so the readiness group includes `streamsState` next to `readinessState`. Liveness stays at `livenessState` only, because a stopped stream thread is cured by the broker coming back, not by restarting this process, and restarting every pod because the broker blipped would turn a blip into an outage. The health indicator counts a rebalancing state as up, because a rebalance is normal and short, and failing readiness during one would remove every pod at once during a rolling deploy. The indicator reports down for any state that is not running or rebalancing. I have not tested this under a real Kubernetes probe, so it is design reasoning backed by the indicator code.

**Example:** `services/stream-processor/src/main/resources/application.yml:44-49`
```yaml
        liveness:
          include: livenessState
        ...
        readiness:
          include: readinessState,streamsState
```
The difference between the two lines is the whole argument: liveness never depends on the broker, readiness does.

**Code:** `services/stream-processor/src/main/java/com/streampulse/processor/config/StreamsStateHealthIndicator.java:26-31` (running or rebalancing is up), `services/stream-processor/src/main/resources/application.yml:40-48` (the comments)

### M8. Why is the output record keyed `tenant:title:region:METRIC:windowStart`?

**Answer:** The key identifies one metric of one aggregate in one window, so a consumer can upsert by it and replaying the same final record is a no-op. The sink writer relies on that: it upserts on this key, which is how an at-least-once delivery becomes effectively once. The window start is in the key because the same aggregate produces a new record for every window, and the metric name is in the key because each window yields two records, concurrent viewers and error rate. A test pins the exact format. Each record also carries a final flag set to true, which is honest only because suppress guarantees that nothing is emitted before the window closes. The output topic has six partitions in the compose file, and I have not checked how those keys spread across them.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:150-151`
```java
            out.add(KeyValue.pair(key.asString() + ":" + value.metric().name() + ":" + start, aggregate));
```
The key is built from the aggregate key, the metric name and the window start in epoch milliseconds.

**Code:** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:178-190` (the key format is asserted), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:20-31` (the idempotent upsert that uses it)

---

## Senior

### S1. Where exactly does exactly-once end?

**Answer:** With `exactly_once_v2`, reading, processing and writing back to Kafka are one atomic step: the output records, the state-store changelog and the consumed offsets commit in a single transaction. It does not cover anything that leaves Kafka, so a consumer that reads the aggregate topic and writes to Postgres sees at-least-once delivery. In this system that sink is made safe by an idempotent upsert and by acknowledging offsets only after the database write, which is Phase 4. My own crash test showed a further limit: Kafka-level exactly-once held, with no duplicates, yet the aggregates were wrong, because the loss came from event-time logic and not from the transaction. The honest phrasing is that effectively once is at-least-once delivery plus idempotent processing. I run a single broker with replication factor 1, so none of this is verified under broker failure.

**Example:** `services/stream-processor/src/main/resources/application.yml:18-21`
```yaml
        # Output records, the state changelog and the consumed offsets commit in one
        # transaction. Kafka to Kafka only: anything that reads telemetry.agg.v1 and writes
        # elsewhere still sees at-least-once, so that consumer must be idempotent (ADR-0012).
        processing.guarantee: exactly_once_v2
```
The comment states the boundary: Kafka to Kafka only, and the consumer after it must be idempotent.

**Code:** `docs/adr/0012-kafka-streams-primary.md:30-37` (what exactly-once v2 does and does not cover), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:20-31` (the idempotent upsert that makes the sink safe)

### S2. How would you size grace?

**Answer:** The freshness floor is window plus grace, and in the full-pipeline runs the aggregates landed 5.2 to 5.4 seconds after the window end, on one laptop with one broker. I would size grace from the observed lateness distribution of real events, not by guessing, and from the longest outage I intend to survive, which is the lesson of S4. A bigger grace raises latency for every window permanently, because every final record waits that much longer, and it also grows the de-duplication state, since the dedup range is window plus grace. The alternative is to emit provisional results and let the sink's upsert overwrite them, which gives up the one-final-answer contract. I chose five seconds as an explainable target, and I have not derived it from a measured lateness distribution.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:56-58`
```java
        Duration dedupRange() {
            return windowSize.plus(grace);
        }
```
Grace appears twice in the design: once in the window definition and once here, so it sets both freshness and state size.

**Code:** `docs/interview/06-numbers.md:20` (the measured freshness row), `docs/interview/06-numbers.md:41` (the window and grace target), `docs/interview/05-what-broke.md` (story 3)

### S3. What is the quiet-stream limit and how do you handle it in production?

**Answer:** Windows close on stream time, which only advances when new events arrive, so a partition that goes quiet holds its last window open indefinitely. ADR-0012 states this as a consequence of using suppress. The options are a synthetic heartbeat per partition that keeps stream time moving, monitoring the age of the oldest open window, or accepting the limit and documenting it. I built none of them, and idle-partition heartbeats are on the not-built list. It showed up for real in my local runs: the load generator reported a freshness p99 of 39 and 113 seconds while Postgres said 5.3 seconds, because one run's last window was closed by the next run's first events. A warm-up does not fix that, since the warm-up leaves its own window open. The test suite pins the behaviour too, and a heartbeat design would need care not to push stream time past real windows.

**Example:** `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java:145-151`
```java
    void noOutputBeforeWindowCloses() {
        heartbeat("s-1", T0.plusSeconds(1));
        heartbeat("s-2", T0.plusSeconds(3));
        // 12:00:12 is in window 2 but stream time is still before 12:00:15.
        heartbeat("s-3", T0.plusSeconds(12));
        ...
        assertThat(aggregates.isEmpty()).isTrue();
```
Nothing is emitted until a record pushes stream time past the close, which is the limit in one test.

**Code:** `docs/adr/0012-kafka-streams-primary.md:39-47` (the quiet-partition consequence), `docs/interview/05-what-broke.md` (story 10, the metric that lied about freshness)

### S4. Tell me about killing the processor. What happened and what would you do?

**Answer:** I ran the accounting script with `kill -9` on the processor partway through the send phase and restarted it a few batches later. There were no duplicates, but the windows spanning the outage of about 40 to 50 seconds held only a fraction of their events: 1,482 of 4,000 missing in one run and 2,554 of 8,000 in a second. The count of `Skipping record for expired window` warnings equalled the missing events exactly and the task's dropped-records counter agreed. The loss was clustered, with 18 of 100 sessions appearing where random loss would touch about 79. My likely mechanism, consistent with the evidence but not proven, is that one aggregate's events come from twelve raw partitions, so working off the backlog feeds the repartition out of event-time order, stream time jumps past windows that still have on-time events, and the five-second grace treats them as late. I ruled out my own test, because the stream time at the drops was earlier than the closing event I sent. I have not fixed it: a larger grace, emitting revisions, or holding stream time back each change a documented guarantee and need an ADR, and I would add a late-drop metric because today it is silent. This was one crash type, one instance, one broker, on one laptop.

**Example:** `MILESTONES.md:1917-1921`
```text
2026-10-19T06:01:20.000Z        900      155     18/100     0.0774     FAIL
2026-10-19T06:01:30.000Z        900      163     18/100     0.1350     FAIL
...
closed windows checked: 6   events sent: 4000   events stored: 2518
```
Two windows hold 155 and 163 of 900 events, and 4,000 minus 2,518 is the 1,482 that matched the warning count.

**Code:** `MILESTONES.md:1903-1990` (Phase 9, T9-34, the full run and the honest gaps), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:104-110` (re-key then window: where order can break), `docs/interview/05-what-broke.md` (story 3)

**If they push:** "Would `max.task.idle.ms` fix it?" I have not verified whether it helps; my reading is that it concerns waiting across the input partitions of one task, not across upstream tasks writing into one repartition partition, so I would check the Kafka Streams docs before proposing it. "Why did recovery take that long?" The restarted JVM reported started in 1.2 seconds, but partitions were not assigned for roughly another 30 seconds while the dead member's session expired; I did not check the configured session timeout.

### S5. How would you prove no loss end to end?

**Answer:** I would assert a sent-versus-stored invariant for every closed window: the unique ids sent, with re-sent duplicates not double-counted, the event count, the distinct sessions and the error ratio, all compared with what is in Postgres. That is what `scripts/accounting-check.py` does, and on the happy path every closed window was exact at 2,000 and at 30,000 events. A check is only trustworthy if it can fail, so I made the script under-count one event in its own bookkeeping and it went red on exactly that window. That mutation tests the verdict logic, not loss detection end to end. The step that found the real defect was injecting faults during the run, because the happy-path run alone passed. The limits are that it covers closed windows only, one aggregate key, about 500 events per second, and no late events.

**Example:** `scripts/accounting-check.py:188-191`
```python
        ok = (viewers is not None and errors is not None
              and errors[1] == exp["events"] and viewers[1] == exp["events"]
              and int(viewers[0]) == len(exp["sessions"])
              and abs(errors[0] - exp["errors"] / exp["events"]) < 1e-9)
```
A window passes only if the stored event count, the stored distinct sessions and the error ratio all equal what was sent.

**Code:** `scripts/accounting-check.py:176-206` (the comparison and the verdict), `MILESTONES.md:1850-1890` (happy-path runs and the mutation check)

### S6. What is the repartition costing, and what would you change?

**Answer:** Keying by session upstream gives per-session ordering and cheap de-duplication, but the aggregation needs a different key, so every event is written to and read back from an internal repartition topic. That is one extra write and read, and the design records it as the deliberate price. The aggregation key is `tenant:title:region`, so one hot combination, such as one live match in one region, maps to a single task and a single accumulator. Salting that key is described in the deep-dive but not built, and a salt would need to be derived from the session so that a session counts once across salts. A possible redesign is to aggregate before re-keying, producing partial aggregates per partition, to cut repartition volume and reduce the out-of-order exposure from S4. That is a hypothesis I have not tested. I have also not confirmed the repartition topic's partition count and have it marked as something to verify.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/domain/AggregateKey.java:24-26`
```java
    public String asString() {
        return tenantId + ":" + (titleId == null ? "" : titleId) + ":" + region;
    }
```
Everything for one tenant, title and region shares this string, so it lands on one partition of the repartition topic.

**Code:** `docs/deep-dive.md:145-174` (ordering, the repartition cost, the unsalted hot key and the VERIFY), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:104-109` (the re-key and group)

---

## Say this honestly

- Single broker, replication factor 1; `acks=all` is checked as configuration only.
- No schema registry; the wire format is raw Avro binary.
- The grace and window are targets. The freshness *result* is measured.
- Not tested: graceful SIGTERM, a second instance taking over, a short outage.
- The repartition topic's partition count is unconfirmed (VERIFY).
- The suppression buffer is unbounded and its memory use is unmeasured.
- Whether `max.task.idle.ms` helps with the S4 loss is unverified.

## Stories and numbers

[What broke](../05-what-broke.md): stories 3, 10. [Numbers](../06-numbers.md): freshness, accounting and
crash rows.
