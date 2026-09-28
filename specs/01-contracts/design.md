# Phase 1 — Contracts & Schemas: Design

Implements [`requirements.md`](requirements.md). See ADR
[0004](../../docs/adr/0004-avro-for-events.md) (why Avro) and
[0005](../../docs/adr/0005-topic-and-schema-versioning.md) (versioning and compatibility).

---

## 1. Where contracts live

```
  libs/contracts/
    ├── src/main/avro/                  ← the source of truth for event shape
    │     TelemetryEvent.avsc
    │     MetricAggregate.avsc
    │     AlertEvent.avsc
    │     ConfigChange.avsc
    │
    ├── src/test/resources/baseline/    ← FROZEN copies of released schemas
    │     TelemetryEvent.avsc                 (compatibility is checked against these)
    │
    ├── src/test/resources/incompatible/ ← deliberately broken, to prove the gate works
    │
    └── build/generated/avro/java/      ← GENERATED, gitignored, compiled
          com/streampulse/contracts/v1/TelemetryEvent.java
```

```
  api/
    ├── openapi/       ingest-v1.yaml · query-v1.yaml · control-plane-v1.yaml
    └── asyncapi/      streampulse-topics.yaml
```

Contracts sit in their own module because **both** producers and consumers depend on them.
Putting them inside a service would make every consumer depend on that service's jar — and
on everything else inside it.

---

## 2. Flow of control: what the build does

```
  ./gradlew :libs:contracts:build
      │
      ├─ generateAvroJava        (custom JavaExec task)
      │    src/main/avro/*.avsc ──▶ Avro SpecificCompiler ──▶ build/generated/avro/java
      │                                                        │
      ├─ compileJava ◀────────────────────────────────────────┘
      │    generated sources are added to the main source set
      │
      ├─ test
      │    ├─ round-trip:      serialize → deserialize → equals
      │    ├─ compatibility:   current schema can read baseline-written data
      │    ├─ gate proof:      an incompatible schema IS rejected
      │    └─ openapi-valid:   every YAML parses
      │
      └─ jar
```

**Why a hand-written codegen task rather than a plugin.** The common Avro Gradle plugin's
support for Gradle 9 is unverified, and a learning project should not be blocked on a
third-party plugin's release cadence. A `JavaExec` invoking Avro's own `SpecificCompiler` is
about fifteen lines, has no compatibility risk, and makes visible what a plugin would
otherwise hide. See ADR-0004.

---

## 3. The event model

### Envelope + payload union

```
  TelemetryEvent
  ┌──────────────────────────────────────────────────────────┐
  │ ENVELOPE — identical for every event type                │
  │   eventId     string   (UUID, client-generated)          │  ← idempotency key
  │   tenantId    string                                     │
  │   sessionId   string                                     │  ← Kafka PARTITION KEY
  │   titleId     string?                                    │
  │   deviceType  enum                                       │
  │   region      string                                     │
  │   occurredAt  timestamp-millis (UTC)                     │  ← EVENT time
  │   receivedAt  timestamp-millis (UTC)                     │  ← INGEST time
  │   type        enum                                       │
  ├──────────────────────────────────────────────────────────┤
  │ PAYLOAD — union, exactly one variant                     │
  │   SessionStart │ SessionEnd │ Heartbeat                  │
  │   │ BufferingEvent │ BitrateChange │ ErrorEvent          │
  └──────────────────────────────────────────────────────────┘
```

**Why an envelope plus a union, rather than one topic per event type?**

| | One topic per type | Envelope + union (chosen) |
|---|---|---|
| Ordering within a session | ✗ lost across topics | ✓ preserved — one partition |
| Consumer must subscribe to | N topics | 1 |
| Adding an event type | new topic, new plumbing | new union branch |
| Filtering one type | free | consumer-side predicate |

Ordering decides it. Stream processing needs `session_start → heartbeat → buffering →
session_end` **in order**, and Kafka only guarantees order within a partition of one topic.

**Why both `occurredAt` and `receivedAt`.** Event time is when the player observed it; ingest
time is when we got it. A device that was offline for an hour sends events with old
`occurredAt` values — this is exactly the late-data problem Phase 3's windowing must handle,
and you cannot handle it without both timestamps.

### Why `sessionId` is the partition key

```
  key = sessionId
    ✓ all events for one viewing session land on one partition → ordering preserved
    ✓ high cardinality → even spread across partitions
    ✗ cannot aggregate per-title without a repartition (Phase 3 pays this, knowingly)

  key = tenantId  ✗ THREE tenants ⇒ three hot partitions; the rest idle
  key = null      ✗ round-robin ⇒ no ordering guarantee at all
```

This trade-off is a standard interview question. The answer is not "sessionId" — it is
naming what you give up: per-title aggregation needs a repartition, which costs a network
hop and an extra topic. We accept that because ordering is a correctness property and
repartitioning is only a performance cost.

---

## 4. Schema evolution: how compatibility actually works

Avro resolves schemas by **name**, using a reader schema and a writer schema:

```
   producer (old)                         consumer (new)
   writes with WRITER schema              reads with READER schema
        │                                        │
        └──────────▶ bytes + schema id ──────────┘
                            │
                  Avro resolves field by field BY NAME:
                    • field in both        → read it
                    • in writer only       → skip it
                    • in reader only       → use the reader's DEFAULT
                                             (no default ⇒ ERROR)
```

That last line is the whole rule. **A new field without a default is a breaking change**,
because when reading old data Avro has nothing to put there.

| Change | BACKWARD safe? | Why |
|---|---|---|
| Add a field **with** a default | ✅ | reading old data uses the default |
| Add a field **without** a default | ❌ | nothing to fill it with |
| Remove a field with a default | ✅ | reader ignores it |
| Remove a field without a default | ❌ | old readers need it |
| Rename a field | ❌ | resolution is by name — it looks like remove + add |
| Widen `int` → `long` | ✅ | Avro promotes numerics |
| Narrow `long` → `int` | ❌ | values may not fit |
| Add an enum symbol | ⚠️ | safe **only** if the enum declares a `default` |

**Compatibility modes**, and why we chose BACKWARD:

| Mode | Guarantees | Upgrade order |
|---|---|---|
| **BACKWARD** (chosen) | new reader reads old data | **consumers first**, then producers |
| FORWARD | old reader reads new data | producers first |
| FULL | both | either |
| NONE | nothing | — |

BACKWARD fits a telemetry pipeline: history is retained and replayed, so a new consumer must
be able to read everything already on the topic and in S3. It is also the Confluent default.

**Contrast with Protobuf** (used in Phase 2 for gRPC): Protobuf resolves by **field number**,
so renaming is free and reusing a number is catastrophic. Avro resolves by **name**, so
renaming breaks and there are no numbers to reuse. Being able to state that difference is a
strong signal.

---

## 5. Enforcing it without a Schema Registry

```
  src/test/resources/baseline/TelemetryEvent.avsc   ← frozen, committed, reviewed
                     │
                     ▼
  SchemaCompatibility.checkReaderWriterCompatibility(
        reader = current schema,      ← from src/main/avro
        writer = baseline schema)     ← what is already on the topic
                     │
      COMPATIBLE ────┴──── INCOMPATIBLE → fail the build, naming the field
```

Avro ships `org.apache.avro.SchemaCompatibility`, so this runs as an ordinary unit test with
no Docker and no registry. A real Schema Registry arrives in Phase 2 and enforces the same
rule at runtime — but catching it at **build** time is strictly better, because it fails the
pull request rather than the deployment.

Updating the baseline is a deliberate, reviewable act: you copy the new schema over the
baseline in the same commit that changes it, so a reviewer sees both halves.

---

## 6. Topic design

| Topic | Key | Partitions | Retention | Cleanup | Why |
|---|---|---|---|---|---|
| `telemetry.raw.v1` | `sessionId` | 12 | 7 days | delete | ordering per session; a week allows replay after an incident |
| `telemetry.agg.v1` | `tenant:title:window` | 6 | 30 days | delete | serves dashboards; lower volume than raw |
| `alerts.v1` | `tenant:ruleId` | 3 | 90 days | delete | low volume; retained for the SLA report |
| `config.changes.v1` | `entityId` | 3 | ∞ | **compact** | a cache needs the *latest* value per key, not the history |
| `*.dlt` | as source | 3 | 30 days | delete | poison messages, retained for inspection |

**Why 12 partitions for raw.** Partition count is the ceiling on consumer parallelism — you
cannot have more consumers in a group than partitions. Increasing it later is possible but
**breaks key-based ordering**, because `hash(key) % partitions` changes. So pick above
current need. 12 is divisible by 1, 2, 3, 4, 6 and 12, which makes consumer scaling even.

**Why `config.changes.v1` is compacted.** Consumers rebuild a cache from it on startup. They
need the current value of every key, not every historical change; compaction keeps the last
value per key indefinitely at bounded size.

---

## 7. Failure modes considered

| Failure | Mitigation |
|---|---|
| Producer ships a breaking schema | baseline compatibility test fails their build |
| A field is added without a default | same test; the error names the field |
| Consumer receives an unknown enum symbol | enums declare a `default` symbol |
| Ordering lost by adding partitions | partition count chosen high; documented as a breaking change |
| Topic contract drifts from reality | AsyncAPI is reviewed; Phase 2 creates topics from it |
| OpenAPI drifts from the controller | Phase 2 generates interfaces from it — the compiler enforces it |
| Clock skew between devices | both `occurredAt` and `receivedAt` are carried; Phase 3 windows on event time with a grace period |
