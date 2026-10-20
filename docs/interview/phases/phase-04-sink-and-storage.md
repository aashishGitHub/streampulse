# Phase 4 — Sink and storage

**Status:** done. Not built: DynamoDB and S3 sinks (the Terraform modules exist but nothing writes to
them), a Redis projection rebuild job. Not measured: flush latency, peak lag and drain time.

**One line to say first:** "The sink makes at-least-once delivery safe: write to Postgres idempotently
and durably, update Redis best-effort, and only then acknowledge the offset."

## Code map

| Thing | Where |
|---|---|
| Batch listener: submit, await, then ack | `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java` |
| The batch writer (queue, flusher, lock, poison vs outage) | `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java` |
| Idempotent upsert | `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java` |
| Schema and primary key | `services/sink-writer/src/main/resources/schema.sql` |
| Config (batch size, wait, queue, timeouts) | `services/sink-writer/src/main/resources/application.yml` |
| No-loss, no-double-count test | `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/NoLossNoDoubleCountIT.java` |
| Decision | `docs/adr/0013-sink-durable-then-commit.md` |

## Flow

```
telemetry.agg.v1 ─ poll up to 500 ─▶ AggregateBatchListener
   decode each ─▶ BatchWriter.submit  (bounded queue 2000, offer timeout 1 s)
   flusher thread: up to 200 rows or 100 ms ─▶ Postgres UPSERT (one transaction)
                                                   │ success
   Redis projection (HSET latest + XADD live stream, best-effort, failures counted)
   all futures complete within the 30 s poll budget ─▶ ack the offsets (listener thread)
```

---

## Beginner

### B1. What is the primary key of the aggregates table and why does it matter?

**Answer:** The primary key of `metric_window` is `(tenant_id, title_id, region, metric, window_start)`, which is the identity of one aggregate: one series in one ten-second window. It matters because the sink upserts on that key, so receiving the same aggregate a second time updates the existing row instead of adding a second one. That is how at-least-once delivery from Kafka becomes safe: a redelivered record lands on the same row. The upsert also carries a guard, so it only overwrites a row when the incoming `computed_at` is not older than the stored one. An empty title is stored as the empty string rather than null, so that column can sit inside the key.

**Example:** `services/sink-writer/src/main/resources/schema.sql:13-14`
```sql
    computed_at   timestamptz      NOT NULL,
    PRIMARY KEY (tenant_id, title_id, region, metric, window_start)
```
Point at the key: it holds the five identifying columns, and `computed_at` is deliberately not part of it, so a recomputed value replaces the row instead of creating a neighbour.

**Code:** `services/sink-writer/src/main/resources/schema.sql:4-14` (the table), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:20-31` (the upsert that uses the key)

### B2. What is an upsert?

**Answer:** An upsert is a single statement that inserts a row, or updates the existing row if the key is already there. In Postgres that is `INSERT ... ON CONFLICT (key) DO UPDATE`. It matters here because the database does the check and the write as one atomic step. If the application did a select first and then an insert, two writers could both see no row and both insert, or a redelivered record could race the original. In the sink the statement also has a `WHERE` on the update branch, so a row is replaced only when the new computation is not older than the stored one. The batch of rows goes through JDBC batching inside one transaction.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:22-30`
```java
INSERT INTO metric_window
...
ON CONFLICT (tenant_id, title_id, region, metric, window_start) DO UPDATE SET
...
WHERE metric_window.computed_at <= EXCLUDED.computed_at
```
The conflict target is the primary key from B1, and the `WHERE` is the freshness guard that makes replays and reordering harmless.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:20-31` (the statement), `services/sink-writer/src/test/java/com/streampulse/sink/adapter/out/postgres/MetricWindowUpsertIT.java:82` (same record twice gives one row)

### B3. Why is auto-commit of offsets turned off?

**Answer:** With auto-commit on, the Kafka client commits offsets on a timer, whether or not my write to Postgres has finished. If it commits and the process then dies before the write, those records are gone for good, because the group believes they were handled. So I turned it off and set the listener to manual acknowledgement: the offset is committed only after the durable write has succeeded. The worst case becomes a replay, which the idempotent upsert absorbs. The consumer also uses `read_committed`, which makes it skip records from aborted transactions of the stream processor, because that processor writes with exactly-once v2.

**Example:** `services/sink-writer/src/main/resources/application.yml:38-54`
```yaml
      # The offset is committed by the listener, after the durable write, never on a timer.
      enable-auto-commit: false
...
      # Skip records of aborted producer transactions (the stream processor is exactly-once v2).
      isolation-level: read_committed
...
      ack-mode: manual
```
The comments carry the reason; `SinkConfig` also sets the manual ack mode in code, so a stray property cannot switch auto-acknowledge back on.

**Code:** `services/sink-writer/src/main/resources/application.yml:38-54` (the three settings), `services/sink-writer/src/main/java/com/streampulse/sink/config/SinkConfig.java:140` (ack mode set explicitly in code)

### B4. What is a dead-letter topic doing in the sink?

**Answer:** The dead-letter topic is where a record goes when it can never be processed, so it does not block the partition behind it. In the sink there are two ways in. A record that cannot be decoded, or that decodes to something the store would always reject, is rejected by the listener and sent to `telemetry.agg.v1.dlt` with its original key and bytes and the reason in a header. A record that fails every individual write inside the batch writer is sent there too. If the dead-letter send itself fails, the listener throws instead of acknowledging, because acknowledging past a record I could not park would lose it.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java:83-92`
```java
    private void deadLetter(ConsumerRecord<String, byte[]> record, String reason) {
        try {
            deadLetters.deadLetter(AggregateRecord.undecodable(record.key(), record.value()), reason);
            metrics.deadLettered();
            log.warn("Dead-lettered {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);
        } catch (Exception e) {
            // Not skipped: acknowledging past a record we could not park would lose it.
            throw new SinkUnavailableException("could not dead-letter a rejected record", e);
        }
    }
```
The catch block is the point: a failed dead-letter send turns into an exception, so nothing gets acknowledged.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java:83-92` (the listener's dead-letter path), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/kafka/KafkaDeadLetterSink.java:28-34` (the send, which waits for the broker), `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/PoisonRecordIT.java:79-113` (garbage bytes parked, healthy records stored)

### B5. Why does the sink write in batches instead of one row per record?

**Answer:** Writing one row at a time would pay a network round trip and a transaction commit per record. The sink collects records in a bounded queue and a single flusher thread writes them in groups of up to 200 rows, or whatever has arrived after 100 milliseconds, whichever comes first. The whole group goes to Postgres in one transaction, so it commits once. The queue holds 2,000 records and a submit waits at most one second for space, so a slow database pushes back on the Kafka listener instead of growing memory without limit. These batch numbers are targets I chose; I did not measure flush latency, peak lag or drain time, so I cannot quote a throughput gain.

**Example:** `services/sink-writer/src/main/resources/application.yml:91-97`
```yaml
    batch:
      max-size: 200
      max-wait: 100ms
      queue-capacity: 2000
      flush-attempts: 3
      retry-backoff: 50ms
      close-timeout: 20s
```
These are the knobs: size and wait decide when a batch flushes, and the queue capacity is the back-pressure limit.

**Code:** `services/sink-writer/src/main/resources/application.yml:91-99` (batch config and offer timeout), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:41-55` (one transaction per batch), `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:190-211` (collect until full or max wait)

---

## Mid

### M1. Walk through the acknowledgement order and say why commit-first is wrong.

**Answer:** For each poll, the listener decodes every record and submits the valid ones to the batch writer, which returns a future per record. It then waits on all of those futures within a 30 second poll budget, runs a pre-ack hook, and only then acknowledges the offsets, on the listener thread, because the Kafka consumer is not thread-safe and the flusher thread must not touch it. Commit-first is wrong because a crash between the commit and the write loses everything in between, and ADR-0013 rejects it for that reason. Write-then-commit can only replay after a crash, and the upsert absorbs replays. If a write fails or the budget runs out, the listener throws and nothing is acknowledged.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java:78-80`
```java
        awaitAll(pending, deadline);
        preAck.beforeAck();
        ack.acknowledge();
```
The order of these three lines is the guarantee: the acknowledgement cannot be reached unless every future completed.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java:66-81` (the whole poll), `docs/adr/0013-sink-durable-then-commit.md:27-30` (the decision), `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListenerTest.java:106` (no ack while the writes are blocked)

**If they push:** "What if the write takes longer than the budget?" The listener throws `SinkUnavailableException` and does not acknowledge, and `max.poll.interval.ms` is set to 300,000 so the broker does not evict the consumer while the error handler backs off; a test covers the budget case.

### M2. How does the batch writer tell a poison record from an outage?

**Answer:** When a batch fails, the flusher retries it up to three times with a doubling backoff starting at 50 milliseconds. If it still fails, it stops treating the batch as a unit and writes the records one at a time. If some of those single writes succeed, the database is clearly up, so the ones that failed are poison and they go to the dead-letter topic. If none succeed, I cannot tell a bad record from a dead store, and dead-lettering healthy records because Postgres was down would lose good data, so every future fails, nothing is dead-lettered, and the listener does not acknowledge. The known gap, from the ADR, is a poison record that sits alone in its batch: it looks exactly like an outage. The decoder rejects the common deterministic causes first to keep that rare.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:273-279`
```java
        if (failed.size() == batch.size()) {
            failAll(batch, batchFailure);
            return;
        }
        for (Failed<T> f : failed) {
            deadLetter(f);
        }
```
The comparison of failures with batch size is the whole distinction: all failed means outage, some failed means poison.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:213-243` (retry loop), `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:255-280` (isolation), `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java:262` (poison isolated), `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java:282` (outage, nothing dead-lettered)

**If they push:** "What does an outage cost?" Isolation makes one connection attempt per record, and the Hikari connection timeout of 2 seconds bounds each attempt, so a full outage makes a failing batch take a while to give up.

### M3. Describe the shutdown race the lock prevents.

**Answer:** Without the lock there is a window between `submit` checking that the writer is open and `submit` putting the record on the queue. If `close()` runs in that window, it marks the writer closed, the flusher sees an empty queue and exits, and then `submit` enqueues a record that nothing will ever write, even though the caller was told it was accepted. I close the window with a `ReentrantReadWriteLock`: submitters take the read lock around the check and the enqueue, so many can run together, and `close()` takes the write lock to set the flag, which means it waits for any submit already past the check. I chose that over `synchronized` because a submit can block in `offer`, and `synchronized` would pin a virtual thread's carrier while it blocks.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:125-133`
```java
        lock.readLock().lock();
        try {
            if (closed) {
                return rejected(SubmissionRejectedException.Reason.CLOSED);
            }
            if (!queue.offer(new Pending<>(value, future), offerTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return rejected(SubmissionRejectedException.Reason.BUFFER_FULL);
            }
            return future;
```
The closed check and the `offer` sit under one read lock, so `close()` cannot slip in between them.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:118-140` (submit), `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java:146-153` (close takes the write lock), `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java:207` (the deterministic test)

**If they push:** "Does the read lock make submitters wait for each other?" No, a read lock is shared, so submitters run in parallel; only `close()` excludes them, and a close that arrives while a submit is blocked in `offer` waits up to the offer timeout.

### M4. Why did a randomised race test stay green with the lock removed?

**Answer:** The bad interleaving is a few nanoseconds wide: a submit has to pass the open check and then lose the race against `close()` and the flusher's exit before it enqueues. A randomised test with many threads and a spin of random length only hits that by luck, so a hundred green runs showed the interleaving was not hit, not that it cannot happen. When the lock was removed, `@RepeatedTest(100)` stayed green, and only a deterministic test went red. That test hands the writer a queue whose `offer` stalls, so a submit is held between its check and its enqueue while `close()` is called. The rule I took is to force the interleaving you fear instead of hoping a random run finds it. This mutation result was an agent's report; I re-ran the tests myself.

**Example:** `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java:210-219`
```java
        BlockingQueue<BatchWriter.Pending<String>> stallingQueue = new ArrayBlockingQueue<>(10) {
            @Override
            public boolean offer(BatchWriter.Pending<String> e, long timeout, TimeUnit unit)
                    throws InterruptedException {
                inOffer.countDown();
                releaseOffer.await();
                return super.offer(e, timeout, unit);
            }
        };
```
The overridden `offer` announces that a submit is inside it and then waits, which pins the exact moment the race needs.

**Code:** `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java:155-157` (the randomised test), `services/sink-writer/src/test/java/com/streampulse/sink/application/batch/BatchWriterTest.java:207-243` (the deterministic one), `MILESTONES.md:1334-1342` (the finding), `docs/interview/05-what-broke.md:105` (story 7)

**If they push:** "Why can the test inject the queue?" The package-private `start` overload accepts a queue, with a comment saying a test can only hold a submit between its check and enqueue deterministically by owning the queue.

### M5. Is the Redis write part of the guarantee?

**Answer:** No. Postgres is the source of truth and Redis is a derived projection that can be rebuilt from it, so the sink writes Redis after Postgres and treats a Redis failure as a counted warning, not as a reason to withhold the acknowledgement. Writing Redis first would let a reader see a value that Postgres never stored. The projection sends the latest-window hash writes and the live-stream adds in one pipelined round trip per batch, but a pipeline is not atomic, so Redis can be half applied or repeat an entry after a redelivery. I accept that because the next write or a rebuild repairs it. The gap is that no rebuild job exists, so today a lagging Redis stays behind until newer data arrives.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/application/AggregateSink.java:45-53`
```java
        try {
            projection.project(windows);
        } catch (RuntimeException e) {
            // Swallowed on purpose: Postgres already holds the data, Redis can be rebuilt from it,
            // and failing here would hold back the acknowledgement for a store that is not the
            // source of truth.
            metrics.projectionFailed();
            log.warn("Projection of {} window(s) failed; Postgres is current, Redis is behind", windows.size(), e);
        }
```
Point at the order in `write`: the store call comes first and can throw, the projection comes second and cannot.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/application/AggregateSink.java:36-54` (store then projection), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/redis/RedisHotProjection.java:36-52` (the pipelined writes), `services/sink-writer/src/test/java/com/streampulse/sink/application/AggregateSinkTest.java:86` (projection failure does not fail the batch)

**If they push:** "What does the live feed see after a redelivery?" It may see the same entry twice, because the stream add is not idempotent; the integration test asserts the stream holds at least as many entries as aggregates, not exactly that many.

### M6. How does the sink shut down without losing the records already accepted?

**Answer:** The order matters. When the application stops, the Kafka listener container must stop first so that no new poll arrives. Only then is the batch writer closed, which stops intake, writes everything already in the queue, and waits for the flusher up to a close timeout of 20 seconds, interrupting it if it overruns. If I closed the writer first, it would reject records the container was still handing it. I get that order from a `SmartLifecycle` bean whose phase is 1,000 below the listener container's phase, since lifecycle beans stop in descending phase order. The database and Redis connections are closed afterwards, at bean destruction. As I read the design, a record that could not be written before the timeout fails its future, so the listener would not acknowledge it and it would be redelivered; I have not run a shutdown under load to confirm that.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/lifecycle/BatchWriterLifecycle.java:48-52`
```java
    public void stop() {
        running = false;
        log.info("Closing the batch writer ({} record(s) queued)", writer.queueDepth());
        writer.close();
    }
```
The `stop` is small on purpose; the correctness is in the phase constant on line 28 that places it after the container.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/lifecycle/BatchWriterLifecycle.java:28` (the phase), `services/sink-writer/src/main/java/com/streampulse/sink/config/SinkConfig.java:77-93` (the writer bean, with its destroy method disabled), `services/sink-writer/src/test/java/com/streampulse/sink/lifecycle/BatchWriterLifecycleTest.java:58` (stops after the container)

---

## Senior

### S1. Consumer lag on the aggregate topic is climbing. Diagnose it.

**Answer:** I would first ask whether every partition is behind or only one, because a single partition points at a hot key and every partition points at throughput. Then I would look at the batch queue depth gauge. A depth near the 2,000 capacity means Postgres is the slow part and the listener is being pushed back; an empty queue means the writer is starved, so I would suspect the listener, a rebalance or the producer side. The consumer concurrency is 3 against 6 partitions, so I can double the consumer threads before partitions become the ceiling. One trap: on my laptop the group sat at a lag of 1 on each of the 6 partitions at rest, which is consistent with the transaction commit marker that exactly-once writes, though I did not dump the log to confirm it. So alert on lag that grows, not on lag greater than zero. I never measured peak lag or drain time.

**Example:** `services/sink-writer/src/main/resources/application.yml:53-55`
```yaml
      type: batch
      ack-mode: manual
      concurrency: 3
```
Three consumer threads each own two of the six partitions, which is where the 2x headroom comes from.

**Code:** `services/sink-writer/src/main/resources/application.yml:42-55` (poll size, listener and concurrency), `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java:53` (the queue depth gauge), `MILESTONES.md:1401-1403` (the lag of 1 at rest)

**If they push:** "Why would a slow Postgres show up as lag?" A full queue makes `submit` time out after one second, the listener throws and does not acknowledge, and the group stops advancing; the ADR calls this honest back-pressure.

### S2. How do you show the sink neither loses nor double-counts?

**Answer:** I inject a failure in the one crash window that matters, after the durable write and before the acknowledgement. A test-only pre-ack hook throws three times. The container then redelivers, the second write has to change nothing, and the committed offsets must still end at exactly the number of records produced. The test sends 60 aggregates and asserts 60 rows with unchanged values and computed-at times, committed offsets summing to 60, an empty dead-letter topic, and a freshness timer count above 60, which proves records really were written more than once. The test passed on its first run, so the agent that wrote it made the upsert non-idempotent to see it fail, and it did; that is from the agent's report, and I re-ran the tests. The limit is that it injects an exception, not a JVM kill, and it does not cover Postgres or the broker failing mid-write.

**Example:** `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/NoLossNoDoubleCountIT.java:93-102`
```java
        PreAckHook failAfterTheWriteBeforeTheAck() {
            return () -> {
                if (FAILURES_LEFT.getAndDecrement() > 0) {
                    FAILURES_INJECTED.incrementAndGet();
                    throw new IllegalStateException("injected: after the durable write, before the ack");
                }
            };
        }
```
This bean replaces the production no-op hook, so the failure lands exactly between the write and the ack.

**Code:** `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/NoLossNoDoubleCountIT.java:125-175` (the assertions), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/PreAckHook.java:10-16` (the seam), `MILESTONES.md:1334-1342` (the recorded result and the mutation)

**If they push:** "Why is this safe against a real crash?" The test exercises the same redelivery path a crash would take, and the idempotent upsert is what makes that path safe; the test comment states it does not prove the crash itself.

### S3. The upsert uses `computed_at <=`. Why not `<`?

**Answer:** The `WHERE` on the update branch decides whether an incoming row replaces the stored one. With `<=`, an older computation never overwrites a newer one, so out-of-order replays cannot move a value backwards, and a replay of the very same record is accepted as a no-op instead of being treated as stale. The code comment and the ADR both call that deliberate. I should be exact about what differs: for an identical record the end state is the same either way, and the only case where `<=` and `<` behave differently is two different values with the same `computed_at`, where `<=` lets the later write win. I did not find a test for that case, so that part is my reading of the SQL, not something a test proves. The whole batch is committed in one transaction.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:19-30`
```java
    // `<=` not `<`: a replay of the very same record must be accepted as a no-op, not refused.
...
            WHERE metric_window.computed_at <= EXCLUDED.computed_at
```
The comment above the constant states the intent; the last line is the guard.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java:19-31` (the statement), `services/sink-writer/src/test/java/com/streampulse/sink/adapter/out/postgres/MetricWindowUpsertIT.java:93` (older does not overwrite newer), `services/sink-writer/src/test/java/com/streampulse/sink/adapter/out/postgres/MetricWindowUpsertIT.java:113` (same key twice in a batch, either order), `docs/adr/0013-sink-durable-then-commit.md:53-55` (the stated trade-off)

**If they push:** "Does a stale row raise an error?" No: the older-does-not-overwrite test calls `upsertAll` twice with no exception, so a skipped update is silent.

### S4. Where could the aggregate be wrong even though the sink is correct?

**Answer:** The sink faithfully stores whatever the stream processor emitted, so the error can sit upstream. In my local run I killed the processor with `kill -9` partway through a run, restarted it, and compared every closed window in Postgres with what I had sent. There were no duplicates, but windows spanning the 40 to 50 second outage held only part of their events, 1,482 of 4,000 and then 2,554 of 8,000, because the processor dropped them as late records for an expired window. The sink stored short windows correctly and nothing flagged them. That is why the accounting check compares what was sent with what was stored, instead of comparing the sink with its own input. This is a local result from one laptop and one broker, and I have not fixed the cause; every fix changes a documented guarantee.

**Example:** `scripts/accounting-check.py:7-10`
```python
compares EVERY closed window in Postgres with what was sent.

It answers: did any event go missing, or get counted twice, anywhere between HTTP and the store?
It does not prove anything about Redis or the SSE feed.
```
The check starts at the sender and ends at the store, so it can see losses that happen before the sink.

**Code:** `scripts/accounting-check.py:1-10` (what it checks and what it does not), `MILESTONES.md:1903` (the crash run), `docs/interview/05-what-broke.md:105` (story 3)

**If they push:** "Would the sink's own metrics have caught it?" No, the sink only sees what arrives, and the write-up notes that the stream processor had no late-drop counter of its own.

### S5. What does the Kafka container do when the listener throws, and why must it never skip a record?

**Answer:** When the listener throws, the container's error handler redelivers the whole poll after a backoff. I configured that backoff to be exponential, starting at one second, doubling up to a 30 second cap, with no limit on attempts or elapsed time, so an outage is waited out instead of given up on. The handler normally calls a recoverer to skip a record after retries run out, and for this sink skipping is exactly what must never happen, so my recoverer throws instead. I also cleared the exception classifications so that every exception type is retried, because Spring otherwise treats some as not retryable and goes straight to the recoverer. Poison records are handled earlier, by the listener and the batch writer. The cost is that a permanent failure would hold a partition, so lag growth is the signal I would alert on; I did not build that alert. `max.poll.interval.ms` is 300,000 to outlast the 30 second poll budget, the 30 second backoff and the worst flush retry.

**Example:** `services/sink-writer/src/main/java/com/streampulse/sink/config/SinkConfig.java:152-165`
```java
    static DefaultErrorHandler neverGiveUpErrorHandler(BackOff backOff) {
        // The recoverer is what DefaultErrorHandler calls to SKIP a record. Skipping is exactly what
        // this sink must never do, so it refuses; poison records are dead-lettered by the listener.
        var handler = new DefaultErrorHandler(
                (record, exception) -> {
                    throw new IllegalStateException(
                            "refusing to skip " + record.topic() + "-" + record.partition() + "@" + record.offset(),
                            exception);
                },
                backOff);
        // Spring treats some exception types as "not retryable" and skips straight to the recoverer.
        // Here every exception is retried.
        handler.setClassifications(Map.of(), true);
        return handler;
```
The recoverer lambda throws, and the empty classification map with `true` makes every exception retryable.

**Code:** `services/sink-writer/src/main/java/com/streampulse/sink/config/SinkConfig.java:145-166` (backoff and handler), `services/sink-writer/src/main/resources/application.yml:49-51` (why `max.poll.interval.ms` is 300,000), `services/sink-writer/src/test/java/com/streampulse/sink/config/RetryPolicyTest.java:14-28` (backoff never returns STOP and is capped at 30 seconds)

**If they push:** "What does a poison record alone in its batch do under this handler?" As I read it, the writer treats it as an outage, the listener throws, and the handler retries without skipping, so it holds that partition until someone intervenes; I did not find a test of that exact case.

---

## Say this honestly

- DynamoDB and S3 sinks are not built; the Terraform for them is.
- Flush latency, peak lag and drain time were never measured.
- The batch numbers (200 rows, 100 ms, queue 2,000, 1 s offer timeout) are targets.
- One broker, so consumer-group behaviour under node loss is untested.

## Stories and numbers

[What broke](../05-what-broke.md): stories 3, 7, 10. [Numbers](../06-numbers.md): accounting rows.
