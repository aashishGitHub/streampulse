# Phase 1 — Contracts and schemas

**Status:** done. Avro is genuinely contract-first (types are generated). OpenAPI and AsyncAPI are
documents that tests parse, not generators. **No schema registry exists**, although two ADRs and a
milestone note say one was coming.

**One line to say first:** "Events are defined as Avro schemas that generate the Java types, and a
unit test fails the build if a schema change is not backward compatible with its frozen baseline."

## Code map

| Thing | Where |
|---|---|
| Event schema (envelope plus payload union) | `libs/contracts/src/main/avro/TelemetryEvent.avsc` |
| The compatibility gate | `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java` |
| Frozen copies of released schemas | `libs/contracts/src/test/resources/baseline/README.md` and the `.avsc` files beside it |
| Deliberately breaking fixtures | `libs/contracts/src/test/resources/incompatible/` |
| Raw Avro codec (no registry framing) | `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java` |
| Custom codegen task | `build-logic/src/main/kotlin/AvroCodegenTask.kt`, `ProtoCodegenTask.kt` |
| gRPC definition | `libs/contracts/src/main/proto/telemetry.proto` |
| HTTP contracts | `api/openapi/ingest-v1.yaml`, `query-v1.yaml`, `control-plane-v1.yaml`, `alerts-v1.yaml` |
| Topic contract | `api/asyncapi/streampulse-topics.yaml` |
| Contract document tests | `libs/contracts/src/test/java/com/streampulse/contracts/ApiContractTest.java` |
| Decisions | `docs/adr/0004-avro-for-events.md`, `docs/adr/0005-topic-and-schema-versioning.md` |

---

## Beginner

### B1. Why do events cross Kafka as Avro and not JSON?

**A strong answer contains**
- Field names are not written per record, so the payload is smaller: a heartbeat measured 134 bytes
  against about 409 as JSON.
- Generated classes make a field typo a compile error, and `timestamp-millis` generates `java.time.Instant`.
- Cost: not human-readable, and codegen adds a build step.
- Be honest: the "JSON" side of that comparison is the generated class's `toString()`, and the Avro side
  excludes the Kafka envelope. It is an order-of-magnitude comparison, not a benchmark.

**Open** `docs/adr/0004-avro-for-events.md`

### B2. What does BACKWARD compatibility mean?

**A strong answer contains**
- A *new* reader can read *old* data. So consumers are upgraded first, then producers.
- It fits retained and replayed history: seven days on the raw topic, and a planned archive.

**Open** `docs/adr/0005-topic-and-schema-versioning.md`

### B3. Which schema changes are allowed under BACKWARD, and which are not?

**A strong answer contains**
- Allowed: add a field with a default; remove a field that has a default; widen `int` to `long`.
- Forbidden: add a field with no default; rename a field (a removal plus an addition); narrow a type.
- Three fixtures in the test resources demonstrate the forbidden ones.

**Open** `libs/contracts/src/test/resources/incompatible/`

### B4. What is a "baseline" here, and who updates it?

**A strong answer contains**
- A frozen copy of each released schema, committed to git. The test checks that the current schema can
  read data written with the baseline.
- Updated by hand in the same commit as the schema change so a reviewer sees both halves. Updating it just
  to silence a red test defeats the gate.

**Open** `libs/contracts/src/test/resources/baseline/README.md`

### B5. Why is the topic called `telemetry.raw.v1`?

**A strong answer contains**
- The major version lives in the name so `v1` and `v2` can run side by side for changes that cannot be made
  compatibly. A test asserts every topic address carries a version suffix.

**Open** `api/asyncapi/streampulse-topics.yaml`

### B6. What is the partition key and why?

**A strong answer contains**
- The session id: one session's events stay ordered, and high cardinality spreads load evenly.
- Cost: per-title aggregation needs a repartition (Phase 3). Raising the partition count later changes
  `hash(key) % partitions` and breaks key ordering, which is why 12 was chosen up front.

**Open** `libs/contracts/src/main/avro/TelemetryEvent.avsc`

---

## Mid

### M1. How exactly does the compatibility test work?

**A strong answer contains**
- It calls Avro's reader/writer compatibility check with the current schema as reader and the baseline as
  writer. No registry and no Docker, so it fails the pull request, not the deployment.
- A coverage guard asserts every schema has a baseline, so none is ungated. The failure names the rule,
  location and field.

**Open** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java`

### M2. How did you prove the gate can fail?

**A strong answer contains**
- Three permanent breaking fixtures must be rejected, so weakening the check turns a test red; a
  counter-example (an optional field with a default) must be accepted.
- A real demonstration: add a required field with no default and watch
  `READER_FIELD_MISSING_DEFAULT_VALUE` fail the build, then revert.
- The lesson is not "adding fields is dangerous".

**Open** `MILESTONES.md` (Phase 1, "The schema-evolution gate — proven by breaking it")

### M3. Why must optional fields be a union with null plus a default, and enums declare a default symbol?

**A strong answer contains**
- A field the reader has but the writer did not write needs a default, or resolution fails.
- An enum default lets a reader map an unknown newer symbol instead of throwing.
- **Caveat:** both help only when the reader resolves against the *writer's* schema (see S1), which this
  repo's codec does not do.

**Open** `libs/contracts/src/main/avro/TelemetryEvent.avsc`

### M4. Avro versus Protobuf: how do they resolve fields, and what breaks each?

**A strong answer contains**
- Avro resolves by name: a rename breaks it, and there is no number to reuse.
- Protobuf resolves by field number: a rename is free, but reusing a retired number silently corrupts
  data.
- The repo uses both on purpose (Avro on Kafka, Protobuf on the gRPC edge); the cost is cognitive load.

**Open** `docs/adr/0004-avro-for-events.md`

### M5. What is the `Utf8` hazard?

**A strong answer contains**
- Generated Avro classes decode strings as `Utf8`, which never equals a `String` and hashes differently,
  so map lookups silently miss.
- The repo converts with `toString()` in each decoder. A key built from a `Utf8` splits groups with no
  exception.

**Open** `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java`

### M6. Why a custom Gradle task for Avro codegen?

**A strong answer contains**
- The community plugin's support for Gradle 9 was unverified, running the tools jar failed on an
  unresolvable dependency, and the tool's default mode is protocol, not schema.
- Calling the compiler directly is incremental and cacheable through input and output annotations.
- Trade-off: code to maintain; revisit when upstream catches up. (The same reasoning drove the protobuf
  decision; see the Phase 2 gRPC notes in `MILESTONES.md`.)

**Open** `build-logic/src/main/kotlin/AvroCodegenTask.kt`

---

## Senior

### S1. Schema registry or raw Avro: what does this repo actually do, and what does it cost?

**A strong answer contains**
- Raw Avro binary: no magic byte, no schema id, no runtime compatibility check. The codec's own Javadoc
  says it: "Writer and reader schema are the same class, so this does not resolve schema evolution across
  versions; that is what Schema Registry would add."
- **State the discrepancy yourself:** ADR-0005 says a registry "still arrives in Phase 2", and ADR-0004's
  table says the schema travels with the data. No registry was built. ADR-0012 and ADR-0016 record the
  decision to stay with raw Avro.
- Consequence (my reasoning from that Javadoc): the build-time gate proves a new schema could read old data
  under writer-aware resolution, but the runtime cannot supply the writer schema. In practice a schema change
  needs producers and consumers to move together, or a new topic version.
- What a registry adds: a schema id in each message, writer-schema lookup, runtime compatibility checks.
  What it costs: a runtime dependency, an availability concern, and caching.

**Open** `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java`

### S2. Compare BACKWARD, FORWARD and FULL. Which for a topic whose consumers you do not control?

**A strong answer contains**
- BACKWARD: new reader, old data (consumers first), suits replay. FORWARD: old reader, new data (producers
  first). FULL: either order, the most restrictive.
- With consumers you cannot sequence, lean to FULL, or to the *transitive* variants across all historical
  versions. This repo's baseline is the *last release only*, so it is not transitive.
- Escape hatch: a `v2` topic with dual-write, a deprecation window, and monitoring of `v1` lag until idle.
  Dual-write brings its own consistency problems (ordering across two topics), which the ADR does not
  discuss.

**Open** `docs/adr/0005-topic-and-schema-versioning.md`

### S3. Nothing enforces consumers-first deploy order here. How would you?

**A strong answer contains**
- Say it plainly: ADR-0005 defers it to a later phase, and that phase (Kubernetes) is not built.
- Options: a release check that fails if a producer ships a schema that consumers have not deployed against;
  staged rollout with lag and error monitoring; a canary consumer; or FULL compatibility to remove the
  ordering constraint.
- BACKWARD also does not protect a *rolled-back* consumer reading newer data; that is a FORWARD concern.

**Open** `docs/adr/0005-topic-and-schema-versioning.md`

### S4. Contract-first: what actually prevents drift?

**A strong answer contains**
- Avro: genuine. The `.avsc` generates the types, so Java drift is a compile error.
- OpenAPI: not yet. The documents are parsed and a few responses asserted, but the controller is
  handwritten. The spec promised generated interfaces; that was not delivered.
- AsyncAPI: a document check only, and the compose `kafka-init` is a second hand-written source of truth
  for topics, with nothing checking they agree.
- Protobuf: only that `protoc` parses it. There is no `buf lint` or `buf breaking`, so a field renumbering
  would go undetected.

**Open** `libs/contracts/src/test/java/com/streampulse/contracts/ApiContractTest.java`

### S5. How would you catch OpenAPI drift here?

**A strong answer contains**
- Validate real responses against the spec in controller tests; diff the spec between commits in CI for
  breaking changes; or generate the server interface so the compiler enforces it.
- Cover all four documents in one place (one is validated in the alert-engine module today), and assert
  `503` as well as `429`.
- **Verify any specific tool name against current docs before quoting it**; those are from memory, not the
  repo.

**Open** `api/openapi/ingest-v1.yaml`

### S6. How would you evolve `telemetry.raw.v1` safely?

**A strong answer contains**
- Additive changes only: new optional fields with defaults, new enum symbols with a default.
- A breaking change means a `v2` topic, dual-write, a published deprecation window, and watching `v1`
  consumer lag.
- Raising the partition count rehashes keys and breaks ordering, so it is a breaking change too.
- Given S1, even additive changes need coordination here. That is the honest answer.

**Open** `docs/adr/0005-topic-and-schema-versioning.md`

---

## Say this honestly

- No schema registry. Two ADRs and a milestone note still describe one as pending.
- The OpenAPI contract test does not assert `503` for ingest.
- The baselines currently equal the live schemas, so the gate presently proves "unchanged".
- `RESUME-HERE.md` still says the OpenAPI file describes saturation as 429; it now documents 429 for quota
  and 503 for saturation. The old note is stale.
- The gRPC efficiency claims in ADR-0010 are expectations; the benchmark was never run.

## Stories and numbers

[What broke](../05-what-broke.md): story 11 (test totals). [Numbers](../06-numbers.md): event-size row.
