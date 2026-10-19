# Multi-region design (DESIGN ONLY)

**Status: a design. Nothing in this document has been built, simulated or run.** The local
emulators (ADR-0003) cannot model cross-region replication, DNS failover or inter-region latency,
and no Terraform here defines a second region. Every number is an **assumption** chosen to reason
with; none is measured, and none comes from a provider guarantee I have verified. Provider
capabilities named below are from memory of how those services work and are marked `VERIFY`.

## 1. What is being protected

| Asset | Where it lives today | Loss means |
|---|---|---|
| Raw telemetry in flight | Kafka topic `telemetry.raw.v1` (Kinesis stream `telemetry-raw-v1` as the comparison path) | events not yet aggregated are gone |
| Aggregates (system of record) | Postgres `metric_window` | dashboards and history wrong |
| Latest values and the live feed | Redis | stale dashboard until rebuilt from Postgres (ADR-0013) |
| Ingest idempotency claims | Redis, 24 h TTL | duplicates become possible again |
| Stream-processor state | Kafka Streams state stores plus changelog topics | dedup window and open windows lost |
| Raw archive | object storage (not written by any service yet) | no replay source older than topic retention |
| Aggregate history table | DynamoDB design (not written by any service) | n/a today |

## 2. Topology (target)

```
                 Route 53 failover record: ingest.streampulse.example
                  primary (health-checked)            secondary
                         │                                │
           ┌─────────────▼─────────────┐    ┌─────────────▼─────────────┐
           │ REGION A (primary)        │    │ REGION B (warm standby)   │
           │ ingest-gateway ──▶ Kafka  │    │ ingest-gateway (scaled to │
           │                   │       │    │   minimum, accepts only   │
           │ stream-processor ◀┘       │    │   after failover)         │
           │       │                   │    │ Kafka (receives mirror)   │
           │       ▼                   │    │ stream-processor (stopped │
           │ sink-writer ─▶ Postgres ──┼─┐  │   or scaled to zero)      │
           │              └▶ Redis     │ │  │ Postgres read replica ◀───┼── async replication
           └──────────────┬────────────┘ │  └─────────────▲─────────────┘
                          │              └────────────────┘
                          └── Kafka mirroring (asynchronous) ───────────▶
```

Active-passive, not active-active. Active-active would need conflict handling for the aggregate
store and a global idempotency store, and I do not claim to have designed that.

## 3. The pieces

### Kafka across regions: MirrorMaker 2 or a managed replicator

Replicates `telemetry.raw.v1` (and, optionally, `telemetry.agg.v1`, `alerts.v1` and
`config.changes.v1`) from A to B asynchronously. Two options:

| Option | Notes |
|---|---|
| MirrorMaker 2 (Kafka Connect based, open source) | Runs on infrastructure we operate. Emits checkpoints that map a consumer group's offsets in A to offsets in B. By default it renames topics with the source cluster alias as a prefix (`A.telemetry.raw.v1`); an identity replication policy can keep names (`VERIFY` the exact class and Kafka version support) |
| A managed replicator for the managed Kafka service | Less to operate. `VERIFY` whether it preserves topic names, whether it syncs consumer offsets, and its lag metrics |

Internal topics (Kafka Streams changelog and repartition topics, `__consumer_offsets`) are not
replicated: they are tied to a cluster's application state. The processor in B rebuilds its state by
replaying.

### The aggregate store: Postgres

Asynchronous cross-region replica, promoted on failover. This is not DynamoDB global tables:
the system of record today is Postgres, and global tables would apply only if the aggregate
history moved to the DynamoDB design in `infra/terraform/modules/dynamodb`. If it did, global tables
replicate asynchronously with last-writer-wins conflict resolution (`VERIFY`), which is safe
here **only** because an aggregate window has one logical writer and the upsert already refuses to
move a value backwards (`computed_at`, ADR-0013). That reasoning would need re-checking under two
writers.

### Redis

Not replicated. It is a rebuildable projection of Postgres. After failover the latest-value hash is
repopulated from Postgres and the live feed starts empty (clients reconnect with `Last-Event-ID`
values from region A's stream, which region B does not have: they get a fresh stream).

### DNS failover: Route 53

A failover record pair with a health check on the primary ingest endpoint. After the primary's
health check fails, DNS answers with the secondary. Clients that cache DNS beyond the record's TTL
keep sending to A until their cache expires.

## 4. Targets (assumptions, not measurements)

| Target | Value | Reasoning, and why it is an assumption |
|---|---|---|
| **RPO** (data we may lose) | **<= 60 seconds of raw telemetry** | Asynchronous mirroring lags by however long the link and the replicator take. I assume tens of seconds in steady state and set 60 s as a budget to measure against. Cross-region lag under load is unmeasured and has to be measured (Phase 9 style) before this is a promise |
| RPO for aggregates | <= the same 60 s, plus one window + grace (15 s) | An aggregate is final only 15 s after its window ends (ADR-0012); anything not yet final is recomputed from replayed raw events |
| **RTO** (time to restore service) | **<= 10 minutes** | Health-check detection (assume 3 failed checks at 30 s = 90 s) + DNS TTL (assume 60 s) + promoting the Postgres replica (assume a few minutes) + starting the processor and letting it replay. Each component is an assumption; the sum is a budget, not a forecast |
| Replication lag alert | page at > 30 s sustained | half the RPO budget, so there is time to react before the budget is spent |

If the real replication lag cannot hold 60 s under load, the right response is to change the number
or the architecture, not to leave the sentence in place.

## 5. What breaks when region A is lost

| What | Effect | Mitigation in this design |
|---|---|---|
| Events accepted in A, not yet mirrored | Lost: this is the RPO window | Clients retry what they never saw acknowledged; accepted-but-unmirrored events are not recoverable |
| In-flight windows in the processor | Open windows lost with A's state | Region B replays the mirrored raw topic and re-derives them |
| Ingest idempotency claims | Lost with A's Redis, so a retry of an event A had accepted is not recognised at the door | Duplicates reach the topic; the processor's event-id deduplication catches them if its range still covers them (see below) |
| Live-feed subscribers | Disconnected; their `Last-Event-ID` means nothing in B | Clients resubscribe and receive a fresh stream |
| Postgres | Replica may be missing the last seconds of writes | Reprocessing from replayed raw events refills them; the upsert is idempotent |
| Raw archive | No effect if nothing is lost; region-pair replication of the bucket is a separate decision | not designed |
| Alerts in flight | May be lost or duplicated | consumers must be idempotent (same rule as the sink) |

## 6. Idempotency keys and Kafka offsets after failover

**Offsets are per cluster.** Offset 1,000,000 in A is not offset 1,000,000 in B: the replicated topic
has its own offsets. A consumer in B cannot reuse the group offsets it had in A. Offset translation
(checkpoints that map A's committed offsets to B's) gives a *starting point*, and it errs toward
earlier offsets, so the first records after failover are **replays**. That is at-least-once
delivery, which the pipeline is already designed for.

**What absorbs the replay, and where each safeguard stops:**

| Layer | Key | Survives failover? |
|---|---|---|
| Ingest door | `eventId` claim in Redis, 24 h TTL | **No.** Redis is regional and not replicated. After failover a retried event is accepted again |
| Stream processor | `eventId` in a windowed state store, range = window + grace (15 s) | Only if B's processor replays far enough back to rebuild it. State is rebuilt from the replayed raw topic, so replaying at least the dedup range **before** the failover point keeps it correct. A duplicate older than that range is not caught here |
| Sink | primary key (tenant, title, region, metric, window start) with `computed_at` guard | **Yes.** The Postgres upsert makes a recomputed identical aggregate a no-op, and never moves a value backwards |

So the layered answer is: the ingest-side claim is a convenience, the processor's range-limited
dedup is a second line, and the **idempotent aggregate write is the line that actually holds**. The
failure that remains is a duplicate raw event older than the processor's dedup range whose window
has not yet been finalised: it would be counted twice. I have not quantified how likely that is.

**Practical rule for failover:** start B's processor from the translated offset, minus a safety
margin of at least the dedup range, and accept a burst of replays.

**Failback** (returning to A) is a second failover with the same offset problem in the other direction,
and needs its own plan. It is not designed here.

## 7. What would have to be true before building this

1. Measure cross-region replication lag under load; adjust the RPO or the design.
2. Measure real failover end to end (health check, DNS, promotion, replay) and replace the RTO budget with a number.
3. Decide whether the ingest idempotency store must become shared or replicated, or whether the downstream layers are enough.
4. Verify every `VERIFY` above against current provider documentation.
5. Add a second-region Terraform root and exercise it against a real account, because emulators cannot show any of this.
