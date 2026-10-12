# Phase 4 — Sink writer: Design

## Flow of control and data

```
telemetry.agg.v1 (6p, key=tenant:title:region:metric:windowStart)
   │  bytes   (3 consumer threads, one batch listener each, manual ack)
   ▼
AggregateBatchListener.onBatch(≤500 records, Acknowledgment)      [listener thread]
   │  decode each ── undecodable ──▶ telemetry.agg.v1.dlt  (original bytes + key + dlt-reason)
   │  submit each → BatchWriter.submit(record)  → CompletableFuture<Void>
   │  allOf(futures).get(budget)                                   ← BLOCKS the listener thread
   │  ack.acknowledge()                                            ← offsets commit HERE, never elsewhere
   ▼
BatchWriter<AggregateRecord>      bounded queue (2000) ──▶ ONE thread "batch-flusher"
   │  flush at 200 records or 100 ms after the first
   ▼
AggregateSink.write(batch)                                         [flusher thread]
   1. MetricWindowStore.upsertAll   one transaction, idempotent upsert        ← DURABLE
   2. freshness timer = Clock.now − windowEnd
   3. MetricProjection.project      pipelined HSET + XADD MAXLEN ~10000       ← derived, best effort
   │
   ├─ batch fails ─▶ retry ×3 with backoff ─▶ still fails ─▶ write ONE AT A TIME
   │        some succeed, some fail: the failures are POISON ─▶ DeadLetterSink (and the future completes)
   │        every record fails: OUTAGE ─▶ futures fail, nothing dead-lettered
   ▼
future completes ─▶ listener unblocks ─▶ ack
future fails     ─▶ listener throws    ─▶ DefaultErrorHandler: infinite exponential backoff, redeliver
```

The Kafka consumer is not thread-safe, so the flusher never touches it: it completes futures,
and the listener thread, which owns the consumer, does the acknowledging.

## Why this is correct

Write order is Postgres, then Redis, then ack. A crash between any two steps only ever causes
a **redelivery**, never a loss, and redelivery is harmless because the upsert is idempotent
and ordered by `computed_at` (see `hot-store-contract.md`). ADR-0013.

## Hexagonal layout

```
domain/        MetricWindow, MetricWindowStore (port), MetricProjection (port), AggregateRecord
application/
  batch/       BatchWriter<T>, BatchSink<T>, DeadLetterSink<T>, BatchWriterMetrics  ← framework-free
  AggregateSink                 the BatchSink: store, freshness, projection
adapter/in/kafka/   AggregateBatchListener, AggregateDecoder
adapter/out/{postgres,redis,kafka}/   JdbcMetricWindowStore, RedisHotProjection, KafkaDeadLetterSink
config/        wiring, properties, Micrometer metrics, listener container factory
lifecycle/     BatchWriterLifecycle (stops AFTER the listener container)
```

`BatchWriter` is generic and knows no Kafka, Spring, JDBC or Redis, so its concurrency
behaviour is tested with a fake sink and no containers.

## BatchWriter concurrency design

- `submit` takes the **read** lock, checks `closed`, then offers with a timeout. `close` takes
  the **write** lock and sets `closed`. Without the lock a submit can pass the open-check, the
  flusher can see an empty queue and exit, and the late record sits in the queue forever: an
  accepted record that is never written. `ReentrantReadWriteLock`, not `synchronized`, which
  pins a virtual thread's carrier while it blocks.
- Deadlines use `System.nanoTime` (monotonic), not the injected `Clock`: wall time can jump.
- The flusher is built by a static `start` factory so no `this` escapes a constructor.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Batch reaches N records | flushed at once | `flushesWhenBatchSizeIsReached` |
| Batch stays below N | flushed after max wait | `flushesAfterMaxWaitWhenBatchIsNotFull` |
| Buffer full | submit fails after the offer timeout | `fullQueueRejectsWithinTheOfferTimeout` |
| Close with records queued | all written before close returns | `closeDrainsEverythingAccepted` |
| Close with a partial batch waiting out its max wait | flushed at once, close returns promptly | `closeFlushesAPartialBatchWithoutWaitingForMaxWait` |
| Submit races close | accepted implies written | `acceptedImpliesWrittenUnderConcurrentSubmitAndClose` (×100, randomised), `closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck` (deterministic: the queue stalls the submit; this is the one that fails without the lock) |
| Submit after close | failed future | `submitAfterCloseFails` |
| Transient store error | retried, then succeeds | `transientFailureIsRetriedThenSucceeds` |
| One poison record | dead-lettered; others written | `poisonRecordIsIsolatedToTheDeadLetterSinkWhileOthersAreWritten` |
| Store down | futures fail; nothing dead-lettered | `outageFailsFuturesWithoutDeadLettering` |
| Dead-letter sink down | only that record's future fails | `deadLetterSinkFailureFailsOnlyThatRecord` |
| Queue depth / flush latency | reported | `metricsReportQueueDepthAndFlushLatency` |
| Undecodable bytes | dead-lettered, never retried | `undecodableRecordIsDeadLetteredAndTheRestAreWritten`, `PoisonRecordIT` |
| Store fails | listener throws, no ack | `storeFailureMeansNoAck` |
| Poll outlives its budget | listener throws, no ack | `budgetExceededMeansNoAck` |
| Dead-letter topic down for an undecodable record | listener throws, no ack, record not skipped | `deadLetterFailureMeansNoAck` |
| Redelivery backoff | grows, capped, never gives up | `backoffNeverGivesUp` |
| Listener id replaces the group id | group stays `sink-writer` | `SinkWiringTest.consumerSettings` |
| Ack only after write | ack follows future completion | `acksOnlyAfterEveryRecordIsWritten` |
| Redis fails | swallowed and counted; Postgres write stands | `projectionFailureDoesNotFailTheBatch` |
| Postgres written, ack path fails | redelivered; N rows, same values, offset = N | `NoLossNoDoubleCountIT` |
| Same record twice / older after newer | one row; newer kept | `MetricWindowUpsertIT` |
| Listener stops before writer | writer closes after container | `lifecycleStopsAfterTheListenerContainer` (a stand-in container with the real phase constant, not the real container) |
| Domain/batch leak a framework type | build fails | `SinkArchitectureRulesTest` |
| Context does not assemble | fails in ms, no Docker | `SinkWiringTest` |

## Known gaps (stated, not hidden)

- A record that is poison but arrives **alone** in its batch is indistinguishable from an
  outage by the "every record failed" rule. The decoder rejects the common deterministic
  causes (blank tenant, unknown metric, NUL characters, inverted window) before the writer.
- On a real outage, isolation costs one connection attempt per record; the pool's connection
  timeout is set low to bound it. A circuit breaker would cap it.
- `latest` in Redis is written per batch by greatest window start; across partitions an older
  window can briefly overwrite a newer one after a redelivery. The durable store is correct.
- The live feed stream may contain a record twice after a redelivery.
- The Redis projection has no retry policy of its own: a 2 s command timeout, then the failure is
  counted (`streampulse.sink.projection.failures`) and swallowed. Nothing rebuilds Redis yet; that
  is a later job.

## Decisions

- ADR-0013: durable-then-commit; poison records versus outages.
- ADR-0012: why the sink must be idempotent (exactly-once stops at Kafka).
