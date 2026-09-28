# Schema evolution — the thing that breaks production quietly

A guided walk through Phase 1. Read [`specs/01-contracts/design.md`](../../specs/01-contracts/design.md)
first for the design; this explains the *why* and shows you how to break it on purpose.

---

## The problem, stated precisely

Inside one process, the compiler guarantees a caller and callee agree. Across a Kafka topic,
**nothing does**.

```
   Monday                                Sunday
   ──────                                ──────
   producer renames sessionId            a weekly batch consumer runs
   → viewingSessionId                    → NullPointerException
   tests pass, deploys fine              → stack trace names neither the change
                                            nor the person who made it
```

Six days between cause and effect, and no link between them. That is the failure mode this
phase exists to prevent.

---

## How Avro resolution actually works

Avro reads with **two** schemas: the one the data was written with (*writer*) and the one
your code expects (*reader*). It matches fields **by name**:

```
   for each field the READER expects:
       in the writer too?   → read the value
       writer-only field?   → skip it
       reader-only field?   → use the reader's DEFAULT
                              ...no default? → ERROR
```

Everything follows from that last line.

**A new field without a default is a breaking change** — when reading old data, Avro has
nothing to put there. That is the single most useful sentence in this document.

```json
// ❌ breaks every consumer reading historical data
{"name": "abTestBucket", "type": "string"}

// ✅ safe: old data reads as null
{"name": "abTestBucket", "type": ["null", "string"], "default": null}
```

---

## The compatibility table

| Change | Safe? | Why |
|---|---|---|
| Add a field **with** a default | ✅ | old data → the default |
| Add a field **without** a default | ❌ | nothing to fill it with |
| Remove a field that has a default | ✅ | reader ignores it |
| Rename a field | ❌ | resolution is by name — looks like remove + add |
| `int` → `long` | ✅ | Avro promotes numerics |
| `long` → `int` | ❌ | values may not fit |
| Add an enum symbol | ⚠️ | safe **only** if the enum declares a `default` |
| Reorder fields | ✅ | order is irrelevant; names match |

That last row surprises people: field **order** does not matter in Avro, but field **names**
are load-bearing. In Protobuf it is the exact opposite — numbers are load-bearing and names
are free. Being able to state that contrast is a strong interview signal.

---

## Why enums need a `default`

```json
{
  "type": "enum", "name": "DeviceType",
  "symbols": ["DEVICE_UNKNOWN", "WEB", "SMART_TV"],
  "default": "DEVICE_UNKNOWN"
}
```

A newer producer adds `GAME_CONSOLE` and sends it. An older consumer has never heard of it.

- **With** `default`: the value reads as `DEVICE_UNKNOWN`. The consumer keeps working.
- **Without**: it throws. Every older consumer breaks the moment you add a device type.

This is why every enum in `libs/contracts` declares an unknown symbol **and** a default.
Costs nothing; prevents a whole class of outage.

---

## The four compatibility modes

| Mode | Meaning | You must upgrade |
|---|---|---|
| **BACKWARD** | new reader reads old data | **consumers first** |
| FORWARD | old reader reads new data | producers first |
| FULL | both | either order |
| NONE | no guarantees | good luck |

StreamPulse uses **BACKWARD**, because history is retained and replayed — seven days on the
topic, indefinitely in S3. A new consumer must read everything already written.

The consequence is an operational rule: **deploy consumers before producers.** BACKWARD only
protects you if you honour the order it implies.

---

## Where the gate lives

```
  src/test/resources/baseline/*.avsc     ← frozen copies of what is ALREADY on the topics
                 │
       SchemaCompatibilityTest
                 │
   COMPATIBLE ───┴─── INCOMPATIBLE → build fails, field named
```

A Schema Registry does this at **runtime**, when a producer first publishes — too late, the
code is merged and deployed. The baseline check fails the **pull request**, while the author
is still looking at the change.

---

## Break it yourself

Genuinely do this. Five minutes here is worth more than rereading the table.

**1. Add a required field**

```bash
cd ~/Documents/GitHub/streampulse
# edit libs/contracts/src/main/avro/TelemetryEvent.avsc, append to "fields":
#   {"name": "abTestBucket", "type": "string"}
./gradlew :libs:contracts:test
```

You get:

```
TelemetryEvent is NOT backward compatible with its baseline.
  - READER_FIELD_MISSING_DEFAULT_VALUE at /fields/10 (abTestBucket)
```

**2. Now make it safe.** Change the type to `["null", "string"]` and add `"default": null`.
Rerun. It passes. *That* contrast is the lesson — not "adding fields is dangerous", but
"adding a field **without a default** is dangerous".

**3. Rename a field.** Change `sessionId` to `viewingSessionId`. It fails, because Avro
matches by name. Try the same thought experiment in Protobuf, where a rename is free.

**4. Narrow a type.** Change `occurredAt` from `long` to `int`. Fails — values may not fit.
Then try widening `int` to `long` somewhere; that passes, because Avro promotes numerics.

Restore with `git checkout libs/contracts/src/main/avro/` when you are done.

---

## Interview connection

This maps to `../../../Java/interview-qa/04-kafka-and-spring-kafka.md` (schema evolution,
Schema Registry compatibility modes) and `10-grpc.md` Q3 (the Protobuf contrast).

Two answers worth having ready cold:

**"How do you handle schema evolution?"** — Not "we use Avro". Say: BACKWARD compatibility
enforced at build time against a committed baseline, so a breaking change fails the pull
request rather than the deployment; the rule is that a new field needs a default and a rename
is breaking, because Avro resolves by name; and the deployment order that BACKWARD implies is
consumers first.

**"Avro or Protobuf?"** — They fail differently. Avro resolves by name, so renaming breaks
and there are no numbers to reuse. Protobuf resolves by number, so renaming is free and
reusing a retired number silently corrupts data. Each format's dangerous operation is the
other's safe one. We use Avro inside Kafka for the registry integration and Streams serdes,
and Protobuf at the gRPC edge.
