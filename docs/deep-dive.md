# Deep Dive: Real-Time Telemetry Pipeline (StreamPulse)

> Updated 2026-10-19 to match the code and MILESTONES.md after Phases 6-13 and the Phase 9 measurements.
> The newer, per-phase interview pack is in docs/interview/.

> Three reading depths per topic:
> - **Beginner** — an analogy, no jargon
> - **Senior** — mechanics, code shape, trade-off tables
> - **Architect** — failure modes, capacity arithmetic, what breaks at scale
>
> Every topic ends with **In this repo:** — the class and the tests, as repo-relative paths. Every path and test method named here was checked against the source.
>
> Rules this file follows:
> - **(not measured: ...)** marks a figure that was never measured. Never quote one. Figures without the tag come from the runs recorded in MILESTONES.md: Phase 3 Days 2-5 and Phase 9 (T9-11, T9-12, T9-13, T9-34). All measurements are from one laptop, one broker, with the load generator on the same machine.
> - **assumption** = arithmetic from the interview prompt (10 M viewers × 1 heartbeat / 10 s), not a measurement.
> - `VERIFY:` = I am not certain of a library default or JDK behaviour. Check the current docs before saying it.
> - "Question bank" = `/Users/aashishkumar/Downloads/Question bank.md`. IDs like M2, J5, SD3 come from it.
> - Where the code differs from the Question bank's idealised answer, there is a **Differs from the Question bank** note.

---

## Table of Contents

1. [M2 — No loss, no double count](#1-m2--no-loss-no-double-count)
2. [M3 — Ordering, partition keys, and the repartition cost](#2-m3--ordering-partition-keys-and-the-repartition-cost)
3. [Event time, windows, grace, suppress, and stalls on quiet partitions](#3-event-time-windows-grace-suppress-and-stalls-on-quiet-partitions)
4. [J5 — The batch writer](#4-j5--the-batch-writer)
5. [J6 — Fan-out under a deadline with CompletableFuture](#5-j6--fan-out-under-a-deadline-with-completablefuture)
6. [J2 — MVC + virtual threads vs WebFlux](#6-j2--mvc--virtual-threads-vs-webflux)
7. [SD3 — SSE, Last-Event-ID, and coalescing](#7-sd3--sse-last-event-id-and-coalescing)
8. [M4 — Consumer lag diagnosis](#8-m4--consumer-lag-diagnosis)
9. [M5 — Dual write and the outbox](#9-m5--dual-write-and-the-outbox)
10. [R1 — Timeout, retry, breaker, bulkhead](#10-r1--timeout-retry-breaker-bulkhead)
11. [R2 — SLIs, SLOs, and burn rate](#11-r2--slis-slos-and-burn-rate)
12. [R4 — Graceful shutdown and zero-loss deploys](#12-r4--graceful-shutdown-and-zero-loss-deploys)
13. [Avro `Utf8` vs `String`](#13-avro-utf8-vs-string)
14. [Testing strategy: Gradle, Testcontainers, TopologyTestDriver, mutation](#14-testing-strategy-gradle-testcontainers-topologytestdriver-mutation)
15. [Interview signal checklist](#interview-signal-checklist)
16. [Anti-patterns](#anti-patterns)
17. [Where this repo differs from the Question bank](#where-this-repo-differs-from-the-question-bank)
18. [Honest gaps](#honest-gaps)
19. [Quick recall cheat sheet](#quick-recall-cheat-sheet)

---

## The pipeline in one picture

```
client ──REST/gRPC──▶ ingest-gateway ──▶ Kafka telemetry.raw.v1 (12p, key=sessionId, ts=occurredAt)
                      (MVC + virtual     │
                       threads)          ▼
                                  stream-processor (Kafka Streams, exactly_once_v2)
                                  decode → validate → dedup(eventId) → re-key(tenant:title:region)
                                  → 10 s window, 5 s grace → suppress → 2 metrics per window
                                         │
                                         ▼
                                  Kafka telemetry.agg.v1 (6p)   (+ .dlt topics)
                                         │  at-least-once from here on
                                         ▼
                                  sink-writer: BatchWriter → Postgres (idempotent upsert) → Redis → ack offset
                                         │
                      Redis: hash sp:latest:{tenant} + stream sp:agg:live (MAXLEN ~10000)
                                         │
                                         ▼
                                  query-api (WebFlux): overview (300 ms budget) · history · SSE
```

Source of truth for shapes: `specs/04-sink-writer/hot-store-contract.md`. Flow and failure tables: `specs/03-stream-processor/design.md`, `specs/04-sink-writer/design.md`, `specs/05-query-api/design.md`.

---

## 1. M2 — No loss, no double count

### Beginner — Posting a parcel

You post a parcel and the tracking site says "not sure it arrived". You post a second one. Now the shop may have two. A sensible shop writes the **order number** on both and keeps only one. That is idempotence: doing it twice has the same effect as doing it once.

"No loss" means you keep re-posting until you get a receipt. "No double count" means the shop ignores a repeated order number. You need **both**, because retrying (to avoid loss) is what creates duplicates.

### Senior — Where each half lives

| Hop | What can go wrong | Mechanism | Code |
|---|---|---|---|
| Client → gateway | Response lost, client resends | `eventId` claimed with Redis `SET NX EX` (atomic) | `RedisIdempotencyStore` |
| Gateway → Kafka | Producer retry duplicates or reorders | `enable.idempotence=true`, `acks=all` | ingest `application.yml` |
| Gateway: claim then publish fails | Ids stay claimed; retry looks like a duplicate; **silent loss** | Compensating `release()` | `IngestService` |
| Redis down | Cannot dedup | **Fail open**: publish, count, return `degraded: true` | ADR-0007 |
| Kafka → Kafka (processor) | Crash mid-processing | `exactly_once_v2`: output + changelog + offsets in one transaction. After `kill -9` it held (no duplicates) but on-time events were dropped as expired-window records (see the gaps below; MILESTONES.md, Phase 9 · T9-34) | stream-processor `application.yml` |
| Kafka → Postgres | At-least-once redelivery | Idempotent upsert, guarded by `computed_at` | `JdbcMetricWindowStore` |
| Offsets | Commit before write → loss | Manual ack **after** the durable write | `AggregateBatchListener` |

The sentence to say: **"effectively-once = at-least-once + idempotent processing."**

The upsert:

```sql
INSERT INTO metric_window (tenant_id, title_id, region, metric, window_start, ...)
VALUES (...)
ON CONFLICT (tenant_id, title_id, region, metric, window_start) DO UPDATE SET ...
WHERE metric_window.computed_at <= EXCLUDED.computed_at
```

- The primary key is the aggregate's identity, so a replay hits the same row.
- `WHERE ... computed_at <=` means an **older** computation never overwrites a **newer** one.
- `<=`, not `<`: a replay of the very same record must be a no-op in effect, not refused.

The ack rule: the listener decodes, submits every record to the batch writer, **waits for all futures**, then acknowledges. Throwing instead of acknowledging makes the container redeliver the whole poll. The Kafka consumer is not thread-safe, so the **flusher thread completes futures and the listener thread acks**.

### Architect — What exactly-once v2 does not cover, and what the tests do not prove

**Scope of `exactly_once_v2`.** It makes read → process → write-to-Kafka atomic: the output records, the state-store changelog and the consumer offsets commit in one transaction. It does **not** cover anything that leaves Kafka. Postgres and Redis are not Kafka resources. Nor did it make the windowed aggregates correct after a restart with a backlog, even inside Kafka: in T9-34 no record was duplicated, yet on-time events were dropped (see the gaps below). The honest claim is at-least-once delivery plus idempotent writes (ADR-0012, ADR-0013).

`read_committed` on the sink consumer matters: it skips records from aborted producer transactions. The comment on the consumer config says so (`services/sink-writer/src/main/resources/application.yml`).

**Why Postgres first, Redis second.** A reader must never see a value in Redis that Postgres never stored. Redis failure is logged, counted (`streampulse.sink.projection.failures`) and swallowed; the ack still happens (REQ-SK-07). Cost: the Redis live feed can repeat a record after a redelivery, and the `latest` hash can briefly hold an older window after a redelivery across partitions (`specs/04-sink-writer/design.md`, Known gaps).

**Poison versus outage.** A record that fails every time is **poison**: dead-letter the original bytes and key, carry on. A store that is down fails **every** record: retry forever with exponential backoff, never dead-letter. Telling them apart: after three failed batch attempts, write records one at a time; if some succeed, the failures are poison; if none do, it is an outage. Known gap: a poison record alone in its batch looks like an outage. The decoder rejects the common deterministic causes first (blank tenant, unknown metric, inverted window) to keep that rare.

**Gaps the claim does not cover (say them):**

- `NoLossNoDoubleCountIT` injects an **exception**, not a killed JVM, and not a broker or Postgres failing mid-write. The upsert is what makes a real crash safe; the test exercises it through redelivery.
- Single broker, replication factor 1. `KafkaIngestIT.producerDeliveryGuarantees` asserts the **configuration** (`enable.idempotence`, `acks=all`), not behaviour when a replica is lost (`MILESTONES.md`, Phase 3 Day 1 "Honest gaps").
- Redis claim at ingest is not exactly-once either: a timeout may fire after the write landed, the claim is released, the retry publishes again. A duplicate, accepted by design; the processor dedups within window + grace.
- Nothing rebuilds the Redis projection yet.
- **Processor crash loses on-time events (measured, T9-34; MILESTONES.md "Phase 9 · T9-34", one laptop, one broker, one instance, `kill -9` of the processor).** No duplicates, but events were **dropped** as `Skipping record for expired window`: 1,482 of 4,000 and 2,554 of 8,000, each equal to the processor's own WARN count. The mechanism is **likely** multi-partition backlog catch-up advancing stream time past the 5 s grace; **not proven**. **Not fixed.** It is **invisible to `streampulse_processor_*` metrics** (only the Kafka Streams dropped-records counter and the WARN log show it). Fix options (a larger grace, emitting revisions, draining the backlog first) each change a documented guarantee and need an ADR. Not tried: graceful SIGTERM, a short outage, a second instance taking over.
- **Differs from the Question bank:** the bank says "upsert by eventId, or a dedupe store with TTL". Here the sink upserts by **aggregate identity** (not by event id), and event-id dedup lives in two places: Redis at ingest (24 h TTL) and a window store in the processor.

**In this repo:**
- `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`, `.../adapter/out/redis/RedisIdempotencyStore.java`, `.../adapter/out/kafka/KafkaEventPublisher.java`
- `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java`, `.../application/AggregateSink.java`, `.../adapter/out/postgres/JdbcMetricWindowStore.java`, `.../config/SinkConfig.java`
- Tests:
  - `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/NoLossNoDoubleCountIT.java` — `failureBetweenWriteAndAckLosesNothingAndDoubleCountsNothing`
  - `services/sink-writer/src/test/java/com/streampulse/sink/adapter/out/postgres/MetricWindowUpsertIT.java` — `sameRecordTwiceGivesOneRow`, `olderComputedAtDoesNotOverwriteNewer`, `newerComputedAtOverwritesOlder`, `sameKeyTwiceInOneBatchKeepsTheNewer`
  - `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListenerTest.java` — `acksOnlyAfterEveryRecordIsWritten`, `storeFailureMeansNoAck`, `preAckFailureMeansNoAck`, `budgetExceededMeansNoAck`, `deadLetterFailureMeansNoAck`
  - `services/sink-writer/src/test/java/com/streampulse/sink/config/SinkWiringTest.java` — `consumerSettings` (auto-commit off, `read_committed`, cooperative assignor, group id not overridden)
  - `services/sink-writer/src/test/java/com/streampulse/sink/config/RetryPolicyTest.java` — `backoffNeverGivesUp`
  - `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/PoisonRecordIT.java` — `garbageIsDeadLetteredAndHealthyRecordsAreStored`
  - `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java` (nested) — `aFailedPublishCanBeRetried`, `withoutTheReleaseTheRetryIsSwallowed`, `concurrentDuplicateBatchesPublishOnce`, `publishesAnywayAndReportsDegraded`, `failClosedRefuses`
  - `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/KafkaIngestIT.java` — `retriedBatchPublishesOnce`, `producerDeliveryGuarantees`
  - `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorIT.java` — `endToEnd` (real broker, `exactly_once_v2`; does not kill the processor)
- ADRs: `docs/adr/0007-fail-open-on-idempotency-store-failure.md`, `docs/adr/0012-kafka-streams-primary.md`, `docs/adr/0013-sink-durable-then-commit.md`

---

## 2. M3 — Ordering, partition keys, and the repartition cost

### Beginner — Queues at a supermarket

If every customer of one family always goes to the same till, their items scan in the order they put them down. If they hop between tills, the order is lost. **A partition is a till.** The **key** decides which till. Key by `sessionId` and one viewing session always uses one till.

### Senior — The key table

| Topic / store | Key | Why |
|---|---|---|
| `telemetry.raw.v1` | `sessionId` | per-session order; high cardinality spreads evenly |
| dedup store | `eventId`, inside the session's partition | a duplicate is in the same partition as its original |
| window state | `tenant:title:region` | the aggregation unit — **forces a repartition** |
| `telemetry.agg.v1` | `tenant:title:region:metric:windowStart` | the consumer upserts by key |

Rules:

- Order holds **only within a partition**. Global order is almost never needed — say so.
- The idempotent producer prevents reordering on retry. The configured `max.in.flight.requests.per.connection` is 5. **VERIFY:** the exact limit under idempotence in current Kafka docs (the repo's own config comment says the same).
- Alternatives and what they cost: `key = tenantId` makes a few hot partitions; `key = null` is round-robin, so no order at all (`specs/01-contracts/design.md`).
- **Dedup runs before the repartition**, while the key is still the session. After the repartition the keys differ and a duplicate could land on another task.

### Architect — What the key choice costs, and what breaks

- **Repartition cost.** Re-keying to `tenant:title:region` makes Kafka Streams write and read a repartition topic: extra network and disk. `specs/03-stream-processor/design.md` records this as the deliberate price of ordering by session upstream.
- **Adding partitions breaks order.** `hash(key) mod partitions` changes. Pick the count high up front (12 raw, 6 aggregate) and treat changing it as a breaking change.
- **Partition count is the consumer ceiling.** Beyond it, extra consumers idle (see M4).
- **Hot key — not solved here.** `tenant:title:region` is one key. One live match in one region is one stream task, and one `WindowAccumulator`. The Question bank says to salt the key for a hot partition. **This repo does not.** The fix: salt by a hash of `sessionId`, aggregate partials in parallel, merge in a second stage. Distinct counts add across salts only when each session maps to exactly one salt.
- **Honest note.** The two computed metrics (distinct sessions, error rate) do **not** depend on per-session order; they depend on event time. Order would matter for session metrics such as rebuffer ratio or startup time, which exist in `libs/contracts/src/main/avro/MetricAggregate.avsc` but are not computed.
- **VERIFY:** the partition count of the Kafka Streams repartition topic (I believe it follows the upstream topic's partition count). Check the Kafka Streams docs before stating it.

**In this repo:**
- `services/ingest-gateway/src/main/java/com/streampulse/ingest/domain/IngestCommand.java` (`partitionKey()`), `.../adapter/out/kafka/KafkaEventPublisher.java`
- `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`, `.../EventIdDeduplicator.java`, `.../domain/AggregateKey.java`
- `docker/compose.yml` — topic creation in `kafka-init` (five topics: 12 / 6 / 3 / 3 partitions, plus `config.changes.v1`: 3 partitions, compacted)
- Tests: `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/KafkaIngestIT.java` — `sameSessionSamePartition`, `batchReachesKafkaWithTheRightKey`; `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java` (nested `HappyPath`) — `partitionKeyIsSessionId`; `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java` — `recordKeyIdentifiesTheWindowAndMetric`, `regionsAreSeparate`, `duplicateEventIdCountedOnce`; `services/stream-processor/src/test/java/com/streampulse/processor/domain/AggregateKeyTest.java` — `deterministic`, `missingTitle`, `tenantNormalised`

---

## 3. Event time, windows, grace, suppress, and stalls on quiet partitions

### Beginner — Counting people through a turnstile

Imagine counting people who passed a turnstile between 12:00:00 and 12:00:10 **by the time on their ticket**, not by when you happen to read the ticket. Someone's ticket is stamped 12:00:07 but reaches you at 12:00:12. **Event time** is the stamp. **Processing time** is when you read it. A **grace period** is how long you keep the 12:00:00 tally open for late tickets. **Suppress** means: do not announce the tally until it is final.

### Senior — The mechanics

- **Event time for free.** `KafkaEventPublisher` sets the record timestamp to the event's `occurredAt`. Kafka Streams windows on the record timestamp by default, so no custom extractor and no second decode. This needs the topic's `message.timestamp.type=CreateTime` (the default).
- **Windows.** `TimeWindows.ofSizeAndGrace(10 s, 5 s)`. Window 1 = [12:00:00, 12:00:10); it closes when **stream time** reaches 12:00:15.
- **Stream time** = the largest record timestamp seen so far, **per task (partition)**. It only moves forward when a later record arrives.
- **Late within grace** → counted in its own window. **After grace** → dropped, and the drop is counted (Kafka Streams' own dropped-records metric; `TelemetryTopologyTest.lateAfterGraceIsDroppedNotEmitted` asserts it is greater than zero).
- **Suppress.** `suppress(untilWindowCloses(unbounded()))` emits **one final record per window**, only after window + grace. Without it every late event re-emits the window and downstream sees revisions.
- **Output.** `MetricMath.compute` yields `CONCURRENT_VIEWERS` (distinct session ids) and `ERROR_RATE` (errors ÷ events), each with `sampleCount`. `computedAt` is the processor clock at emission.
- **Dedup retention.** The window store keeps event ids for `2 × (window + grace)` = 30 s, because a duplicate arriving after grace would be dropped by the window anyway.
- **Validator guard.** An event more than 5 minutes in the future (`max-future-skew`) goes to the dead-letter topic: a device with a wrong clock would otherwise advance stream time past every real window and make them all "late". An event from the **past** is valid; lateness is the window's job.

### Architect — The freshness cost, the stall, and what scales badly

**The cost, said as a trade.** A final aggregate cannot appear sooner than window + grace = **15 s after the window starts** (ADR-0012). That is what correctness for late events costs. Against a 30 s freshness target (an assumption you should confirm) that leaves about 15 s for the rest of the pipeline. If the target were 5 s: emit provisional values (`isFinal=false`) and let the sink's `computed_at` upsert replace them. The upsert is already built for that.

**What the freshness metric measures.** `streampulse.sink.freshness` = `now − windowEnd` at the durable write. It starts at window **end**, not at event time. True event-to-visible freshness is that number plus up to 10 s of window. p99 5.71 s from the histogram estimate (Phase 3); all 9 closed windows landed 5.2 to 5.4 s after their end in Postgres, and grace is 5 s. Phase 9 · T9-13 (one laptop, one broker, 1,000 and 2,000 events/s, 5 to 6 windows per run) measured per-window freshness 5.2 to 5.4 s again. The loadgen's own freshness p99 is unreliable for back-to-back runs (it reported 39.33 / 112.93 / 39.63 s because the previous run's open window closes late inside it): quote Postgres per window, not the loadgen histogram.

**The stall.** `suppress` emits only when stream time advances. A **quiet partition holds its last window open indefinitely**. At the prompt's 1 M events/s this does not happen; on an idle title or an under-keyed topic it will. `TelemetryTopologyTest.noOutputBeforeWindowCloses` proves nothing is emitted early, and most other tests pipe a "tick" record (`tickPastWindowOne`) to close the window. `StreamProcessorIT` uses **one partition per topic** so stream time is deterministic. Mitigations (design options, not built): steady traffic, a synthetic heartbeat per partition, or monitoring the age of the oldest open window. **VERIFY** any idle-partition behaviour of your Kafka Streams version before promising it.

**Memory.** The suppress buffer is `unbounded()`. My reading: it holds one entry per windowed key until the window closes, so it is bounded by keys × open windows, not by events. **VERIFY:** against the Kafka Streams docs for `Suppressed.BufferConfig`.

**What scales badly (arithmetic from the assumption, not measurements):**

- `WindowAccumulator.add` copies the session-id set on every event: cost is proportional to distinct sessions in the window. It is **exact**, and the class comment names HyperLogLog as the production answer. One hot title at scale also means one key and one task.
- Dedup state at 1 M events/s × 30 s ≈ **30 M ids**.
- `EventIdDeduplicator` is a persistent window store with a changelog topic; its size is the biggest state in the system at 10x.

**In this repo:**
- `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`, `.../EventIdDeduplicator.java`, `.../domain/EventValidator.java`, `.../domain/WindowAccumulator.java`, `.../domain/MetricMath.java`, `services/stream-processor/src/main/resources/application.yml`
- Tests in `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java`: `noOutputBeforeWindowCloses`, `emitsOneFinalAggregatePerMetricAfterGrace`, `outOfOrderWithinGraceIsCounted`, `lateAfterGraceIsDroppedNotEmitted`, `futureEventIsRejected`, `invalidEventGoesToDlt`, `undecodableBytesGoToDltWithReasonHeader`, `unknownTypeCountsTowardTotalsOnly`
- Also: `services/stream-processor/src/test/java/com/streampulse/processor/domain/EventValidatorTest.java` — `oldEventIsValid`, `futureSkewBoundary`; `.../domain/WindowAccumulatorTest.java` — `counts`, `addIsImmutable`; `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorIT.java` — `endToEnd`
- ADR: `docs/adr/0012-kafka-streams-primary.md`; `specs/03-stream-processor/design.md`

---

## 4. J5 — The batch writer

### Beginner — The lift and the rope

A lift does not leave with one person; it waits until it is full **or** a few seconds pass. That is **batching**. It has a limited number of seats: when full, people at the door are told "next one". That is **backpressure**. At closing time it empties completely before the doors lock.

### Senior — The design

```
submit() ──▶ bounded queue (2000) ──▶ ONE "batch-flusher" thread ──▶ sink.write(batch)
   │                                        │
   └─ future completes after ◀──────────────┘   on failure: retry ×3 with backoff, then ONE AT A TIME
```

- **Bounded queue**: `ArrayBlockingQueue`, capacity 2000. Unbounded queues relocate failure to the heap.
- **Flush** at 200 records, or 100 ms after the **first** record, whichever comes first. `drainTo` grabs what is already queued.
- **Backpressure**: `submit` uses `offer` with a **1 s timeout**. A full buffer returns a failed future (`BUFFER_FULL`); it never blocks forever and never drops silently.
- **Each submit returns a `CompletableFuture<Void>`** that completes only when the record's batch is durably written, or fails.
- **Retry**: 3 attempts with exponential backoff (50 ms base). Still failing → **isolate**: write one record at a time. Some succeed → the failures are poison → dead-letter. None succeed → outage → fail the futures; nothing is dead-lettered.
- **Shutdown order**: stop the Kafka listener first, then `BatchWriterLifecycle.stop()` → `writer.close()`: stop intake, flush what is accepted, join the flusher (bounded by `close-timeout`, 20 s; interrupt if it overruns).
- **A static `start` factory**, not a constructor that starts a thread: no `this` escape.
- **Deadlines use `System.nanoTime`**, not the injected `Clock`: wall time can jump.
- **Framework-free**: no Kafka, Spring or JDBC type in `application/batch`, enforced by `SinkArchitectureRulesTest.batchWriterIsFrameworkFree`.

**Differs from the Question bank's sketch:** the bank's J5 code uses a `volatile boolean open` and names the open-check/close race as "fix with a poison pill or a lock". This repo uses the **lock** (`ReentrantReadWriteLock`), returns per-record **futures**, and adds retry + isolate + dead-letter. The bank's sketch has retry/DLQ as "missing"; here it is built.

### Architect — The race, the lock, and a test that lied

**The race.** Without coordination:

1. `submit` reads `closed == false`.
2. `close()` sets `closed = true`.
3. The flusher polls, sees an empty queue and `closed`, and **exits**.
4. `submit` enqueues. The record is **accepted, and nothing will ever write it.**

**The fix.** `submit` takes the **read** lock around "check closed + offer". `close()` takes the **write** lock only to set `closed`. Many submitters hold the read lock at once; `close()` waits for those in flight. The flusher then only exits on `closed && queue.isEmpty()`.

**Cost of the fix.** `submit` holds the read lock while blocked in `offer` (up to the 1 s timeout), so `close()` can wait about that long. Accepted.

**Why not `synchronized`.**
- It serialises all submitters on one monitor. With a read lock they proceed in parallel.
- On Java 21, a virtual thread that blocks inside `synchronized` pins its carrier thread. `submit` blocks in `offer`. **VERIFY:** JEP 491 is reported to remove this pinning in a later JDK — check the JDK you ship before relying on either the problem or the fix (ADR-0006 says the same).
- **Honest note:** in this repo `submit` is called from Spring Kafka listener threads, which I believe are platform threads (the sink-writer's `application.yml` does not enable virtual threads). So the "pinning" reason is defensive; the "do not serialise submitters" reason holds regardless.

**The honest finding: a randomised test gave false confidence.**

There are two tests of the race:

- `acceptedImpliesWrittenUnderConcurrentSubmitAndClose` — `@RepeatedTest(100)`; 48 threads × 40 submits while a closer thread closes after a random spin.
- `closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck` — deterministic. It hands the writer a package-private `ArrayBlockingQueue` subclass whose `offer` stalls on a latch, so a submit is held **between the open-check and the enqueue**, then calls `close()` and asserts `close()` is still **waiting** and that the record is eventually written.

When the lock was removed in a mutation check, the **randomised test did not go red; the deterministic one did.** `specs/04-sink-writer/design.md` marks the deterministic test as "the one that fails without the lock".

Why a randomised concurrency test lies:

- The dangerous window is nanoseconds wide. The scheduler rarely puts `close()` inside it. 100 green runs show the interleaving was **not hit**, not that it **cannot happen**.
- "Absence of failure" ≠ "absence of bug". The test's assertions are right; its **coverage of interleavings** is the weakness.
- To test a race, **control the interleaving**: inject a collaborator you own (here the queue) and stall it at the exact point. That is why `start(...)` has a package-private overload taking a queue.
- Then mutation-test it: remove the lock and confirm **that** test fails.

`specs/04-sink-writer/tasks.md` T4-03 records the same result: with the lock removed the randomised test stayed green and only the deterministic test went red.

**Measured behaviour:** (not measured: batch flush latency p99 and peak queue depth under the load run)

**In this repo:**
- `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java`, `.../batch/BatchSink.java`, `.../batch/DeadLetterSink.java`, `.../lifecycle/BatchWriterLifecycle.java`, `.../config/SinkConfig.java`, `services/sink-writer/src/main/resources/application.yml` (200 / 100 ms / 2000 / 3 attempts / 1 s offer)
- Tests in `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java`: `flushesWhenBatchSizeIsReached`, `flushesAfterMaxWaitWhenBatchIsNotFull`, `fullQueueRejectsWithinTheOfferTimeout`, `closeDrainsEverythingAccepted`, `closeFlushesAPartialBatchWithoutWaitingForMaxWait`, `submitAfterCloseFails`, `acceptedImpliesWrittenUnderConcurrentSubmitAndClose`, `closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck`, `transientFailureIsRetriedThenSucceeds`, `poisonRecordIsIsolatedToTheDeadLetterSinkWhileOthersAreWritten`, `outageFailsFuturesWithoutDeadLettering`, `deadLetterSinkFailureFailsOnlyThatRecord`, `metricsReportQueueDepthAndFlushLatency`
- `services/sink-writer/src/test/java/com/streampulse/sink/lifecycle/BatchWriterLifecycleTest.java` — `lifecycleStopsAfterTheListenerContainer` (uses a stand-in container with the real phase constant, not the real container), `phaseIsBelowTheContainers`, `stopInvokesCallback`
- `services/sink-writer/src/test/java/com/streampulse/sink/SinkArchitectureRulesTest.java` — `batchWriterIsFrameworkFree`
- ADR/spec: `docs/adr/0013-sink-durable-then-commit.md`; `specs/04-sink-writer/design.md` (BatchWriter concurrency design)

---

## 5. J6 — Fan-out under a deadline with CompletableFuture

### Beginner — Ordering three dishes for one table

Three dishes come from three kitchens. You tell the table: "everything arrives within 5 minutes." You ask all three kitchens at once, not one after another. If one is late, you serve the other two and say "the dessert is delayed" rather than leave the table empty.

### Senior — The shape

```java
Instant deadline = clock.instant().plus(budget);          // computed ONCE per request
supplyAsync(call, blockingExecutor)                         // dedicated, bounded pool — never the common pool
    .orTimeout(remainingNanos, NANOSECONDS)                 // remaining = deadline - now
    .thenApply(v -> new Part<>(v, false))
    .exceptionally(f -> new Part<>(fallback, true));        // degraded, not failed
CompletableFuture.allOf(latest, history, metadata)          // join
```

- **One** budget for the whole request (300 ms target), measured from arrival, not per call.
- A failed or slow part becomes an empty value named in `degraded`. Even if all three fail the answer is **200** (REQ-QA-04).
- `supplyAsync` on a rejecting executor throws `RejectedExecutionException`; the code catches it and degrades the part **at once**.
- The executor is `BoundedExecutor`: fixed size (8), bounded queue (16), `AbortPolicy`, named threads `blocking-N`.
- Never the common `ForkJoinPool`: it is shared process-wide, sized by CPU count, and one blocking call can starve unrelated work.

### Architect — The trap and the bounds

**`orTimeout` completes the future; it does not cancel the running call.** The slow call keeps its worker until it returns. An unbounded pool would pile up abandoned calls behind a slow dependency, every timed-out request leaving a zombie. Bounded size + bounded queue + rejection turns "slow dependency" into "this part degrades immediately", not "the service leaks".

Consequences you should be able to state:

- The remaining budget is computed **after** `supplyAsync` returns, so queue wait inside the executor counts against the 300 ms.
- A JDBC query timeout (`jdbc.setQueryTimeout`) is set to bound a stuck query, because `orTimeout` cannot cancel it. Whole seconds only — the JDBC API takes no finer unit (comment in `QueryConfig`).
- Hikari pool = 8 = executor size, so the **executor, not the pool, is the limit**. Hikari's `connection-timeout` is 250 ms (the comment in the file says that is Hikari's minimum), because waiting longer than the 300 ms budget for a connection is pointless.
- One of the three ports (`RedisLatestMetrics`) uses the reactive Redis client and `block()`s with a timeout **on the executor thread**. That is legal there; it would not be on an event loop. This keeps one executor, one deadline and one fallback rule for all three parts.

**Structured concurrency** (`StructuredTaskScope`) is the cleaner modern shape: scope-bound lifetime, cancellation of siblings on failure. **VERIFY:** it is a preview API in Java 21 — check its status on the JDK you target.

**Differs from the Question bank:** the bank's J6 says "supplyAsync → orTimeout → exceptionally → allOf". This is exactly that, plus the bounded rejecting executor and the shared-deadline arithmetic. (not measured: overview p99 latency and degraded-part rate under the load run)

**In this repo:**
- `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java`, `.../application/BoundedExecutor.java`, `services/query-api/src/main/java/com/streampulse/query/config/QueryConfig.java`, `services/query-api/src/main/resources/application.yml`
- Tests in `services/query-api/src/test/java/com/streampulse/query/application/TitleOverviewServiceTest.java`: `slowCallIsDegradedAndTheBudgetHolds`, `callsRunInParallel`, `allCallsFailingStillAnswers`, `workNeverRunsOnTheCommonPool`, `saturatedExecutorDegradesInsteadOfBlocking`
- `services/query-api/src/test/java/com/streampulse/query/adapter/in/web/QueryControllersTest.java` — `overviewAnswers`
- Spec says the mutation "remove `orTimeout`, `slowCallIsDegradedAndTheBudgetHolds` goes red" was done (`specs/05-query-api/tasks.md` T5-04). `MILESTONES.md` (Phase 3 Days 3-4) records the mutation as an agent report, so treat it as agent-reported.
- ADR: `docs/adr/0014-webflux-sse-redis-streams.md` (The J6 shape)

---

## 6. J2 — MVC + virtual threads vs WebFlux

### Beginner — One waiter per table vs one waiter for the whole room

**Thread-per-request** is one waiter per table: easy to follow, expensive if there are 10,000 tables. **Virtual threads** make waiters nearly free, so you keep the easy model. **WebFlux** is one very fast waiter who never stands still: wonderful, but if he stops to wait on the kitchen even once, every table stalls.

### Senior — The comparison, and the rule each side imposes

| | MVC + virtual threads | WebFlux / Reactor |
|---|---|---|
| Code | ordinary blocking code | `Mono` / `Flux` chains |
| Blocking call in the wrong place | fine: the virtual thread unmounts | **stalls an event-loop thread** |
| Backpressure | you build it (here: a bounded semaphore) | built into `request(n)` |
| Streaming operators (merge, replay, buffer) | hand-written | built in |
| Debugging | ordinary stack traces | assembled traces |
| Libraries | any, including blocking JDBC | the whole chain must be non-blocking |

**The split in this repo (the interview answer):**

- **Ingest** = I/O-bound, every dependency blocking (Kafka producer, Redis client), no streaming requirement → **MVC on virtual threads** (`spring.threads.virtual.enabled: true`). Backpressure is a fair `Semaphore(64)` with a 250 ms wait → 503.
- **Query API** = long-lived streams with demand control → **WebFlux**. `QueryApiWiringTest.runsOnWebFlux` asserts `spring-boot-starter-web` has not crept in.

**The rule WebFlux imposes: never block an event-loop thread.** Every blocking port (JDBC, the catalog stub, the Redis latest read) is invoked from one bounded, named, rejecting executor (`blocking-N`). `BlockingIsolationTest` records the thread name of each request and each port call and asserts: requests ran on `http-nio` threads, ports ran on `blocking-` threads, none on an event loop.

**Netty thread-naming gotcha.** The server's event-loop threads are named **`webflux-http-nio-N`** (Spring's `ReactorResourceFactory`), not **`reactor-http-nio-N`** (Reactor Netty's own default). A test that matches only `reactor-http-nio` would pass vacuously. The test matches on `http-nio`, which covers both. (Javadoc on `BlockingIsolationTest`.)

### Architect — What each choice costs

- Virtual threads: the semaphore only bounds what you remembered to bound; nothing stops you creating a million virtual threads (ADR-0006).
- A virtual thread blocked inside `synchronized` pins its carrier on Java 21. The ADR prefers `ReentrantLock` around blocking calls. **VERIFY:** whether your JDK still pins (JEP 491 is reported to remove it).
- WebFlux: one accidental `block()` in a library breaks it; JDBC needs an executor, which is a thread pool, which reintroduces a limit. The design accepts that and sizes the pool to the executor.
- **Two concurrency models in one repo** is a real cognitive cost. Justified by different workloads, said out loud.
- **The weak answer is "virtual threads are the new way" or "WebFlux is faster".** The strong answer names the workload and the rule.
- ADR-0006 promised a benchmark of both under the same load in Phase 5/9. **I have no measured comparison in this repo.** Do not quote one. (not measured: concurrent SSE streams per query-api instance and CPU/heap at that count)

**In this repo:**
- `services/ingest-gateway/src/main/resources/application.yml` (`spring.threads.virtual.enabled`), `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java` (semaphore), `services/query-api/src/main/java/com/streampulse/query/application/BoundedExecutor.java`
- Tests: `services/query-api/src/test/java/com/streampulse/query/config/BlockingIsolationTest.java` — `noBlockingPortRunsOnAnEventLoopThread`; `services/query-api/src/test/java/com/streampulse/query/config/QueryApiWiringTest.java` — `runsOnWebFlux`, `compositionRootWiresTheCore`, `probesDependOnNoDataStore`; `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java` (nested `Backpressure`) — `rejectsWhenSaturated`, `permitIsReleasedOnFailure`
- ADRs: `docs/adr/0006-virtual-threads-over-webflux.md`, `docs/adr/0014-webflux-sse-redis-streams.md`

---

## 7. SD3 — SSE, Last-Event-ID, and coalescing

### Beginner — A noticeboard that announces changes

A radio station broadcasts one way; you do not talk back. If you lose the signal in a tunnel, you tell the station "the last thing I heard was item 42" and it plays you 43 onward before going live. A slow listener would otherwise get a growing pile of unheard items; instead the station says only "here is the latest score".

### Senior — Why SSE, and how replay-then-live works

**SSE over WebSocket:** one-way, plain HTTP, browser auto-reconnect, `Last-Event-ID` resume built in. WebSocket adds two-way you do not need and a custom reconnect protocol. (The Question bank's trap: "WebSocket by default with no reason.")

The pieces:

| Piece | What it does | Class |
|---|---|---|
| One shared reader per instance | One Redis Stream reader fans out via a hot multicast sink; cost scales with instances, not clients | `RedisLiveFeed` |
| SSE `id:` = stream entry id | Totally ordered; doubles as the resume point | `EntryId`, `SseStreamController` |
| Replay-then-live | **Subscribe to live first** (buffered), then read the missed range, drop live entries ≤ last replayed id | `SseStreamController.resume` |
| Coalescing | Every 250 ms emit the **latest** entry per series; slow clients overwrite, never queue | `Coalescer` |
| Heartbeat | An SSE comment every 15 s while idle, so proxies do not close the stream | `Heartbeat` |

Why replay-then-live has no gap and no duplicate:

- **No gap:** the live feed is subscribed **before** the range read starts, so anything published during the read is held in a 1024-entry replay buffer.
- **No duplicate:** live entries with `id <= lastSent` are dropped, so nothing the read already returned is sent twice.
- **Ids compare numerically** (`EntryId`): as strings `9-0` sorts after `10-0`.

Other details:

- The feed reads from an **explicit cursor**, not `$`. `$` means "newer than whenever the server handles this read"; an entry added between two reads could be skipped. A restart or re-subscribe continues from the last id seen.
- Malformed `Last-Event-ID` → 400 problem+json. A poison stream entry is skipped, never allowed to kill the shared reader.
- `Sinks.many().multicast().directBestEffort()`: a subscriber that cannot keep up misses entries instead of stalling the reader; each client's `Coalescer`, not this sink, keeps a slow client current.

### Architect — Limits, and what was not built

- **Replay is bounded.** The stream is trimmed with `MAXLEN ~ 10000` across **all tenants** (`RedisHotProjection.STREAM_MAX_LENGTH`). A busy system shortens the resumable window. A longer outage gets a **gap, not an error**, and needs the history endpoint.
- **Replayed entries are not coalesced**; only live entries are.
- **Every instance reads the whole stream** and discards other tenants' entries. Next step: a stream per tenant or shard. Not done.
- **No authentication or authorization on the query API.** The OpenAPI contract declares `bearerAuth`; nothing validates it and `tenantId` is taken from the caller. Any client can read any tenant (`specs/05-query-api/requirements.md`, Honest gaps).
- **Redis or Postgres down:** the feed retries with exponential backoff from its cursor; the overview marks that part degraded; probes stay UP. **No dedicated automated test** for the retry loop — it was observed by hand (`specs/05-query-api/design.md` failure table).
- **VERIFY:** I have not proven that `StreamReceiver` re-issues `$` on an idle poll (the spec says the same). The explicit-cursor design is correct either way; confirm the receiver behaviour in the Spring Data Redis docs for your version.
- **Load balancer idle timeouts:** the 15 s heartbeat exists for this. I have no verified default timeout to quote; check your LB's setting.
- **Differs from the Question bank:** the bank says "Stateless SSE nodes subscribe to a pub/sub fan-out (Redis / NATS)". This repo uses a **Redis Stream**, not pub/sub, because pub/sub has no replay and `Last-Event-ID` would not work (ADR-0014). Anchor from your Couchbase work: CDC → SSE with a polling fallback.

**In this repo:**
- `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java`, `.../adapter/in/sse/Coalescer.java`, `.../adapter/in/sse/Heartbeat.java`, `.../adapter/out/redis/RedisLiveFeed.java`, `.../domain/EntryId.java`
- Tests:
  - `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/SseStreamControllerTest.java` — `liveOnlyAndTenantFiltered`, `metricsFilter`, `resumeReplaysThenGoesLiveWithoutDuplicates`, `entryDuringReplayIsNotLost`, `replayIsTenantFiltered`
  - `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/SseResumeIT.java` — `resumeThenLive`
  - `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/CoalescerTest.java` — `burstCollapsesToLastValuePerSeries`, `emptyWindowsEmitNothing`, `aSeriesIsSentOncePerChange`, `slowConsumerGetsLatestNotBacklog`
  - `services/query-api/src/test/java/com/streampulse/query/adapter/in/sse/HeartbeatTest.java` — `idleStreamGetsAComment`, `dataAndHeartbeatsInterleave`
  - `services/query-api/src/test/java/com/streampulse/query/domain/EntryIdTest.java` — `comparesNumerically`, `rejectsMalformed`
  - `services/query-api/src/test/java/com/streampulse/query/adapter/in/web/QueryControllersTest.java` — `malformedLastEventIdIsBadRequest`
- ADR: `docs/adr/0014-webflux-sse-redis-streams.md`; spec `specs/05-query-api/design.md`

---

## 8. M4 — Consumer lag diagnosis

### Beginner — A queue at the post office

The queue grows. Before hiring more clerks, ask: is the clerk slow (computer problem), is there only one counter open that handles registered mail (a **hot** counter), or are clerks swapping shifts (a **rebalance**)? Hiring clerks does not help if there are only 6 counters.

### Senior — The playbook

1. **Which lag?** Raw topic lag → the stream processor is behind. `telemetry.agg.v1` lag → the sink is behind.
2. **All partitions or one?** One partition lagging is a **hot key**. More consumers will not help. The lag panel in the repo plots `kafka_consumer_fetch_manager_records_lag_max`, which is the **max over partitions** per client; look per partition too.
3. **Sink: queue depth.** Near the 2000 capacity → Postgres is slow (look at flush latency). Near empty with lag growing → consumers starved or rebalancing.
4. **Rebalance storms.** The sink uses `CooperativeStickyAssignor`, so a rebalance moves only the partitions that must move.
5. **Then act:** fix the hot key; batch the sink write (done); scale consumers **up to the partition count and no further**; shed or sample low-value events (heartbeats before errors); autoscale on lag.

Generic Kafka tool for the per-partition view (it is a stock Kafka script; check the flags for your version):

```
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group sink-writer
```

### Architect — The ceilings and the honest meaning of lag

- **Partition ceiling.** Raw: 12 partitions → at most 12 parallel tasks in the processor. Aggregate: 6 partitions, sink concurrency 3 → exactly **2x** headroom to 6 consumer threads, then a wall. **Consumers beyond the partition count do nothing.** (The aggregate topic has 6; the sink runs 3 threads, so doubling is possible, not 3x.)
- **Lag means "work not yet stored"** (REQ-SK-14), because offsets are acked only after the durable write. A stale dashboard is honest; a wrong one is not.
- **Backpressure is the signal.** Slow Postgres → bounded buffer fills → `submit` fails after 1 s → the listener throws, no ack → redelivery with exponential backoff (1 s up to 30 s, never gives up) → consumer lag rises (ADR-0013).
- **`max.poll.interval.ms` = 300 000** is set above the poll budget (30 s) + longest backoff (30 s) + worst flush retry, or the broker evicts the consumer mid-outage. `SinkWiringTest.pollIntervalCoversTheBudget` asserts the inequality.
- **Not built:** autoscaling on lag (KEDA); the bank answer "autoscale on lag" is a Phase 11 item. Do not claim it.
- Measured: (not measured: peak consumer lag on telemetry.agg.v1 during the load run and time to drain after load stops)
- Observed, not a measurement of lag under load: after the 90 s run, `kafka-consumer-groups.sh --describe` showed lag **1 on each of the 6 partitions** with the sink idle and caught up. That is consistent with the transaction commit marker the exactly-once stream processor writes at the end of each transaction: the marker occupies an offset but is never delivered to a consumer, so the group can never reach the log end. I did not dump the log to confirm. If an alert fires on "lag > 0", it will fire forever on a transactional topic: alert on lag growing, or above a small constant.

**In this repo:**
- `services/sink-writer/src/main/resources/application.yml` (`concurrency: 3`, `max-poll-records: 500`, `CooperativeStickyAssignor`, `max.poll.interval.ms`), `docker/compose.yml` (partition counts), `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java` (`streampulse.sink.batch.queue.depth`), `docker/observability/grafana/dashboards/streampulse.json` (panels: Consumer lag, Freshness p99, Batch-writer queue depth)
- Tests: `services/sink-writer/src/test/java/com/streampulse/sink/config/SinkWiringTest.java` — `pollIntervalCoversTheBudget`, `consumerSettings`; `services/sink-writer/src/test/java/com/streampulse/sink/config/MicrometerSinkMetricsTest.java` — `queueDepthGaugeFollowsTheSupplier`; `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java` — `fullQueueRejectsWithinTheOfferTimeout`

---

## 9. M5 — Dual write and the outbox

### Beginner — Telling two people the same news

You tell your bank you moved house, then phone the post office. If the phone call drops, one knows and the other does not. The fix: write both tasks on one to-do note (the **outbox**), and someone reliably works through the note.

### Senior — The pattern

**Dual-write problem:** you change a database and publish an event; one succeeds and the other fails. **Outbox:** write the event to an outbox table **in the same transaction** as the state change; a relay (a poller, or CDC such as Debezium) publishes from the table; consumers are idempotent because the relay is at-least-once.

### Architect — What the control plane does, and what is still open

The control plane (`services/control-plane`) has a real outbox. The flow of control:

1. A REST write changes the business row (tenant, alert rule and so on) and inserts an outbox row **in the same Postgres transaction**. The outbox port is `Propagation.MANDATORY`: called outside a transaction it throws instead of committing on its own connection (ADR-0015).
2. Debezium reads the outbox table from the Postgres WAL; its `EventRouter` SMT routes every row to the compacted topic `config.changes.v1`, keyed by `aggregate_id` so one entity stays in one partition, in order. The payload is raw Avro bytes (ADR-0016).
3. A delete writes an outbox row with an empty payload, which the router turns into a **tombstone** (null value) so compaction can drop the key.
4. Delivery is **at-least-once** (the relay can replay after a restart), so consumers must dedupe on the change id and the entity version.

Proven live: create, update and delete of one tenant gave three records on `config.changes.v1` (two Avro records, then a null-value tombstone) (`MILESTONES.md`, "Phase 6 — Control plane, Debezium CDC, alert engine"; one local stack, one run). `OutboxAtomicityIT` includes the mutation check where the outbox runs on its own connection.

**Gaps, stated plainly (ADR-0015, ADR-0016):**

- No outbox retention job: rows stay after being relayed, so the table grows.
- Nothing alerts on replication-slot lag. A stopped connector leaves the slot inactive with WAL retained, and an abandoned slot would fill the disk.
- Service nodes have no entity type in the `ConfigChange` contract and are announced as `ENTITY_UNKNOWN` (ADR-0015). VERIFY: whether "apps" are announced at all; I did not find a statement either way.
- `OutboxCdcIT` (Testcontainers plus Debezium) was not rerun in the Phase 6 close-out; the live proof is the compose stack.
- No authentication on the control-plane API; single Postgres instance.

The other dual writes in this repo are handled without an outbox, because each has a different property that makes one unnecessary:

| Two-system write | Handling | Why an outbox is not needed |
|---|---|---|
| Redis claim then Kafka publish (`IngestService`) | Compensating `release()` on publish failure; accept a duplicate over a loss | There is no database transaction to join; the claim is a reservation |
| Postgres write then Redis write (`AggregateSink`) | Postgres first; Redis is a **rebuildable projection**; failure counted and swallowed | Redis can be recomputed from Postgres |
| Postgres write then Kafka offset commit (`AggregateBatchListener`) | Commit only after the write; idempotent upsert absorbs redelivery | The write is idempotent, so at-least-once is safe |

**Differs from the Question bank:** the bank's M5 is "DB update + publish event → outbox". The control plane follows it. The telemetry path does not, on purpose: the sink consumes events and its writes are idempotent. ADR-0013 lists "an outbox or two-phase commit would add machinery this write does not need". The alert engine also has an in-memory per-channel delivery outbox (a different thing from the control-plane outbox).

**In this repo:**
- Outbox: `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java`, `docker/debezium/outbox-connector.json`
- Outbox test: `services/control-plane/src/test/java/com/streampulse/controlplane/OutboxAtomicityIT.java`
- ADRs: `docs/adr/0015-jpa-flyway-transactional-outbox.md`, `docs/adr/0016-debezium-outbox-router-raw-avro.md`
- `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java` (compensating `release`), `services/sink-writer/src/main/java/com/streampulse/sink/application/AggregateSink.java` (Postgres first, Redis swallowed)
- Tests: `services/ingest-gateway/src/test/java/com/streampulse/ingest/application/IngestServiceTest.java` (nested `PublishFailures`) — `aFailedPublishCanBeRetried`, `withoutTheReleaseTheRetryIsSwallowed`; `services/sink-writer/src/test/java/com/streampulse/sink/application/AggregateSinkTest.java` — `storesThenRecordsFreshnessThenProjects`, `storeFailureStopsEverythingElse`, `projectionFailureDoesNotFailTheBatch`

---

## 10. R1 — Timeout, retry, breaker, bulkhead

### Beginner — Calling a restaurant that never picks up

A **timeout**: hang up after ten rings. A **retry**: try once more, but wait a bit longer each time and at a slightly random moment so the whole street does not redial at once. A **circuit breaker**: after enough failed calls, stop dialling for 30 seconds. A **bulkhead**: only 64 phones may be dialling at a time, so one dead restaurant cannot use every phone.

### Senior — The order, and why

```
Retry ( CircuitBreaker ( TimeLimiter ( publish ) ) )
```

- **Timeout is the base.** No timeout, no resilience.
- **TimeLimiter inside the breaker**, so its `TimeoutException` is a failure the breaker **counts**. Reverse them and a permanently hanging dependency never trips the breaker.
- **Retry outside the breaker**, so the breaker counts **every attempt**, not one result per batch. With a window needing 4 calls and 3 attempts per batch, the breaker trips after 2 failing batches, not 4 (ADR-0009).
- **Retry only fast failures** (every attempt holds an in-flight permit). Excluded: `CallNotPermittedException` (breaker open 30 s), `TimeoutException` (slow failure — retrying triples the permit hold), `IllegalArgumentException` (the broker rejected this batch).
- **Backoff with jitter**: `IntervalFunction.ofExponentialRandomBackoff(100 ms, 2.0, 0.5)`. Jitter is the point: without it the retry wave is as synchronised as the failure.
- **Decorator, not `@CircuitBreaker`**: the annotation needs a Spring proxy, and `IngestService` is built with plain `new` so it is unit-testable.
- **Bulkhead = the in-flight `Semaphore(64)`**, with a 250 ms permit wait → 503. A Resilience4j `Bulkhead` is **not** used (REQ-RS-05, open item).

### Architect — Verified by mutation, what the client sees, and the compensating bug

Mutations recorded in ADR-0009 and `RESUME-HERE.md`:

| Mutation | Result |
|---|---|
| `TimeLimiter( CircuitBreaker( … ) )` | the ordering test fails — **but 9 of 10 tests still passed** |
| retry moved inside the breaker | the attempt-counting test fails |
| `ofExponentialBackoff` (no jitter) | the jitter test fails |

Lesson: a suite that passes under both orderings does not test the ordering. Mutate to prove a test can fail.

**What the client sees.** `PublishUnavailableException` → **503 + `Retry-After`**, not 500. The same bytes will succeed after recovery; a 500 tells the client the batch is malformed. `Retry-After` is derived from the breaker's own open wait so they cannot drift (Resilience4j exposes `getWaitIntervalFunctionInOpenState()`, not `getWaitDurationInOpenState()` — a name verified against the jar).

**The coupling nobody expects.** Adding the breaker exposed a data-loss bug: ids claimed in Redis, publish fails, the retry reads "duplicate", answers 202 with nothing published. A breaker makes it worse (it fails fast for its whole open window). Fix: compensating `IdempotencyStore.release(...)`, pinned by a test that reproduces the old behaviour.

**Settings are guesses, not measurements** (`failure-rate-threshold 50`, `sliding-window-size 20`, `minimum-number-of-calls 10`, `wait-duration-in-open-state 30s`, `call-timeout 5s`, `retry-max-attempts 3`). `minimum-number-of-calls` stops two unlucky calls at 04:00 from opening the breaker. `call-timeout` must exceed `slow-call-duration-threshold` or the slow-call signal never fires.

**Retry makes duplicates more likely.** Kinesis `PutRecords` is not idempotent; Kafka's producer is (producer id + sequence). Accepted, because the pipeline is idempotent downstream.

**In this repo:**
- `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java`, `.../config/IngestConfig.java`, `.../adapter/in/rest/IngestExceptionHandler.java`, `services/ingest-gateway/src/main/resources/application.yml` (`publisher-resilience`)
- Tests in `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisherTest.java` (nested classes): `failuresOpenIt`, `slowSuccessesOpenIt`, `timeoutsAreCountedByTheBreaker`, `failsFastWithoutTouchingTheBroker`, `retryAfterMatchesTheConfiguredWait`, `trialSuccessesCloseIt`, `trialFailuresReopenIt`, `recoversOnItsOwnAfterTheWait`, `oneBlipIsRiddenOver`, `givesUpAfterMaxAttempts`, `eachAttemptIsCountedByTheBreaker`, `openBreakerIsNotRetried`, `timeoutsAreNotRetried`, `batchRejectionsAreNotRetried`
- ADR: `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md`

---

## 11. R2 — SLIs, SLOs, and burn rate

### Beginner — A restaurant's health check

You do not judge a restaurant by how hot the oven is. You judge it by how long guests wait and how many leave unhappy. Those are **SLIs** (service level indicators). An **SLO** is the promise: "99.9% of guests served within 60 minutes". The **error budget** is the 0.1% you may miss. **Burn rate** is how fast you are using it up.

### Senior — The five SLIs, mapped to this repo

| SLI | Metric / source | Built? |
|---|---|---|
| Ingest success rate | `http_server_requests_seconds_count{uri="/v1/events:batch",status=~"5.."}` ratio | dashboard panel: "Ingest 5xx ratio and p99 latency"; recording rule written in `docker/observability/rules/streampulse-sli.rules.yml` (never run or verified) |
| p99 ingest latency | `http_server_requests_seconds_bucket` (needs `percentiles-histogram: http.server.requests: true`, set in ingest `application.yml`) | dashboard panel |
| End-to-end freshness | `streampulse.sink.freshness` (Timer with histogram, seconds, window end → durable write) | dashboard panel "Freshness p99" |
| Consumer lag | `kafka_consumer_fetch_manager_records_lag_max` | dashboard panel |
| DLQ rate | `streampulse.processor.events.rejected` (tag `reason`), `streampulse.sink.dead.letters` | dashboard panel |
| Data completeness (received vs expected heartbeats) | — | **not built**; still not an SLI. `scripts/accounting-check.py` (sent vs stored per closed window) is an offline check, not a metric |

Other counters worth naming: `streampulse.ingest.events.published`, `.duplicates`, `.rejected` (capacity), `.throttled` (quota), `streampulse.ingest.idempotency.unavailable` (fail-open count), `streampulse.ingest.publish.failures`, `streampulse.processor.events.duplicates`, `streampulse.sink.batch.flush`, `streampulse.sink.projection.failures`.

RED (rate, errors, duration) for services; USE (utilisation, saturation, errors) for resources.

### Architect — SLO, burn rate, and the probe design

**Written, never run:** the repo has recording rules (`docker/observability/rules/streampulse-sli.rules.yml`), alert rules (`streampulse-alerts.rules.yml`) and a rules test file under `docker/observability/rules/tests`. The alerts are multiwindow multi-burn-rate (14.4x / 6x / 1x) on an ingest availability target of 99.9% and a freshness target of 99% durable within 30 s of window end (both targets, not measurements), plus cause alerts. There are four Grafana dashboards (`streampulse`, `streampulse-red`, `streampulse-jvm`, `streampulse-slo`) and an OTel config. **None of it was ever run or wired:** `make up-obs` does not load the rules or the new dashboards, `promtool` was never run against them, and `docs/runbook.md` and `docs/system-health.md` do not exist. Say "written, unverified", not "built".

**Why freshness beats lag.** Lag counts records; freshness counts what the user feels. A pipeline can have zero lag and a stalled window (see the quiet-partition stall in section 3); only freshness shows it.

**Probe design is part of health.**

- **Liveness** answers "am I broken beyond recovery?" and contains **only** `livenessState`. A dependency in it is a restart storm. Reproduced by mutation: adding Redis to the liveness group with Redis down turned liveness into 503 (`MILESTONES.md`, "restart storm on camera").
- **Readiness** answers "can I take traffic?". In ingest it is `readinessState` only, and the drain flips it. In the stream processor it includes `streamsState` (running or rebalancing counts as ready, so a rebalance does not pull every pod out at once). In sink-writer and query-api it is `readinessState` only.
- **Fail-open must be visible.** `streampulse.ingest.idempotency.unavailable` is an SLI: a degradation that is not measured is silent.

**In this repo:**
- `docker/observability/grafana/dashboards/streampulse.json`, `docker/observability/prometheus.yml`
- `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/MicrometerIngestMetrics.java`, `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java`, `services/stream-processor/src/main/java/com/streampulse/processor/config/MicrometerProcessorMetrics.java`, `services/stream-processor/src/main/java/com/streampulse/processor/config/StreamsStateHealthIndicator.java`
- Tests: `services/sink-writer/src/test/java/com/streampulse/sink/config/MicrometerSinkMetricsTest.java` — `freshnessIsAHistogramTimer`, `freshnessExportsBuckets`; `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java` — `bothProbesAreUpAndUnauthenticated`, `livenessGroupHasNoDependencies`, `readinessGroupIsDrivenByAvailabilityState`, `readinessGoesOutOfServiceOnDrainButLivenessStaysUp`; `services/stream-processor/src/test/java/com/streampulse/processor/config/StreamsStateHealthIndicatorTest.java` — `runningAndRebalancingAreUp`, `otherStatesAreDown`, `stateIsReported`; `services/stream-processor/src/test/java/com/streampulse/processor/config/StreamProcessorWiringTest.java` — `readinessIncludesStreamsStateLivenessDoesNot`; `services/sink-writer/src/test/java/com/streampulse/sink/config/SinkWiringTest.java` — `healthGroups`; `services/query-api/src/test/java/com/streampulse/query/config/QueryApiWiringTest.java` — `probesDependOnNoDataStore`

---

## 12. R4 — Graceful shutdown and zero-loss deploys

### Beginner — Closing a shop

You turn the sign to "closed" so no new customers come in, serve the people already inside, lock the door, and only then switch off the lights and the till. Switch off the till first and the people still being served cannot pay.

### Senior — The shutdown ladder

Ingest uses Spring's `SmartLifecycle` phases. `SmartLifecycle` beans stop in **descending** phase order. The ladder, read from the jars and recorded in ADR-0010:

```
phase 2147482623  in-flight HTTP requests finish   (WebServerGracefulShutdownLifecycle)
phase 2147481599  the servlet connector stops      (WebServerStartStopLifecycle)
phase 200         GrpcServerLifecycle: stop accepting, drain, then cancel
phase 100         IngestDrainLifecycle: readiness → REFUSING_TRAFFIC, wait for in-flight batches
```

- `IngestDrainLifecycle.stop()` publishes `ReadinessState.REFUSING_TRAFFIC` (readiness → 503, the pod leaves the Service) then polls the in-flight semaphore until all permits return, up to `drain-timeout` (20 s). A timeout logs an error: those clients get no 202 and must retry; idempotency makes that safe.
- `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 25s`. The sum of the phases must fit inside Kubernetes `terminationGracePeriodSeconds` (the comment says Phase 11 sets 45 s; **no Kubernetes manifests exist; Phase 11 has only a Dockerfile**).

The sink:

- Stop the Kafka listener first (no new poll), then `BatchWriterLifecycle` (phase `AbstractMessageListenerContainer.DEFAULT_PHASE - 1000`) closes the writer, flushing what the last poll left in the queue; connections (Hikari, Lettuce) close last. Closing the writer first would reject records the container is still handing it.
- A write in flight is never acked until durable, so a SIGTERM mid-poll causes **redelivery**, not loss.

### Architect — Zero-loss rules, and what is not proven

Rules to state:

1. **Stop polling → finish in-flight → commit offsets → exit.** Here: container stops, writer drains, offsets were committed only after writes.
2. **Readiness off before shutdown** so the load balancer stops sending.
3. **Liveness must not depend on the broker** or you restart the fleet during a broker blip.
4. **Cooperative rebalancing** (`CooperativeStickyAssignor`) avoids stop-the-world rebalances in the sink.
5. PodDisruptionBudget, a canary pod watched on lag and error rate, autoscaling on lag: design answers from the Question bank (R4, D4). **Not built here.** Phase 11 has only `deploy/docker/Dockerfile`; there are no Kubernetes manifests.

**What is not proven (say it):**
- **No test sends a real SIGTERM.** `HealthProbesIT` calls the lifecycle `stop()` directly; it proves the readiness flip and the liveness non-flip, not the full `SmartLifecycle` ordering under a real signal (`MILESTONES.md`). `RollingRestartChaosIT` (BS-01's acceptance test) is a Phase 11 item.
- The drain was verified by mutation: commenting out the single `AvailabilityChangeEvent.publish(...)` line makes `readinessGoesOutOfServiceOnDrainButLivenessStaysUp` fail (**3 of 4 probe tests still passed**).
- Processor crash mid-window recovery **was demonstrated, and it fails** (MILESTONES.md "Phase 9 · T9-34": `kill -9` of the processor, no duplicates, but 1,482 of 4,000 and 2,554 of 8,000 on-time events dropped as expired-window records; likely cause multi-partition backlog catch-up passing the 5 s grace, not proven; unfixed). The recovery requirement (REQ-RS-05) stays unticked. `StreamProcessorIT` still does not kill anything; the crash test is a script, not a JUnit test.

**In this repo:**
- `services/ingest-gateway/src/main/java/com/streampulse/ingest/lifecycle/IngestDrainLifecycle.java`, `.../lifecycle/GrpcServerLifecycle.java`, `services/sink-writer/src/main/java/com/streampulse/sink/lifecycle/BatchWriterLifecycle.java`, ingest `application.yml` (`drain-timeout`, `shutdown-timeout`, `timeout-per-shutdown-phase`)
- Tests: `services/ingest-gateway/src/test/java/com/streampulse/ingest/adapter/HealthProbesIT.java` — `readinessGoesOutOfServiceOnDrainButLivenessStaysUp`; `services/sink-writer/src/test/java/com/streampulse/sink/lifecycle/BatchWriterLifecycleTest.java` — `lifecycleStopsAfterTheListenerContainer`, `phaseIsBelowTheContainers`; `services/sink-writer/src/test/java/com/streampulse/sink/config/SinkWiringTest.java` — `writerLifecycleIsRegistered`
- ADR: `docs/adr/0010-grpc-as-a-second-ingest-protocol.md` (the shutdown ladder)

---

## 13. Avro `Utf8` vs `String`

### Beginner — Two spellings of the same word that a computer treats as different

Imagine a filing cabinet where "Smith" typed on a typewriter and "Smith" typed on a computer are filed in **different drawers** because the cabinet compares the **machine** that typed them. Avro hands you the typewriter version (`Utf8`); your code looks in the computer drawer (`String`).

### Senior — The hazard

- The generated Avro classes use Avro's **default string type**, so decoded strings are `org.apache.avro.util.Utf8`.
- A `Utf8` **never equals a `String`** and has a **different hash code**. A `HashMap` keyed by one silently misses the other. A grouping key built from a `Utf8` silently **splits groups**.
- The rule: call `toString()` at the boundary, before using a value as a key or comparing it.
- `MILESTONES.md` (Phase 3 Day 1) records the first-hand failure: two assertions failed with `expected: "…" (String) but was: "…" (Utf8)`. The codegen was **deliberately not changed**; the discipline is at the boundaries.

### Architect — Where the boundary is drawn, and what is not pinned

- **One conversion point per service:** `ObservationMapper` (stream processor, "the one place the Avro wire type becomes a domain type"), `AggregateDecoder.text(...)` (sink), `AvroBinaryCodec` Javadoc (the warning, shared by publisher, processor and sink).
- The key strings (`AggregateKey.asString()`, the aggregate topic key) are built from already-converted values, which is why the topology comment says "every key is built with `toString()`".
- The dedup processor calls `getEventId().toString().toLowerCase(Locale.ROOT)`: ids that differ only by case are the same event.
- **What is not pinned:** I found no test that fails if a `toString()` is removed, and no mutation result for it. **VERIFY** by mutation (remove `toString()` in `ObservationMapper`, run `TelemetryTopologyTest`) before claiming a test guards it. The tests that would exercise it: `duplicateEventIdCountedOnce`, `recordKeyIdentifiesTheWindowAndMetric`, `regionsAreSeparate`.
- Wire format is **raw Avro binary, no schema-registry framing** (`AvroBinaryCodec`). Schema evolution is guarded by `SchemaCompatibilityTest` against frozen baselines, not by a registry. Writer and reader schema are the same class, so there is no cross-version resolution; that is what a registry would add. **Differs from the Question bank M7**, which names a schema registry; registry framing is a later, additive change (ADR-0012).

**In this repo:**
- `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java`, `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/ObservationMapper.java`, `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateDecoder.java`
- Tests: `libs/contracts/src/test/java/com/streampulse/contracts/AvroBinaryCodecTest.java` — `roundTrip`, `garbageIsATypedFailure`, `trailingBytesAreRejected`, `emptyPayload`; `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/AggregateDecoderTest.java` — `decodesValidAggregateToPlainStrings`, `absentTitleAndRegionBecomeEmpty`, `garbageIsRejected`; `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java` — `currentSchemaCanReadBaselineData`, `breakingChangesAreRejected`, `addingAnOptionalFieldIsSafe`

---

## 14. Testing strategy: Gradle, Testcontainers, TopologyTestDriver, mutation

### Beginner — Rehearsal versus opening night

A rehearsal in your living room (fast, no audience) shows whether the lines are right. Opening night in a real theatre (slow, costly) shows whether the lights, the stage and the audience work. You need both; they prove different things.

### Senior — What each layer proves

| Layer | Runs with | Proves | Does not prove |
|---|---|---|---|
| Unit with fakes | `./gradlew test` (no Docker) | logic, ordering, edge cases in milliseconds (`IngestServiceTest`, `BatchWriterTest`, `TitleOverviewServiceTest`) | real broker or database behaviour |
| `TopologyTestDriver` | in memory, no broker | every window/grace/dedup/DLT behaviour of the topology (`TelemetryTopologyTest`) | transactions, real timestamps, wiring |
| Spring wiring tests | context only, no Docker (`SinkWiringTest`, `StreamProcessorWiringTest`, `QueryApiWiringTest`) | the context assembles; settings are what the design needs; probe groups | behaviour under traffic |
| ArchUnit | classpath bytecode (`*ArchitectureRulesTest`) | layering: domain/batch/application are framework-free | runtime behaviour |
| `*IT` with Testcontainers | `./gradlew integrationTest` (`make it`), needs Docker | real Kafka / Postgres / Redis behaviour (`NoLossNoDoubleCountIT`, `StreamProcessorIT`, `KafkaIngestIT`, `SseResumeIT`, `MetricWindowUpsertIT`) | a real crash or a broker failing mid-write (the ITs do not kill anything; the live-stack crash test is `scripts/accounting-check.py --at "12:pkill -9 ..."`, a script, not an IT) |
| Mutation check | break the code on purpose | that a test **can fail** | — |

Mechanics worth knowing:

- **`TopologyTestDriver`**: the topology is a pure function (`TelemetryTopology.build`) with an injected `Clock`. The test sets `STATESTORE_CACHE_MAX_BYTES_CONFIG = 0` because a record cache would hold a window's updates until the next commit and the test would assert on timing the topology does not promise. Stream time advances only on later records, so tests pipe a "tick" (`tickPastWindowOne`).
- **`StreamProcessorIT`**: real broker, `exactly_once_v2` as in `application.yml`; one partition per topic so there is one task and stream time is deterministic; the verifying consumer uses `read_committed`, as a downstream consumer of a transactional topic must.
- **Gradle:** `*IT` classes run only under `integrationTest`; `./gradlew test` is Docker-free; `check` runs both. Gradle 9 fails a `Test` task that discovers no tests, so `integrationTest` sets `failOnNoDiscoveredTests = false` (`libs:*` have no `*IT` classes). `jacocoTestReport` uses `mustRunAfter(integrationTest)`: an ordering, not a dependency, so the report does not force the slow task.
- **Testcontainers lessons** (`MILESTONES.md`, `RESUME-HERE.md`): a dead-but-present Docker socket produces *silence*, not an error; `apache/kafka:3.9.0` fails under Testcontainers 1.21.2 with an advertised-listener error while **3.9.1** starts (root cause not established); the resolved Testcontainers is 1.21.2 although the version catalog says 1.21.3 (the Boot BOM wins, an unresolved follow-up; **VERIFY:** the catalog still says 1.21.3 and I did not re-resolve it).
- **Fixed ports are shared globals.** Two `@SpringBootTest` contexts in one JVM collided on gRPC port 9090 (fixed by `port=0`); it never reproduces when either class runs alone.

### Architect — Mutation, false confidence, and what "green" does not mean

Recorded mutation results (all in the repo's own docs):

| Claim | Mutation | Result | Source |
|---|---|---|---|
| decorator order | swap TimeLimiter and CircuitBreaker | ordering test red; 9 of 10 still green | `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md` |
| retry outside breaker | move retry inside | attempt-counting test red | same |
| jitter | `ofExponentialBackoff` | jitter test red | same |
| audience check | delete `audiences` | the wrong-audience test red; 4 of 5 still green | `MILESTONES.md`, Phase 2 Keycloak |
| readiness drain | comment out `AvailabilityChangeEvent.publish` | the drain test red; 3 of 4 still green | `MILESTONES.md` |
| liveness group | add Redis to it | restart storm: liveness 503 | `MILESTONES.md` |
| protocol parity | `.trim().toUpperCase()` in the gRPC mapper | parity test red, names the field | `MILESTONES.md` |
| BatchWriter lock | remove `ReentrantReadWriteLock` | randomised test **stayed green**; deterministic test red | this document, section 4; `specs/04-sink-writer/design.md` |
| `orTimeout` | remove it | `slowCallIsDegradedAndTheBudgetHolds` red | `specs/05-query-api/tasks.md` T5-04 (no console output in MILESTONES) |

Three lessons to say:

1. **A passing suite proves the code passes the suite.** Only a deliberate break proves the suite can fail. In every row above, most tests stayed green.
2. **Randomised concurrency tests give false confidence** when the dangerous window is tiny. Force the interleaving by owning a collaborator.
3. **Running the app is a different test from testing the app.** `bootRun` lacked a system property that the tests had; the whole suite was green while the first real request returned a 500 (`MILESTONES.md`).

**Also, a self-correction to know about:** test totals in older MILESTONES entries were inflated by 25 because stale `kata/build/test-results` were still being summed after the module was removed; corrected there. `MILESTONES.md` now has "Phase 3 · Days 2-5" (processor, sink, query API, load run) and `specs/04-sink-writer/tasks.md` T4-12 is ticked.

**In this repo:**
- `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java`, `services/stream-processor/src/test/java/com/streampulse/processor/StreamProcessorIT.java`, `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/NoLossNoDoubleCountIT.java`, `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java`
- ArchUnit: `services/sink-writer/src/test/java/com/streampulse/sink/SinkArchitectureRulesTest.java` — `domainIsFrameworkFree`, `batchWriterIsFrameworkFree`, `applicationIsFrameworkFree`, `dependenciesPointInward`, `adaptersDoNotDependOnConfig`, `noFieldInjection`; `services/stream-processor/src/test/java/com/streampulse/processor/ProcessorArchitectureRulesTest.java` — `domainIsFrameworkFree`, `domainDependsOnNothingOutward`, `adaptersDoNotDependOnConfig`; `services/query-api/src/test/java/com/streampulse/query/QueryArchitectureRulesTest.java` — `coreIsFrameworkFree`, `dependenciesPointInward`, `reactorOnlyInAdapters`, `adaptersStayApart`, `constructorInjectionOnly`
- Build: `build-logic/src/main/kotlin/streampulse.java-conventions.gradle.kts`, `Makefile` (`make test`, `make it`)

---

## Interview signal checklist

When you hear these phrases, these mechanisms should appear in your answer.

| Interview signal | Response |
|---|---|
| "real-time telemetry" / "1M events per second" | Numbers first (assumption, labelled); keyed topic; aggregate before storing (~500x reduction in the assumption) |
| "no data loss, no double counting" | Idempotent producer + `acks=all`; consumer commits **after** the durable write; idempotent upsert; "effectively-once = at-least-once + idempotent processing"; exactly-once only Kafka → Kafka |
| "per-session order" | Key by `sessionId`; order only within a partition; process each partition sequentially; global order not needed |
| "late or out-of-order events" | Event time; grace period; suppress; name the freshness cost (window + grace); name the quiet-partition stall |
| "hot partition / one live match" | Salt the key; two-stage aggregation; sketch for distinct counts. **Say it is not built here.** |
| "consumer lag is growing" | Diagnose first (which lag, one partition or all, queue depth); consumers ≤ partitions; shed low-value events |
| "thread-safe batch writer" | Bounded queue, one flusher, flush on N or T, offer with timeout, drain on close, **the open-check/close race**, retry + DLQ, metrics |
| "call three services in 300 ms" | One deadline; dedicated bounded executor; `orTimeout` does not cancel; degrade per part |
| "MVC, WebFlux or virtual threads" | Name the workload; ingest blocking → virtual threads; streams with demand → WebFlux; the never-block-the-loop rule |
| "push live metrics to dashboards" | SSE, `Last-Event-ID`, subscribe-before-read, coalescing, heartbeat, check LB idle timeout |
| "timeout, retry, breaker, bulkhead" | Order matters: Retry( CircuitBreaker( TimeLimiter ) ); retry only fast failures; jitter; verified by mutation |
| "define system health" | User-facing SLIs, SLO, error budget, burn rate; liveness vs readiness |
| "zero-loss deploy" | Stop polling → finish in-flight → commit → exit; readiness off first; liveness not dependent on the broker |
| "DB update plus publish an event" | Dual-write problem; outbox + relay/CDC; idempotent consumers; built in the control plane (transactional outbox + Debezium; ADR-0015/0016); the telemetry path does not need one |
| "a dependency is slow on a peak night" | Degraded modes decided in advance; "telemetry must never break playback" |

## Anti-patterns

What **not** to say or do, and what to say instead.

| Anti-pattern | Why it fails | Correct alternative |
|---|---|---|
| Claiming "exactly-once end to end" | It holds only Kafka → Kafka with transactions; anything leaving Kafka is at-least-once | "At-least-once + idempotent writes = effectively-once", and say where each half lives |
| Drawing boxes before numbers | The interviewer cannot judge the design | 1 M events/s and ~1 GB/s (assumption) first |
| `enable.auto.commit=true` | Commits what `poll()` returned, not what you processed; a crash loses records | Manual ack after the durable write (`AggregateBatchListener`) |
| An unbounded queue or buffer | Failure relocates to the heap | Bounded queue + `offer` with timeout (`BatchWriter`) |
| Losing records on shutdown | Accepted-but-unwritten data | Stop intake, drain, flush, then close; fix the open-check/close race |
| `synchronized` around a blocking call | Pins a virtual thread's carrier on Java 21; serialises submitters | `ReentrantReadWriteLock` / `ReentrantLock` |
| Trusting a randomised concurrency test | A tiny window may never be hit; 100 green runs prove nothing | Control the interleaving with a stalling collaborator; mutation-test it |
| `CompletableFuture` on the common pool | Shared and CPU-sized; one blocking call starves unrelated work | Dedicated bounded rejecting executor |
| Assuming `orTimeout` cancels the call | It completes the future; the call keeps its worker | Bound and reject; set query timeouts |
| Blocking an event-loop thread in WebFlux | Stalls every connection on that loop | One bounded named executor + a test that asserts thread names |
| Matching only `reactor-http-nio` in a thread-name test | The server's threads are `webflux-http-nio-N`; the assertion passes vacuously | Match `http-nio` |
| A dependency in the liveness probe | One Redis blip restarts every pod at once | Liveness = liveness state only; dependencies in readiness, if at all |
| Retrying a slow failure | Multiplies permit hold during the incident | Retry only fast failures; skip timeouts and open breakers |
| Retry without jitter | Synchronised retry wave lands on a dependency that has not recovered | Exponential backoff with random jitter |
| Returning 500 for a transient broker failure | Client concludes the batch is malformed and drops it | 503 + `Retry-After` |
| Claiming a number you did not measure | A wrong number delivered confidently is worse than none | Say "assumption", or "I did not measure that" |
| Claiming a feature that is not built | Exposed by the first follow-up | Say: no auth on the query API; alert rules written but never wired or verified; no hot-key salting; no lag autoscaling |
| Salting a key and summing distinct counts with a random salt | The same viewer is counted in two salts | Salt by hash of `sessionId`, or merge HyperLogLog sketches |
| Writing the cache/projection first | A reader sees a value the durable store never held | Durable store first; projection is derived and rebuildable |
| Dead-lettering because the store was down | Turns an outage into data loss | Retry forever on outage; dead-letter only deterministic poison |

---

## Where this repo differs from the Question bank

Say these as deliberate honest notes, not as apologies.

| Question bank (idealised) | This repo (what the code does) | Where |
|---|---|---|
| Kinesis/Kafka + Flink; hot store Redis/OpenSearch; raw → Firehose → S3 Parquet | Kafka + Kafka Streams; Postgres + Redis; **no OpenSearch; no raw archive in use** (an S3 bucket via Terraform against moto and an archive compactor exist, but no service writes raw events to it and the compactor runs only in tests) | ADR-0012 |
| 429 for backpressure | **429 = tenant quota only; 503 = gateway saturated or breaker open / stream unavailable.** `api/openapi/ingest-v1.yaml` now documents both | `IngestExceptionHandler.java`, `api/openapi/ingest-v1.yaml` |
| Client-side gzip | **Not implemented or tested** on the server | grep of `services/ingest-gateway/src` finds no gzip handling (VERIFY) |
| WebFlux for SSE fan-out; MVC + virtual threads for ingest | Same split; **WebFlux only in query-api** | ADR-0006, ADR-0014 |
| Redis as the hot store | **Redis is a rebuildable projection; Postgres is the durable source of truth** | ADR-0013 |
| Approximate distinct count | **Exact set of session ids**, not HyperLogLog | `WindowAccumulator.java` |
| Salt the key for hot partitions | **Not salted**; `tenant:title:region` is one key | `AggregateKey.java` |
| Autoscale on lag (KEDA) | **Not built** | `specs/` Phase 11 |
| SLO burn-rate alerts | **Rules written, not wired and not verified** (`make up-obs` does not load them; `promtool` never run) | `docker/observability/rules/` |
| Multi-broker, replicated Kafka | **Single broker, replication factor 1** | `docker/compose.yml` |
| Schema registry | **Raw Avro, no registry**; compatibility gated by `SchemaCompatibilityTest` | ADR-0012 |
| Authenticated dashboards | **Ingest: JWT + Keycloak OIDC. Query API, control-plane, alert-engine, activity-feed: no auth.** The dashboard has an OIDC/PKCE flow, off by default and never exercised live | `specs/05-query-api/requirements.md` |
| Outbox for DB + event | **Implemented in the control plane** (outbox row in the same transaction, Debezium EventRouter to `config.changes.v1`); not used on the telemetry path | section 9 |
| J5 sketch: `volatile open`, "fix with poison pill or a lock" | **Read-write lock**, per-record futures, retry + isolate + dead-letter | section 4 |
| Idempotent sink "upsert by eventId" | **Upsert by aggregate identity** guarded by `computed_at` | `JdbcMetricWindowStore.java` |

---

## Honest gaps

Taken from `MILESTONES.md`, the specs and what I found reading the code.

- **Every number is a target unless MILESTONES.md records a measurement for it.** Measured (one M2 Pro laptop, one broker, loadgen on the same machine, one or two runs each): throughput, ingest latency and freshness (Phase 3); ingest saturation (T9-11); 5xx and 429 at saturation; overview read p99 at about 52 req/s and per-window freshness at 1,000 and 2,000 events/s (T9-13); end-to-end accounting (T9-12); and the processor crash result (T9-34). Window 10 s, grace 5 s, batch 200 / 100 ms / 2000 / 1 s, overview 300 ms, coalesce 250 ms, heartbeat 15 s, breaker thresholds: all explainable, none measured.
- **Single broker.** `acks=all` is asserted as configuration, not behaviour under replica loss.
- **No JUnit test kills the JVM.** `NoLossNoDoubleCountIT` throws an exception and `StreamProcessorIT` does not kill the processor. The processor **was** killed with `kill -9` by a script (T9-34) and that **failed**: on-time events were dropped. Still true: no test sends a real SIGTERM, and the sink was never killed.
- **Ingest idempotency is demo-scale.** One sequential `SET NX EX` round trip per event id (up to 500 per batch; `RedisIdempotencyStore.claimAll` loops), with a 24 h TTL. At the prompt's assumed 1 M events/s that is on the order of 86 billion keys (assumption arithmetic). The processor already dedups within window + grace.
- **Hot key not salted; distinct count is exact.**
- **Redis projection has no rebuild job** and no retry policy of its own (2 s command timeout, then counted and swallowed).
- **Live feed can repeat a record after a redelivery;** the `latest` hash can briefly be older after a cross-partition redelivery.
- **Query API: no authentication or authorization;** overview title metadata still comes from `StubTitleCatalog`; the control plane now exists (it has a `TitleEntity`) but query-api does not read from it; replay is bounded by stream trimming; the Redis-down retry loop has no automated test.
- **Alert and burn-rate rules are written but never wired or verified; no lag autoscaling; no Kubernetes manifests** (Phase 11 has only a Dockerfile).
- **`RESUME-HERE.md` is as of 2026-10-12** and is stale relative to the later phases.
- **T9-34:** processor crash drops on-time events; unfixed; needs an ADR (see section 1).
- **Resilience4j `Bulkhead` is not used;** the in-flight `Semaphore` is a bulkhead in effect.
- **Authorization (scopes/roles) in ingest is untouched;** only authentication.

---

## Quick recall cheat sheet

> Close this file. Answer from memory. Open if stuck.

| Question-bank ID | One-line answer | Class | Test |
|---|---|---|---|
| **SD1** telemetry design | Numbers first (1 M/s, ~1 GB/s — assumption); stateless ingest → keyed Kafka → Kafka Streams windows → idempotent sink → query/SSE; telemetry never breaks playback | `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`, `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java` | `StreamProcessorIT.endToEnd`, `KafkaIngestIT.batchReachesKafkaWithTheRightKey` |
| **SD3** push live metrics | SSE + `Last-Event-ID` (= Redis stream entry id); subscribe-before-read; coalesce 250 ms; heartbeat 15 s | `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java`, `Coalescer.java` | `SseStreamControllerTest.entryDuringReplayIsNotLost`, `SseResumeIT.resumeThenLive`, `CoalescerTest.slowConsumerGetsLatestNotBacklog` |
| **M1** Kafka vs Kinesis vs SQS | Replay, ordering scope, per-key throughput, who runs it; both backbones behind one `EventPublisher` port; Kinesis `PutRecords` can partially fail and is not idempotent | `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java`, `.../adapter/out/kinesis/KinesisEventPublisher.java` | `IngestEndToEndIT.sameSessionGoesToTheSameShard`, `KafkaIngestIT.sameSessionSamePartition` |
| **M2** no loss, no double count | Idempotent producer + `acks=all`; commit after the durable write; idempotent upsert guarded by `computed_at`; exactly-once only Kafka → Kafka | `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java`, `.../adapter/out/postgres/JdbcMetricWindowStore.java` | `NoLossNoDoubleCountIT.failureBetweenWriteAndAckLosesNothingAndDoubleCountsNothing`, `MetricWindowUpsertIT.olderComputedAtDoesNotOverwriteNewer` |
| **M3** per-session order | Key by `sessionId`; order only within a partition; dedup before repartition; the repartition is the cost | `.../ingest/domain/IngestCommand.java`, `.../processor/adapter/streams/TelemetryTopology.java` | `KafkaIngestIT.sameSessionSamePartition`, `IngestServiceTest.partitionKeyIsSessionId` |
| **M4** consumer lag | Diagnose first (which lag, hot partition, queue depth); consumers ≤ partitions; shed low-value events | `services/sink-writer/src/main/resources/application.yml`, `MicrometerSinkMetrics.java` | `SinkWiringTest.pollIntervalCoversTheBudget`, `BatchWriterTest.fullQueueRejectsWithinTheOfferTimeout` |
| **M5** dual write / outbox | Outbox + relay/CDC + idempotent consumers; built in the control plane (same-transaction outbox row, Debezium to `config.changes.v1`); telemetry path: compensating release, Postgres-then-Redis | `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java`, `.../application/IngestService.java`, `.../sink/application/AggregateSink.java` | `OutboxAtomicityIT`, `IngestServiceTest.aFailedPublishCanBeRetried`, `AggregateSinkTest.projectionFailureDoesNotFailTheBatch` |
| **M6** backpressure end to end | 429 (quota) / 503 (capacity) + `Retry-After`; fair semaphore; bounded queues; sink buffer full → lag | `IngestService.java`, `BatchWriter.java` | `IngestServiceTest.rejectsWhenSaturated`, `BatchWriterTest.fullQueueRejectsWithinTheOfferTimeout` |
| **M7** schema evolution | Avro, backward-compatible only; frozen baselines gate it; raw Avro, **no registry here**; bad events → DLT | `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java` | `SchemaCompatibilityTest.breakingChangesAreRejected`, `addingAnOptionalFieldIsSafe`, `AvroBinaryCodecTest.garbageIsATypedFailure` |
| **J2** MVC vs WebFlux vs VT | Ingest blocking → MVC + virtual threads; query streams → WebFlux; never block the loop | `.../query/application/BoundedExecutor.java`, ingest `application.yml` | `BlockingIsolationTest.noBlockingPortRunsOnAnEventLoopThread`, `QueryApiWiringTest.runsOnWebFlux` |
| **J5** batch writer | Bounded queue, one flusher, N-or-T flush, offer timeout, drain on close, **read-write lock for the open-check/close race**, retry then isolate then DLQ | `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java` | `BatchWriterTest.closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck` (the deterministic one), `acceptedImpliesWrittenUnderConcurrentSubmitAndClose` (randomised; did not catch the removed lock) |
| **J6** 3 calls in 300 ms | One deadline; bounded rejecting executor; `orTimeout` does not cancel; degrade per part; structured concurrency is preview in Java 21 (VERIFY) | `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java` | `TitleOverviewServiceTest.slowCallIsDegradedAndTheBudgetHolds`, `saturatedExecutorDegradesInsteadOfBlocking` |
| **R1** resilience order | Retry( CircuitBreaker( TimeLimiter( publish ) ) ); retry only fast failures; jitter; mutation-verified | `.../adapter/out/resilience/ResilientEventPublisher.java` | `ResilientEventPublisherTest.timeoutsAreCountedByTheBreaker`, `eachAttemptIsCountedByTheBreaker` |
| **R2** system health | Five SLIs (success rate, p99, freshness, lag, DLQ); SLO + burn-rate rules written in `docker/observability/rules/` but **never wired or verified** | `MicrometerSinkMetrics.java`, `docker/observability/grafana/dashboards/streampulse.json` | `MicrometerSinkMetricsTest.freshnessIsAHistogramTimer`, `HealthProbesIT.livenessGroupHasNoDependencies` |
| **R4** zero-loss deploy | Readiness off → stop poll → drain → commit → exit; liveness never depends on the broker | `.../ingest/lifecycle/IngestDrainLifecycle.java`, `.../sink/lifecycle/BatchWriterLifecycle.java` | `HealthProbesIT.readinessGoesOutOfServiceOnDrainButLivenessStaysUp`, `BatchWriterLifecycleTest.lifecycleStopsAfterTheListenerContainer` |
| **R5** dependency slow | Fail open on Redis; breaker + 503 + Retry-After on Kafka; overview degrades per part; sink backs off and lag rises | `IngestService.java`, `TitleOverviewService.java`, `AggregateBatchListener.java` | `IngestServiceTest.publishesAnywayAndReportsDegraded`, `TitleOverviewServiceTest.allCallsFailingStillAnswers`, `AggregateBatchListenerTest.storeFailureMeansNoAck` |
| **D2** caching dashboard reads | Redis `latest` hash is the read model; one shared reader; coalescing is single-latest-wins; not cache-aside with TTL here | `.../adapter/out/redis/RedisHotProjection.java`, `RedisLatestMetrics.java` | `RedisLatestMetricsIT.readsTheTitlesSeries`, `titleIdMayContainAColon` |
| Windows / late data | Event time; 10 s window; 5 s grace; suppress; freshness floor 15 s; quiet-partition stall | `TelemetryTopology.java` | `TelemetryTopologyTest.emitsOneFinalAggregatePerMetricAfterGrace`, `lateAfterGraceIsDroppedNotEmitted`, `noOutputBeforeWindowCloses` |
| Avro `Utf8` | `Utf8 != String`, different hash; `toString()` at the boundary | `ObservationMapper.java`, `AggregateDecoder.java` | `AggregateDecoderTest.decodesValidAggregateToPlainStrings` (no test pins a removed `toString()`; VERIFY by mutation) |
| Testing | `TopologyTestDriver` proves logic in memory; ITs prove wiring and real brokers; mutation proves a test can fail | `TelemetryTopologyTest`, `StreamProcessorIT` | see section 14 |

**Numbers to have ready (all targets, not measurements, unless filled):**

| Number | Value | Kind |
|---|---|---|
| Peak events/s | 1 M/s | assumption (10 M viewers ÷ 10 s) |
| Window / grace / floor | 10 s / 5 s / 15 s | configured target |
| Raw / aggregate partitions | 12 / 6 | configured (local) |
| Batch / in-flight / permit wait | 500 / 64 / 250 ms | configured target |
| BatchWriter | 200 records / 100 ms / buffer 2000 / offer 1 s / 3 attempts | configured target |
| Overview / coalesce / heartbeat | 300 ms / 250 ms / 15 s | configured target |
| Executor / queue | 8 / 16 | configured target |
| Sustained throughput | 1,500 events/s sustained for 90 s, 0 rejected (offered rate, not a ceiling: ingest's tenant quota is 2,000 events/s) | measured (Phase 3) |
| Ingest ceiling, default quota | last clean 2,500 events/s; 33.4% 429 at 3,000 events/s | measured (T9-11; one laptop, loadgen co-located, one run) |
| Ingest-only ceiling, quota lifted | clean at 40,000 events/s; 503 from 45,000 (11.8% errors, p99 595.1 ms at that step); 503 mechanism not proven (breaker ruled out, 0 failed calls; the 64-permit bound is the likely cause) | measured (T9-11; same caveats) |
| p99 ingest latency | p99 68.7 ms (p50 50.0 ms, p95 57.0 ms) at 1,500 events/s (Phase 3); 76.5 ms at 1,000/s and 70.9 / 59.0 ms at 2,000/s (T9-13) | measured |
| Overview read latency | p99 27 to 30 ms at about 52 req/s alongside the write load | measured (T9-13; light, unsaturated; which store served the reads not checked) |
| Freshness per closed window | 5.2 to 5.4 s after window end (the loadgen's own histogram p99 is unreliable back-to-back) | measured (Postgres, T9-13) |
| End-to-end accounting | 2,000 and 30,000 events with retries re-sent: every closed window exact | measured (T9-12) |
| Processor `kill -9` | no duplicates; 1,482 of 4,000 and 2,554 of 8,000 on-time events dropped | measured (T9-34; fails) |
