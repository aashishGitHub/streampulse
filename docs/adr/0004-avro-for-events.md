# ADR-0004 — Avro for event serialization

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 1

## Context

Events cross a Kafka topic between services that deploy independently. The encoding must be
compact (a heartbeat per player every ~10s dominates cost), must evolve without coordinated
deploys, and must be enforceable in CI.

## Decision

**Apache Avro** for Kafka messages, with **BACKWARD** compatibility enforced at build time.
Protobuf is used separately in Phase 2 for the gRPC edge.

## Alternatives considered

| | Avro (chosen) | Protobuf | JSON + JSON Schema |
|---|---|---|---|
| Size | binary, no field names on the wire | binary, field numbers | text, names repeated per record |
| Resolution | by **name** | by **field number** | by name |
| Rename a field | ❌ breaking | ✅ free | ❌ breaking |
| Reuse a removed identifier | n/a (no numbers) | ☠️ silent corruption | n/a |
| Schema travels with data | ✅ (id, via registry) | ❌ out of band | ❌ |
| Kafka ecosystem | first-class; Schema Registry, Kafka Streams serdes | supported | supported |
| Human-readable | no | no | yes |

We measured the size difference on our own event rather than quoting one:

```
Heartbeat: Avro 134 bytes vs JSON ~409 bytes  (67% smaller)
```

**Why not JSON**, despite being debuggable: it repeats every field name on every record. At
the volume a heartbeat implies, that is the single largest avoidable cost in the pipeline,
and it buys only convenience that `kafka-avro-console-consumer` already provides.

**Why not Protobuf for Kafka**, given we use it for gRPC: Avro is the better fit *inside*
Kafka. Schema Registry integration is first-class, Kafka Streams serdes are built for it,
and the writer's schema travels with the data by id — so a consumer can always decode a
record even if the producer has since moved on.

**Using both is a deliberate choice, not an accident.** They resolve differently, and the
contrast is worth stating:

- **Avro resolves by name.** Renaming breaks; there are no numbers to reuse.
- **Protobuf resolves by number.** Renaming is free; reusing a retired number silently
  corrupts data.

So each format's dangerous operation is the other's safe one.

## Consequences

**Positive**
- Compact on the wire; measured, not assumed.
- Compatibility is machine-checkable — `SchemaCompatibility` ships inside Avro, so the gate
  runs as a unit test with no registry and no Docker.
- Generated classes mean a field typo is a compile error.
- Logical types map `timestamp-millis` to `java.time.Instant`, so we get a real temporal
  type on both sides and stay inside the "no `java.util.Date`" architecture rule.

**Negative**
- Not human-readable. Mitigation: Kafka UI in the `ui` compose profile decodes it.
- Codegen adds a build step. We wrote it as a custom task (see below).
- **Renaming a field is a breaking change.** This surprises people coming from Protobuf and
  is the most common cause of a failing compatibility gate.
- Two serialization formats in one system is real cognitive load. Justified by the different
  jobs, but it is a cost.

## Implementation note: why a custom codegen task

The usual Avro Gradle plugin's Gradle 9 support is unverified. Driving the CLI with
`JavaExec` was tried and rejected for two concrete reasons:

- `avro-tools` 1.12.x cannot be resolved at all — its POM declares `trevni-core-tests.jar`
  and `trevni-avro-tests.jar`, which were never published to Maven Central.
- `SpecificCompiler.main` defaults to *protocol* mode, so a record schema fails with
  "No protocol name specified".

`SpecificCompiler.compileSchema(File[], File)` is the API underneath both. `AvroCodegenTask`
in `build-logic/` calls it directly: fewer moving parts, real error messages, and its
`@InputDirectory`/`@OutputDirectory` annotations make it incremental and cacheable.

**Verify:** whether the Avro Gradle plugin has since gained Gradle 9 support, and whether the
avro-tools packaging bug is fixed in a later release. If both, switching back would reduce
the code we maintain.
