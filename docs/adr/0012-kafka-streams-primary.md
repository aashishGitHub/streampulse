# ADR-0012 — Apache Kafka and Kafka Streams are the primary backbone

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 3 · **Supersedes** ADR-0008

## Context

ADR-0008 made Kinesis (on moto) the default and kept Kafka behind a profile. Phase 3 needs a
stream processor: 10-second windows per tenant/title/region, a grace period for late events,
and one final value per window. Nothing available locally for free runs that against Kinesis:

| Option | Why not |
|---|---|
| Kinesis Data Analytics (Flink) | Not emulated by moto; no free local equivalent. |
| KCL consumer | The Kinesis Client Library against moto is unproven here, and it has no windowing, so windows, state and late-event handling would be hand-written. |
| Lambda on moto / `sam local` | Stateless per invocation; windowing state would live in an external store. |

ADR-0008 flagged this fork itself: *"Phase 3's stream processing must choose between Kafka
Streams and a KCL-style consumer."*

## Decision

**Apache Kafka (KRaft, single broker) is the default backbone and Kafka Streams is the
stream processor.** `streampulse.backbone` selects the publisher: `kafka` (default) or
`kinesis`. The Kinesis adapter and its moto-based tests stay as the comparison, so the
Strategy seam is still exercised by two implementations.

Kafka Streams runs inside an ordinary JVM, so it is testable with `TopologyTestDriver`
(a topology runs in memory, no broker) and needs no cluster beyond the broker.

## What exactly-once v2 does and does not cover

`processing.guarantee=exactly_once_v2` makes **read → process → write to Kafka** atomic: the
output records, the state-store changelog and the consumer offsets commit in one transaction.

It does **not** cover anything that leaves Kafka. A sink that writes to Postgres or Redis
sees at-least-once delivery, so the sink must be idempotent (ADR-0013). The honest claim is
*effectively-once = at-least-once delivery + idempotent processing*.

## Consequences

**Positive**
- A real windowed, stateful topology with a late-data policy, testable without a broker.
- Event time comes from the record timestamp, which the publisher now sets to `occurredAt`.
- Every later phase (sink, query, alerts) consumes a topic that carries real aggregates.

**Negative**
- Kinesis is no longer the default path, so AWS-native depth (shards, `PutRecords` partial
  failure, quotas) is exercised only by the comparison adapter and its tests.
- A windowed `suppress(untilWindowCloses)` emits only when **stream time** advances, i.e.
  when later events arrive. A quiet partition holds its last window open indefinitely. The
  pipeline needs steady traffic, and tests must pipe a later "tick" record to close a window.
- Final aggregates cannot appear sooner than window end + grace (10 s + 5 s here). That floor
  is the freshness cost of correctness for late events, and it is the number to quote.

## Wire format

Raw Avro binary, no schema-registry framing (`AvroBinaryCodec` in `libs/contracts`). Schema
evolution is guarded by the compatibility test against frozen baselines (ADR-0004/0005), not
by a registry. Registry framing is a later, additive change.

## Verified

- `KafkaIngestIT` runs the default (no backbone property) against a real broker: key,
  partition, decodable payload, record timestamp = `occurredAt`, idempotent retry.
- **VERIFY:** `apache/kafka:3.9.0` fails to start under Testcontainers 1.21.2 (the image's
  start-up rejects the `0.0.0.0` advertised listener Testcontainers configures). 3.9.1
  starts. Compose and the test both pin 3.9.1.
