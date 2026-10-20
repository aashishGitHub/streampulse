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

**Answer:** Avro does not write field names on every record, so the payload is much smaller: one heartbeat measured 134 bytes in Avro against about 409 as JSON, which is roughly 67 percent smaller. That matters because a heartbeat per player every ten seconds is the highest-volume event in the pipeline. Generated classes also make a field typo a compile error, and the `timestamp-millis` logical type generates a real `java.time.Instant`. The costs are that the bytes are not human-readable and that code generation adds a build step. I would be upfront about the number: the "JSON" side of that comparison is the generated class's `toString()`, which emits Avro's JSON form, and the Avro side excludes the Kafka record envelope. It is one run on my machine and an order-of-magnitude comparison, not a benchmark.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/SerializationRoundTripTest.java:176-183`
```java
int avroSize = serialize(event, TelemetryEvent.class).length;
// toString() on a generated class emits the Avro JSON form — close enough to a
// JSON encoding for an order-of-magnitude comparison.
int jsonSize = event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

System.out.printf(
        "Avro %d bytes vs JSON ~%d bytes (%.0f%% smaller)%n",
        avroSize, jsonSize, 100.0 * (jsonSize - avroSize) / jsonSize);
```
Point at the `toString()` line: that is the honest caveat about what "JSON" means in the comparison.

**Code:** `docs/adr/0004-avro-for-events.md:28-32` (the measured line), `libs/contracts/src/test/java/com/streampulse/contracts/SerializationRoundTripTest.java:140-161` (the 400-byte budget test), `libs/contracts/src/main/avro/TelemetryEvent.avsc:46-49` (the timestamp logical type)

---

### B2. What does BACKWARD compatibility mean?

**Answer:** BACKWARD compatibility means a new reader can read old data. In practice that tells you the deployment order: you upgrade consumers first and producers afterwards, because a new consumer has to cope with everything already written, while old consumers never see the new format yet. It suits this system because history is retained and replayed. ADR-0005 states seven days on the raw topic and an indefinite S3 archive, and a newly deployed consumer must be able to read all of it. I did not find a retention setting for the raw topic in the compose topic creation, so the seven days is stated intent in the ADR and I have not verified it on a running broker.

**Example:** `docs/adr/0005-topic-and-schema-versioning.md:21-22`
```
| **BACKWARD** | new reader reads old data | **consumers first**, then producers | ✅ |
| FORWARD | old reader reads new data | producers first | ✗ |
```
The upgrade-order column is the practical consequence, and the FORWARD row is its mirror image.

**Code:** `docs/adr/0005-topic-and-schema-versioning.md:17-32` (why BACKWARD), `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:48-61` (the same reasoning in the test's Javadoc)

---

### B3. Which schema changes are allowed under BACKWARD, and which are not?

**Answer:** Avro resolves by field name. A field in both schemas is read, a field only in the writer's schema is skipped, and a field only in the reader's schema takes the reader's default, and with no default it is an error. From that one rule everything else follows. Allowed: adding a field with a default, removing a field that has a default, and widening `int` to `long`. Forbidden: adding a field with no default, renaming a field, which Avro sees as a removal plus an addition, and narrowing `long` to `int`. Three fixtures in the test resources demonstrate the forbidden ones, and a fourth shows an optional field with a default being accepted, so the lesson is not that adding fields is dangerous.

**Example:** `libs/contracts/src/test/resources/incompatible/AddedRequiredField.avsc:277-281`
```json
{
  "name": "mandatoryNewField",
  "type": "string",
  "doc": "BREAKING: no default. Reading old data, Avro has nothing to put here."
}
```
The missing `default` is the whole break; the sibling fixture `AddedOptionalField.avsc` adds a union with null plus a default and is accepted.

**Code:** `specs/01-contracts/design.md:150-157` (the table of changes), `libs/contracts/src/test/resources/incompatible/RenamedField.avsc` (`sessionId` renamed to `viewingSessionId`), `libs/contracts/src/test/resources/incompatible/NarrowedType.avsc:56-59` (`occurredAt` narrowed to `int`)

---

### B4. What is a "baseline" here, and who updates it?

**Answer:** A baseline is a frozen copy of each released schema, committed to git under the test resources. The compatibility test parses the current schema and the baseline and checks that the current schema, as reader, can read data written with the baseline, as writer. Updating a baseline is a manual act that happens in the same commit as the schema change, so a reviewer sees both halves of the change in one diff and can ask whether the evolution is really safe. Updating the baseline only to silence a red test defeats the gate, and the README says so. The limit is that nothing but review stops someone doing exactly that.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:121-124`
```java
Schema current = parse(CURRENT_SCHEMA_DIR.resolve(schemaName + ".avsc"));
Schema baseline = parseResource("baseline/" + schemaName + ".avsc");

SchemaPairCompatibility result = check(current, baseline);
```
The argument order is the point: current first, as reader, baseline second, as writer.

**Code:** `libs/contracts/src/test/resources/baseline/README.md:1-22` (what a baseline is and how to update it), `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:118-137` (the parameterised gate)

---

### B5. Why is the topic called `telemetry.raw.v1`?

**Answer:** The major version lives in the topic name so that `v1` and `v2` can run side by side when a change cannot be made compatibly, such as splitting a field, changing a unit or restructuring a union. Without it, those changes would need a flag day where every producer and consumer switches at once. With it, producers can dual-write, consumers migrate at their own pace, and `v1` is retired when it is idle. A test enforces the habit: it asserts that every channel address in the AsyncAPI document ends in a version suffix, with an optional `.dlt` for dead-letter topics. What that does not enforce is the migration process itself, which is a design I describe in S6, not something I have run.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/ApiContractTest.java:147-152`
```java
topics().path("channels").fields().forEachRemaining(entry -> {
    String address = entry.getValue().path("address").asText();
    assertThat(address)
            .as("topic '%s' has no version suffix", address)
            .matches(".*\\.v\\d+(\\.dlt)?$");
});
```
The regex is the contract: a name without `.vN` fails the build.

**Code:** `api/asyncapi/streampulse-topics.yaml:25-26` (the raw topic address), `docs/adr/0005-topic-and-schema-versioning.md:34-42` (why the version is in the name)

---

### B6. What is the partition key and why?

**Answer:** The partition key for the raw topic is the session id. All events for one viewing session land on one partition, which is the only way Kafka gives ordering, and `session_start`, heartbeats, buffering and `session_end` must stay in order for stream processing to be correct. A session id has high cardinality, so the load spreads evenly. The alternatives were worse: keying by tenant would give three hot partitions because there are only three tenants, and a null key gives balance but no ordering. The cost I accept is that aggregating per title needs a repartition, a network hop and an extra topic. The partition count is 12, chosen up front because raising it later rehashes keys and breaks ordering for existing keys.

**Example:** `api/asyncapi/streampulse-topics.yaml:49-53`
```yaml
bindings:
  kafka:
    topic: telemetry.raw.v1
    partitions: 12
    replicas: 1        # local only — production would be 3 with min.insync.replicas=2
```
The replica count of 1 is a local-only choice, which the comment says; the production figure there is a design statement I have not run.

**Code:** `libs/contracts/src/main/avro/TelemetryEvent.avsc:18-22` (the `sessionId` field and its doc), `api/asyncapi/streampulse-topics.yaml:31-45` (key, rejected alternatives and the 12 partitions)

---

## Mid

### M1. How exactly does the compatibility test work?

**Answer:** It calls Avro's own `SchemaCompatibility.checkReaderWriterCompatibility` with the current schema as the reader and the frozen baseline as the writer, and asserts the result is `COMPATIBLE`. Because that class ships inside Avro, the test needs no registry, no Docker and no network, so a breaking change fails the pull request rather than a deployment. There is a coverage guard that asserts every schema file has a baseline, so a new schema cannot be silently ungated, and the failure message names the rule, the location and the field. It covers five schemas: `TelemetryEvent`, `MetricAggregate`, `AlertEvent`, `ConfigChange` and `WatchEvent`. The test also compares each schema with itself, as a sanity check on the checker.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:90-92`
```java
private static SchemaPairCompatibility check(Schema reader, Schema writer) {
    return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
}
```
The whole gate is this one library call; everything else in the file is setup, naming and failure messages.

**Code:** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:114-139` (the baseline test), `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:198-216` (every schema has a baseline)

**If they push:** "Why summarise the failure?" Avro's own `toString` embeds both full schemas in each entry, roughly 10 KB per failure for this model, so the test prints only the type, location and field.

---

### M2. How did you prove the gate can fail?

**Answer:** There are two kinds of proof. The permanent one is three fixtures, an added required field, a renamed field and a narrowed type, which the test must reject, so weakening the check turns a test red. A counter-example, an optional field with a default, must be accepted. The one-off one is a real demonstration: I added a required field with no default to a released schema and watched `READER_FIELD_MISSING_DEFAULT_VALUE` fail the build, then reverted. One limit I should state is that all the fixtures are derived from the `TelemetryEvent` schema and checked against its baseline, so the other four schemas share the mechanism but have no breaking fixtures of their own.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:151-160`
```java
@ValueSource(strings = {"AddedRequiredField", "RenamedField", "NarrowedType"})
void breakingChangesAreRejected(String fixture) throws IOException {
    Schema breaking = parseResource("incompatible/" + fixture + ".avsc");
    Schema baseline = parseResource("baseline/TelemetryEvent.avsc");

    SchemaPairCompatibility result = check(breaking, baseline);

    assertThat(isCompatible(result))
            .as("%s SHOULD be rejected but the gate accepted it — the gate is broken", fixture)
            .isFalse();
```
The assertion is inverted on purpose: the test passes only when the checker says "incompatible".

**Code:** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:164-175` (the accepted counter-example), `MILESTONES.md:207-209` (the real failure output)

**If they push:** "What is the lesson?" It is not that adding fields is dangerous; it is that adding a field without a default is, and the paired tests make that distinction executable.

---

### M3. Why must optional fields be a union with null plus a default, and enums declare a default symbol?

**Answer:** When a reader has a field the writer did not write, Avro needs a default to fill it, and without one resolution fails. So every optional field in the schema is a union of `null` and its type with a default of `null`, and the schema's own comment on `titleId` says a field without a default cannot be added compatibly later. Enums work the same way. An enum default lets a reader map an unknown newer symbol to `DEVICE_UNKNOWN` instead of throwing, so adding a device type does not break existing consumers. The caveat is that both mechanisms help only when the reader resolves against the writer's schema, which is the subject of S1, and this repo's codec does not do that. I have not tested what the codec does with bytes from a newer producer.

**Example:** `libs/contracts/src/main/avro/TelemetryEvent.avsc:25-35`
```json
"type": ["null", "string"],
"default": null,
...
"symbols": ["DEVICE_UNKNOWN", "WEB", "MOBILE_IOS", "MOBILE_ANDROID", "SMART_TV", "SET_TOP_BOX", "GAME_CONSOLE"],
"default": "DEVICE_UNKNOWN",
```
The unknown symbol sits first in the list and is also the declared default.

**Code:** `libs/contracts/src/main/avro/TelemetryEvent.avsc:23-39` (`titleId` and `deviceType`), `specs/01-contracts/design.md:157` (the enum-symbol row of the change table)

---

### M4. Avro versus Protobuf: how do they resolve fields, and what breaks each?

**Answer:** Avro resolves fields by name, so renaming a field breaks compatibility, and there are no field numbers to reuse. Protobuf resolves by field number, so a rename is free, but reusing a retired number silently corrupts data, because old bytes get read as the wrong field. Each format's dangerous operation is the other's safe one. This repo uses both on purpose: Avro on Kafka, where the Schema Registry and Kafka Streams ecosystem is strongest, and Protobuf at the gRPC edge. The cost is cognitive load from two serialization formats in one system. The proto file states its own rule, never reuse a number, but it has no `buf lint` or `buf breaking` gate yet, so that rule is a convention today, not a check.

**Example:** `docs/adr/0004-avro-for-events.md:46-48`
```
- **Avro resolves by name.** Renaming breaks; there are no numbers to reuse.
- **Protobuf resolves by number.** Renaming is free; reusing a retired number silently
  corrupts data.
```
These two bullets are the sentence to say aloud.

**Code:** `docs/adr/0004-avro-for-events.md:18-26` (the comparison table), `libs/contracts/src/main/proto/telemetry.proto:18-26` (the versioning rule and the unset `buf` gate)

---

### M5. What is the `Utf8` hazard?

**Answer:** Generated Avro classes decode string fields as `org.apache.avro.util.Utf8`, not `String`, because the generated code uses Avro's default string type. A `Utf8` never equals a `String` and has a different hash code, so a map lookup or a grouping key built from one silently misses, with no exception. The consequence in a stream pipeline is that a key built from a `Utf8` would split what should be one group. The codec does not hide this; its Javadoc warns about it. Instead each consumer converts at its own boundary: the stream processor's mapper calls `toString()` on every string, and the sink writer has a small helper for nullable ones. The cost is that the discipline lives in each decoder, and nothing stops a new consumer forgetting it.

**Example:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/ObservationMapper.java:14-18`
```java
static Observation toObservation(TelemetryEvent e) {
    // toString() on every string: Avro decodes them as Utf8, which never equals a String.
    return new Observation(
            e.getEventId().toString(),
            e.getTenantId().toString(),
```
Every string field is converted before it becomes a domain value.

**Code:** `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java:21-24` (the warning), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateDecoder.java:37-41` (another converting decoder)

**If they push:** "Why not convert inside the codec?" The generated types would still expose `CharSequence`, and changing the generated string type is a build-level decision I did not make; I have not tried it.

---

### M6. Why a custom Gradle task for Avro codegen?

**Answer:** The community Avro Gradle plugin's support for Gradle 9 was unverified when I needed it. I tried running the `avro-tools` jar through `JavaExec`, and that failed because its 1.12.x POM declares test jars that were never published to Maven Central, so it cannot even be resolved. Its `main` also defaults to protocol mode, so a record schema fails with "No protocol name specified". So the custom task calls `SpecificCompiler.compileSchema` directly. Because the task declares `@InputDirectory` and `@OutputDirectory`, Gradle can skip it when nothing changed and cache its output. The trade-off is code I now maintain, and the ADR says to revisit it when upstream catches up. The same reasoning led to driving `protoc` directly for the gRPC contract, which ADR-0010 records.

**Example:** `build-logic/src/main/kotlin/AvroCodegenTask.kt:36-43`
```kotlin
abstract class AvroCodegenTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
```
The two annotated properties are what make the task incremental and cacheable.

**Code:** `build-logic/src/main/kotlin/AvroCodegenTask.kt:10-35` (the reasoning), `build-logic/src/main/kotlin/AvroCodegenTask.kt:67` (the direct compiler call), `libs/contracts/build.gradle.kts:87-94` (the task wired in), `docs/adr/0004-avro-for-events.md:70-86` (the ADR note)

---

### M7. What does the codec do with bad bytes, and why does it check for trailing bytes?

**Answer:** The codec turns every malformed input into one typed exception, `AvroDecodeException`, which callers route to a dead-letter topic. Avro surfaces bad input in several ways depending on where it breaks: an end-of-file error, an Avro runtime error, an index out of bounds or a negative array size. So the decoder catches those and rethrows a single type. It also rejects an empty payload, and it checks `isEnd()` after reading, because Avro stops when the schema is satisfied and ignores whatever follows, so a corrupted or padded tail would otherwise decode as a valid event. The limit is that this validates the shape of the bytes only, not the meaning of the values.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/AvroBinaryCodecTest.java:52-58`
```java
@DisplayName("trailing bytes after a valid record are rejected, so a corrupted tail cannot pass as an event")
void trailingBytesAreRejected() {
    byte[] valid = CODEC.encode(event());
    byte[] withTail = java.util.Arrays.copyOf(valid, valid.length + 3);

    assertThatThrownBy(() -> CODEC.decode(withTail)).isInstanceOf(AvroDecodeException.class);
}
```
The test pads a valid record with three zero bytes and expects rejection.

**Code:** `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java:59-79` (the decode method), `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroDecodeException.java:6-14` (the typed failure)

---

## Senior

### S1. Schema registry or raw Avro: what does this repo actually do, and what does it cost?

**Answer:** This repo uses raw Avro binary: no magic byte, no schema id and no runtime compatibility check. The codec's own Javadoc says it: "Writer and reader schema are the same class, so this does not resolve schema evolution across versions; that is what Schema Registry would add." I should state the discrepancy myself, because the docs disagree: ADR-0005 says a registry "still arrives in Phase 2", and ADR-0004's table says the schema travels with the data, but no registry was built, and ADR-0012 and ADR-0016 record the decision to stay with raw Avro. My own reasoning from that Javadoc is that the build-time gate proves a new schema could read old data under writer-aware resolution, but the runtime cannot supply the writer schema, so in practice a schema change needs producers and consumers to move together, or a new topic version. A registry would add a schema id to each message, writer-schema lookup and runtime checks, at the cost of a runtime dependency, an availability concern and caching.

**Example:** `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java:16-37`
```java
 * Raw Avro binary, no schema-registry framing: the topic carries exactly the bytes of one
 * record. Writer and reader schema are the same class, so this does not resolve schema
 * evolution across versions; that is what Schema Registry would add.
...
this.writer = new SpecificDatumWriter<>(type);
this.reader = new SpecificDatumReader<>(type);
```
Both the writer and the reader are built from the same generated class, which is why nothing can resolve across versions.

**Code:** `libs/contracts/src/main/java/com/streampulse/contracts/codec/AvroBinaryCodec.java:16-19` (the Javadoc), `docs/adr/0012-kafka-streams-primary.md:55-59` (the wire format decision), `docs/adr/0005-topic-and-schema-versioning.md:59-61` (the stale "arrives in Phase 2" sentence)

**If they push:** "So is the compatibility test pointless?" No, it still catches a breaking edit before merge; it is a design-time gate, not a runtime guarantee, and I would say that in those words.

---

### S2. Compare BACKWARD, FORWARD and FULL. Which for a topic whose consumers you do not control?

**Answer:** BACKWARD means a new reader can read old data, so consumers upgrade first, and it suits replay. FORWARD means an old reader can read new data, so producers upgrade first. FULL means both, so either order works, and it is the most restrictive. For a topic whose consumers I cannot sequence, I would lean to FULL, or to the transitive variants that check against all historical versions rather than only the previous one. This repo's baseline is the last release only, so it is not transitive. The escape hatch for a change that fits none of them is a `v2` topic with dual-write, a published deprecation window, and monitoring of `v1` consumer lag until it goes idle. Dual-write brings its own consistency problems, such as ordering across two topics, which the ADR does not discuss.

**Example:** `docs/adr/0005-topic-and-schema-versioning.md:22-23`
```
| FORWARD | old reader reads new data | producers first | ✗ |
| FULL | both | either | over-restrictive |
```
The ADR calls FULL over-restrictive for this system, which has one team controlling both sides.

**Code:** `docs/adr/0005-topic-and-schema-versioning.md:17-32` (the mode table and why BACKWARD), `docs/adr/0005-topic-and-schema-versioning.md:40-42` (dual-write and retirement of `v1`)

**If they push:** "What does transitive add?" It checks the new schema against every older version, not only the last one, so a reader cannot be broken by data written two releases ago.

---

### S3. Nothing enforces consumers-first deploy order here. How would you?

**Answer:** I would say plainly that nothing enforces it today. ADR-0005 defers it to Phase 11, and I found nothing in the Phase 11 spec or in `deploy/` that does it, so it remains a rule people have to follow. The options I would weigh are a release check that fails if a producer ships a schema that consumers have not been deployed against, a staged rollout with consumer lag and error monitoring, a canary consumer, or FULL compatibility, which removes the ordering constraint altogether. These are design options, not things I have built or run. One more gap: BACKWARD does not protect a rolled-back consumer reading newer data, because that is a FORWARD concern, so a rollback after a producer upgrade can still break.

**Example:** `docs/adr/0005-topic-and-schema-versioning.md:81-83`
```
- BACKWARD forbids some changes that are genuinely safe in context. The escape hatch is a
  new topic version, which is intentionally slightly painful.
- Nothing yet enforces the consumers-first deployment order. That is a Phase 11 concern.
```
The last bullet is the admission to repeat before an interviewer finds it.

**Code:** `docs/adr/0005-topic-and-schema-versioning.md:31-32` (the rule people have to follow), `docs/adr/0005-topic-and-schema-versioning.md:77-83` (the negatives)

---

### S4. Contract-first: what actually prevents drift?

**Answer:** It depends on the contract. Avro is genuinely contract-first: the `.avsc` generates the Java types, so Java drift is a compile error. OpenAPI is not yet: the documents are parsed and a few responses are asserted, but the controller is handwritten, and the spec promised generated interfaces that were not delivered. AsyncAPI is a document check only, and the compose `kafka-init` is a second hand-written source of truth for topics with nothing checking that they agree. The document lists eight topic addresses, `kafka-init` creates five, and I found no creation of `alerts.v1` or `activity.watch.v1` in the compose file, scripts or Makefile. With auto-create switched off, I have not run the stack to see how that plays out. Protobuf is checked only by `protoc` parsing it: there is no `buf lint` or `buf breaking`, so a field renumbering would go undetected.

**Example:** `docker/compose.yml:110-114`
```yaml
$$T --bootstrap-server $$B --create --if-not-exists --topic telemetry.raw.v1 --partitions 12 --replication-factor 1
$$T --bootstrap-server $$B --create --if-not-exists --topic telemetry.agg.v1 --partitions 6 --replication-factor 1
$$T --bootstrap-server $$B --create --if-not-exists --topic telemetry.raw.v1.dlt --partitions 3 --replication-factor 1
$$T --bootstrap-server $$B --create --if-not-exists --topic telemetry.agg.v1.dlt --partitions 3 --replication-factor 1
$$T --bootstrap-server $$B --create --if-not-exists --topic config.changes.v1 --partitions 3 --replication-factor 1 --config cleanup.policy=compact
```
Compare this list with the eight `address:` entries in the AsyncAPI file; the two are maintained by hand.

**Code:** `libs/contracts/src/test/java/com/streampulse/contracts/ApiContractTest.java:22-33` (what the document tests claim), `api/asyncapi/streampulse-topics.yaml:26` (the first address; the others are at lines 56, 81, 98, 125, 150, 171 and 200), `docker/compose.yml:79` (auto-create off)

**If they push:** "How would you close the topic gap?" Generate the topic list for `kafka-init` from the AsyncAPI file, or add a test that compares the two.

---

### S5. How would you catch OpenAPI drift here?

**Answer:** Today the check is thin. The contract test parses the ingest, query and control-plane documents and asserts a few things about the ingest endpoint: that it documents `202`, `400` and `429`, that `429` carries `Retry-After`, and that the `400` response uses `application/problem+json`. It does not assert `503`, even though the document now describes `503` for gateway saturation. The fourth document, alerts, is validated by a test in the alert-engine module. To catch drift I would validate real controller responses against the spec in controller tests, diff the spec between commits in CI to flag breaking changes, or generate the server interface so the compiler enforces it. I would also cover all four documents in one place. I have not verified which specific tools to use for the diff or the validation, so I would check the current docs before naming any.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/ApiContractTest.java:73-77`
```java
var responses = api.getPaths().get("/v1/events:batch").getPost().getResponses();

assertThat(responses.keySet())
        .as("the ingest endpoint must document backpressure and failure explicitly")
        .contains("202", "400", "429");
```
The list has no `503`, which is the gap to name.

**Code:** `api/openapi/ingest-v1.yaml:75-86` (the `429` and `503` responses), `services/alert-engine/src/test/java/com/streampulse/alert/AlertsApiContractTest.java:22-27` (the alerts document is validated elsewhere)

**If they push:** "Which direction does drift run today?" From code to spec: a controller change would not fail any test unless it broke one of those few assertions.

---

### S6. How would you evolve `telemetry.raw.v1` safely?

**Answer:** For additive changes I would only add new optional fields with defaults and new enum symbols with a default, which the gate accepts. A breaking change means a `v2` topic, dual-write, a published deprecation window, and watching `v1` consumer lag until it is idle. Raising the partition count is a breaking change too, because it rehashes keys and breaks per-key ordering, which is why 12 was chosen up front. Given S1, I should add the honest part: even additive changes need coordination in this repo, because the raw codec cannot resolve a newer writer against an older reader at runtime. I have designed this migration on paper only and have not run a `v1` to `v2` migration here.

**Example:** `docs/adr/0005-topic-and-schema-versioning.md:40-42`
```
With them, `v1` and `v2` run side by side: producers dual-write, consumers migrate at their
own pace, `v1` is retired when it is idle. A topic rename is cheap; a coordinated
organisation-wide deploy is not.
```
This is the plan; the word "dual-write" carries the consistency questions raised in S2.

**Code:** `docs/adr/0005-topic-and-schema-versioning.md:34-42` (versioned topics), `libs/contracts/src/test/java/com/streampulse/contracts/ApiContractTest.java:122-140` (every channel declares its partitions)

---

### S7. A green `SchemaCompatibilityTest` today: what does it prove?

**Answer:** Today it proves less than it looks like. All five baselines are byte-for-byte identical to the live schemas, which I checked with a file diff, so a green run currently shows only that each schema can read itself, which is also what the self-compatibility test says. The gate becomes meaningful at the next schema change, when the baseline is the previous release and the current file has moved. Even then, the baseline is the last release only, not every historical version, and nothing stops a developer copying the new schema over the baseline in the same commit as a breaking change. The README warns against that, and only review catches it. What the gate does prove independently is that it can fail, through the three fixtures, though only against the `TelemetryEvent` baseline.

**Example:** `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:220-225`
```java
void schemaIsCompatibleWithItself(String schemaName) throws IOException {
    // A sanity check on the checker: if a schema were not compatible with itself,
    // the test above would be meaningless.
    Schema s = parse(CURRENT_SCHEMA_DIR.resolve(schemaName + ".avsc"));
    assertThat(isCompatible(check(s, s))).isTrue();
}
```
While baseline equals current, the main gate reduces to this check.

**Code:** `libs/contracts/src/test/resources/baseline/README.md:9-22` (the manual update rule), `libs/contracts/src/test/java/com/streampulse/contracts/SchemaCompatibilityTest.java:141-162` (the gate-proof fixtures)

**If they push:** "How would you make the baseline harder to bypass?" Compare the baseline with the version on the main branch in CI and require a separate approval when it changes; I have not built that.

---

## Say this honestly

- No schema registry. Two ADRs and a milestone note still describe one as pending, and the Javadoc in `SchemaCompatibilityTest` says one arrives in Phase 2.
- The OpenAPI contract test does not assert `503` for ingest.
- The baselines currently equal the live schemas, so the gate presently proves "unchanged". The fixtures that prove it can fail are all derived from `TelemetryEvent`.
- The AsyncAPI document lists eight topic addresses and the compose `kafka-init` creates five; I found no creation of `alerts.v1` or `activity.watch.v1` there, and have not run the stack to see the effect.
- `RESUME-HERE.md` still says the OpenAPI file describes saturation as 429; it now documents 429 for quota
  and 503 for saturation. The old note is stale.
- The gRPC efficiency claims in ADR-0010 are expectations; the benchmark was never run.
- The 134-byte versus about 409-byte figure is one run on one machine, and the JSON side is the generated class's `toString()`.

## Stories and numbers

[What broke](../05-what-broke.md): story 11 (test totals). [Numbers](../06-numbers.md): event-size row.
