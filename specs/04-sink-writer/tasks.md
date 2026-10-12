# Phase 4 — Sink writer: Tasks

Each line: failing test first, then code. Tick only with passing output in MILESTONES.md.

## Day 3
- [x] T4-01 spec, design, ADR-0013 written before any code
- [x] T4-02 `BatchWriterTest` (size, time, full, close, retry, poison, outage, metrics) fails to compile → `BatchWriter`, `BatchSink`, `DeadLetterSink`, `BatchWriterMetrics`
- [x] T4-03 `acceptedImpliesWrittenUnderConcurrentSubmitAndClose` ×100 → read/write lock. Mutation check, as actually observed: with the lock removed the randomised ×100 test stayed GREEN (16 and 48 threads); only the deterministic `closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck` (stalls `offer`) went red. The randomised test is a smoke test, not the proof.
- [x] T4-04 `SinkArchitectureRulesTest` fails → hexagonal package layout
- [x] T4-05 `AggregateDecoderTest` fails → `AggregateDecoder` (Utf8 → String, validation)
- [x] T4-06 `AggregateSinkTest` fails → `AggregateSink`, ports `MetricWindowStore`, `MetricProjection`
- [x] T4-07 `MetricWindowUpsertIT` fails → `schema.sql`, `JdbcMetricWindowStore`
- [x] T4-08 `AggregateBatchListenerTest` fails → `AggregateBatchListener` (ack on the listener thread)
- [x] T4-09 `BatchWriterLifecycleTest`, `SinkWiringTest` fail → `BatchWriterLifecycle`, config, `application.yml`
- [x] T4-10 `NoLossNoDoubleCountIT` fails → `RedisHotProjection`, listener container factory, error handler
- [x] T4-11 `PoisonRecordIT` fails → `KafkaDeadLetterSink`
- [x] T4-12 verified output into `MILESTONES.md`
