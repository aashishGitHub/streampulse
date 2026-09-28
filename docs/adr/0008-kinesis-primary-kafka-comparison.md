# ADR-0008 — Kinesis as the primary backbone, Kafka as a comparison adapter

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 2 · **Supersedes part of** ADR-0003

## Context

Phase 2 originally published to Kafka. Two things changed the decision:

1. **Project direction.** StreamPulse is a vehicle for learning the Java ecosystem *in a
   cloud environment*, and the target role's JD names Kinesis alongside Kafka. An
   AWS-native backbone makes every later phase — Lambda, DynamoDB, S3, IAM, Terraform —
   fit together rather than sit beside a non-AWS component.
2. **Cost of running it.** There is no AWS account available, so whatever is chosen must
   run free and offline.

The constraint that decided it: **it must cost nothing and need no credentials.**

## Decision

**Kinesis Data Streams is the default backbone**, running against **moto** locally.
**Kafka remains** behind the `kafka` Spring profile, as a comparison adapter.

Both implement the same `EventPublisher` port, so the choice is one bean definition.

## Verified before committing to it

This was tested rather than assumed:

```
PutRecords → FailedRecordCount: 0
  sess-a → shardId-000000000000
  sess-a → shardId-000000000000     ← same key, same shard
  sess-b → shardId-000000000001
GetRecords → both sess-a records read back, in order
```

**Partition-key routing works identically to Kafka's.** That is the property the whole
design rests on, so it was the first thing checked.

## Kinesis vs Kafka

| | Kafka | Kinesis |
|---|---|---|
| Unit of parallelism | partition | shard |
| Ordering | per partition, by key | per shard, by key — **same guarantee** |
| Consumer position | offset, consumer-managed | sequence number + checkpoint (in DynamoDB) |
| Consumer grouping | consumer group | KCL application, or enhanced fan-out |
| Retention | time or size, unbounded by config | 24h default, up to 365 days |
| Scaling | add partitions (rehashes keys) | split/merge shards |
| Throughput limit | broker-bound | **1 MB/s or 1000 rec/s per shard — a hard quota** |
| Partial write failure | no | **yes — `PutRecords` returns 200 with failures inside** |
| Ops burden | you run brokers | managed |
| Cost model | infrastructure | per shard-hour + per million records |

The two bold rows are where real Kinesis design work happens:

- **Per-shard quotas are hard.** Exceed them and you get
  `ProvisionedThroughputExceededException` — a throttle, not a failure. Kafka has no
  equivalent fixed cap, so capacity planning is a genuinely different exercise.
- **`PutRecords` can partially fail and still return HTTP 200.** The per-record outcome is
  in `FailedRecordCount`. Code that checks only the status code silently drops records; it
  is the most common Kinesis bug and it stays invisible until someone notices the numbers
  are low. `KinesisEventPublisher.failOnPartialFailure` handles it explicitly.

## Why keep Kafka at all

- It is open source and free, so keeping it costs nothing but a profile.
- The JD names both, and being able to say *"I ran the same pipeline on both"* is a
  materially better answer than having read about one.
- It keeps the port honest. An abstraction with one implementation is a guess; with two it
  is tested — and swapping backbones changes exactly one bean, which is the proof.

## Consequences

**Positive**
- Runs free, offline, with no AWS account. Credentials are the literal string `test`.
- Every later phase can be AWS-native and coherent.
- The Strategy seam is demonstrated rather than asserted.

**Negative**
- Kafka-specific depth — consumer groups, exactly-once semantics, Kafka Streams — is no
  longer backed by the default path. Phase 3's stream processing must choose between
  Kafka Streams and a KCL-style consumer, and that is a real fork in the plan.
- Two backbones is two things to keep working. Mitigated by the shared port and by the
  business-rule tests running against neither.
- moto is not Kinesis. No IAM, no throttling, no realistic latency. **A green local run is
  not evidence that a production call would be permitted.** ADR-0003.

## Local-simulation gotchas found the hard way

Both cost real time and are recorded so they are not rediscovered:

1. **CBOR.** Kinesis SDKs default to CBOR, a binary JSON encoding moto does not implement.
   The failure is an unhelpful HTTP 500 with no message, after four silent retries. Fixed
   with `-Daws.cborEnabled=false`, which only affects local runs.
2. **HTTP/2.** `KinesisAsyncClient` negotiates HTTP/2, because real Kinesis supports it.
   moto is a Flask app speaking HTTP/1.1, so Netty fails with *"First received frame was not
   SETTINGS. Hex dump: 485454502f"* — which is `"HTTP/"` in ASCII. Fixed by forcing HTTP/1.1
   for local simulation only.

**VERIFY:** both workarounds may become unnecessary as moto and the SDK evolve. Check before
assuming they are still needed.
