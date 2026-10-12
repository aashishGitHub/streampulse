# Phase 3 — Stream processor: Tasks

Order is the order of work. Each line: failing test first, then code. Tick only with passing
output in MILESTONES.md.

## Day 1 (done with the build hygiene)
- [x] T3-01 ADR-0012 written; ADR-0008 marked superseded
- [x] T3-02 `AvroBinaryCodecTest` fails → `AvroBinaryCodec`, `AvroDecodeException`
- [x] T3-03 `KafkaIngestIT` fails → backbone property, Kafka default, record timestamp = occurredAt
- [x] T3-04 AsyncAPI: `telemetryAggDlt`, aggregate key documented

## Day 2
- [x] T3-05 `EventValidatorTest` fails → `EventValidator` (sealed `Valid | Invalid`, injected `Clock`)
- [x] T3-06 `MetricMathTest` fails → `MetricMath`
- [x] T3-07 `ProcessorArchitectureRulesTest` fails → module skeleton with hexagonal layout (+ Spring wiring, health, metrics)
- [x] T3-08 `undecodableBytesGoToDltWithReasonHeader`, `invalidEventGoesToDlt` fail → decode + validate + split
- [x] T3-09 `duplicateEventIdCountedOnce` fails → `EventIdDeduplicator`
- [x] T3-10 `noOutputBeforeWindowCloses`, `emitsOneFinalAggregatePerMetricAfterGrace` fail → window + aggregate + suppress
- [x] T3-11 `outOfOrderWithinGraceIsCounted`, `lateAfterGraceIsDroppedNotEmitted` fail → grace handling + drop metric
- [x] T3-12 `unknownTypeCountsTowardTotalsOnly` fails → metric mapping
- [x] T3-13 `StreamProcessorIT` — real broker, exactly-once v2 end to end
