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

**A strong answer contains**
- `(tenant_id, title_id, region, metric, window_start)`.
- The sink upserts on that key, so replaying the same aggregate is a no-op; that is how at-least-once
  delivery becomes safe.
- It only overwrites when the incoming `computed_at` is not older.

**Open** `services/sink-writer/src/main/resources/schema.sql`

### B2. What is an upsert?

**A strong answer contains**
- Insert, or update if the key already exists, in one statement (`ON CONFLICT ... DO UPDATE`).
- Done in the database so it is atomic; check-then-insert in the application would race.

**Open** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java`

### B3. Why is auto-commit of offsets turned off?

**A strong answer contains**
- Auto-commit acknowledges on a timer, regardless of whether the write succeeded. A crash after the
  commit but before the write loses data.
- Manual ack runs only after the durable write.
- The consumer also uses `read_committed`, so it skips records from aborted processor transactions.

**Open** `services/sink-writer/src/main/resources/application.yml`

### B4. What is a dead-letter topic doing in the sink?

**A strong answer contains**
- Records that cannot be decoded, or that fail every write as individuals, are sent aside with their
  original bytes so they do not block the partition.
- If the dead-letter send itself fails, the listener throws instead of acknowledging.

**Open** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java`

---

## Mid

### M1. Walk through the acknowledgement order and say why commit-first is wrong.

**A strong answer contains**
- Submit every record, wait for all futures inside the poll budget, run the pre-ack hook, then ack on
  the listener thread (the consumer is not thread-safe).
- Commit-first loses data on a crash between commit and write; ADR-0013 rejects it. Write-then-commit
  can only *replay*, and the upsert absorbs replays.

**Open** `docs/adr/0013-sink-durable-then-commit.md`

### M2. How does the batch writer tell a poison record from an outage?

**A strong answer contains**
- After 3 attempts with backoff it writes records one at a time. If some succeed, the failures are
  poison and go to the DLT. If none succeed, it is an outage: everything fails and nothing is
  dead-lettered, so the listener retries.
- Known gap: a single poison record in an otherwise failing batch looks like an outage.

**Open** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java`

### M3. Describe the shutdown race the lock prevents.

**A strong answer contains**
- Without it: `submit` sees "open", `close()` marks closed, the flusher sees an empty queue and exits,
  then `submit` enqueues a record nothing will ever write.
- A read lock around the check-and-enqueue and a write lock in `close()` close the window.
- It is a `ReentrantReadWriteLock`, not `synchronized`, which would pin a virtual thread's carrier.

**Open** `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java`

### M4. Why did a randomised race test stay green with the lock removed?

**A strong answer contains**
- The bad interleaving is nanoseconds wide; 100 green runs show it was not hit, not that it cannot
  happen.
- Only a deterministic test that stalls `offer` at the right moment went red.
- Rule: force the interleaving you fear instead of hoping a random run finds it. (This result was an
  agent's report; I re-ran the tests.)

**Open** `docs/interview/05-what-broke.md` story 7.

### M5. Is the Redis write part of the guarantee?

**A strong answer contains**
- No. Postgres is the source of truth; Redis is a rebuildable projection and its failures are swallowed
  and counted.
- The two Redis commands are pipelined but not atomic, so Redis can repeat or lag; accepted because it
  is rebuildable.
- Gap: no rebuild job exists.

**Open** `docs/adr/0013-sink-durable-then-commit.md`

---

## Senior

### S1. Consumer lag on the aggregate topic is climbing. Diagnose it.

**A strong answer contains**
- Is it every partition or one? One partition points to a hot key.
- Queue depth near 2,000 means Postgres is slow; an empty queue means starvation or rebalancing.
- Ceiling: concurrency 3 against 6 partitions gives 2x headroom.
- A lag of 1 at rest is the transaction commit marker (consistent, not confirmed by dumping the log),
  so alert on growth, not on "greater than zero".

**Open** `services/sink-writer/src/main/resources/application.yml`

### S2. How do you show the sink neither loses nor double-counts?

**A strong answer contains**
- Inject a failure *after* the durable write and *before* the ack, three times; the test then asserts
  60 rows with unchanged values, committed offsets summing to 60, and an empty DLT.
- It passed first time, so the upsert was made non-idempotent on purpose to prove the test can fail.
- Limit: it injects an exception, not a JVM kill.

**Open** `services/sink-writer/src/test/java/com/streampulse/sink/adapter/in/kafka/NoLossNoDoubleCountIT.java`

### S3. The upsert uses `computed_at <=`. Why not `<`?

**A strong answer contains**
- Replaying the *same* record must be a no-op, not a rejection; `<=` lets an identical record "win"
  harmlessly.
- An older record never overwrites a newer one, so out-of-order replays are safe.
- The whole batch commits in one transaction.

**Open** `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java`

### S4. Where could the aggregate be wrong even though the sink is correct?

**A strong answer contains**
- Upstream: the processor dropped on-time events after a restart (Phase 3 S4). The sink faithfully
  stored a short window and nothing flagged it.
- This is why the end-to-end accounting check compares *sent* with *stored*, not the sink with its
  own input.

**Open** `scripts/accounting-check.py`

---

## Say this honestly

- DynamoDB and S3 sinks are not built; the Terraform for them is.
- Flush latency, peak lag and drain time were never measured.
- The batch numbers (200 rows, 100 ms, queue 2,000, 1 s offer timeout) are targets.
- One broker, so consumer-group behaviour under node loss is untested.

## Stories and numbers

[What broke](../05-what-broke.md): stories 3, 7, 10. [Numbers](../06-numbers.md): accounting rows.
