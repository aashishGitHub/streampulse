# MILESTONES

A build log of what was **actually run and observed** — not what was intended.

Convention borrowed from the author's `ticket-booking` project: every entry pastes real
terminal output. Where something was skipped, deferred or only partly verified, it says so.
A milestone log that only records successes is marketing, not engineering.

---

## Phase 0 — Foundation · 2026-09-28 · ✅ core complete

### Environment

```
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment Homebrew (build 21.0.12.1)
Docker version 29.5.3 · Docker Compose v5.1.4
Apple Silicon (arm64) · 32 GB RAM · 12 cores
```

### Gradle bootstrap

No Gradle was installed. The distribution was downloaded and its SHA-256 verified against
the value published by `services.gradle.org/versions/current` **before** being executed:

```
expected: bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
actual:   bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c
✅ CHECKSUM OK
Welcome to Gradle 9.8.0!
```

The same checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`, so every future
`./gradlew` invocation re-verifies the distribution.

> **Note on the first attempt:** the initial download produced a checksum mismatch. It was
> not tampering — `curl` without `-L` had saved a 169-byte HTTP 307 redirect page instead of
> the archive. Recorded because "checksum mismatch" should always be investigated rather
> than retried blindly.

### Build

```
$ ./gradlew build
BUILD SUCCESSFUL in 14s
```

39 tests pass:

```
ArchitectureRulesTest > no field injection anywhere                        PASSED
ArchitectureRulesTest > the domain layer stays free of framework imports   PASSED
ArchitectureRulesTest > inbound adapters go through the use case           PASSED
ArchitectureRulesTest > no java.util.Date or Calendar — use java.time      PASSED

TenantIdTest > normalisation > lowercasesAndTrims()                        PASSED
TenantIdTest > normalisation > two spellings of the same tenant are equal  PASSED
TenantIdTest > normalisation > toString is the bare value                  PASSED
TenantIdTest > accepts valid identifiers > acceptsWellFormed  [7 cases]    PASSED
TenantIdTest > rejects ... > rejectsMalformed                 [8 cases]    PASSED
TenantIdTest > rejects ... > rejectsNull / rejectsBlank / rejectsTooLong   PASSED
TenantIdTest > key layout > keyPrefixIsDefinedInExactlyOnePlace()          PASSED

EventIdTest  > validation > acceptsCanonicalUuid()                         PASSED
EventIdTest  > validation > uppercase input is normalised for dedup        PASSED
EventIdTest  > validation > rejectsMalformed                  [5 cases]    PASSED
EventIdTest  > validation > the original parse failure is preserved        PASSED
EventIdTest  > generation > randomIdsAreUnique()              [10k ids]    PASSED
EventIdTest  > idempotency key > isNamespacedByTenant()                    PASSED
EventIdTest  > idempotency key > sameEventDifferentTenantsDoNotCollide()   PASSED
```

### Quality gates — verified by breaking them

A gate nobody has seen fail is not a gate. Each was deliberately violated, the failure
observed, and the change reverted.

**1. Spotless catches misformatted code**

```
$ printf 'class    Bad  {int   x;}' >> LruCache.java && ./gradlew spotlessCheck
> The following files had format violations:
      src/main/java/com/streampulse/kata/cache/LruCache.java
  Run './gradlew :kata:spotlessApply' to fix these violations.
BUILD FAILED in 900ms
```

**2. The value-object tests catch a widened validation rule**

These gates were first verified against the algorithm katas that Phase 0 originally shipped
(commit `41deee0`); the katas were subsequently removed as out of scope, and the equivalent
gate now sits on the domain primitives. Deleting the `fresh > 0` guard in the old BFS code
produced:

```
RottingOrangesTest > the off-by-one trap > adjacent fresh orange takes exactly 1 minute, not 2 FAILED
    expected: 1  but was: 2
```

The *named* failure-mode test caught it first and most precisely — the argument for writing
tests against the specific bug rather than only the happy path. `TenantIdTest`'s eight
parameterized rejection cases serve the same purpose now: loosening the regex to permit a
`/` fails `rejectsMalformed[2] candidate=has/slash` immediately.

**3. ArchUnit catches an architecture violation**

Adding a class returning `java.util.Date`:

```
ArchitectureRulesTest > no java.util.Date or Calendar — use java.time FAILED
    Architecture Violation [Priority: MEDIUM] - Rule 'no classes should depend on classes
    that have fully qualified name 'java.util.Date' ...' was violated (2 times):
    Method <com.streampulse.platform.Temp.now()> calls constructor <java.util.Date.<init>()>
    Method <com.streampulse.platform.Temp.now()> has return type <java.util.Date>
```

### Problem hit and resolved

**Type-safe version-catalog accessors are unavailable inside precompiled script plugins.**
`libs.assertj` inside `build-logic` fails to compile with `Unresolved reference`. The widely
circulated workaround injects the generated `LibrariesForLibs` class via a `files(...)`
reflection trick.

Resolved instead with the documented `VersionCatalogsExtension` API. More verbose, but it
fails loudly naming the missing alias, and it teaches the model rather than a hack.
Documented in `specs/00-foundation/design.md` §3.

### Measurements

| NFR | Target | Observed |
|---|---|---|
| NFR-F0-01 incremental build | < 5 s | ~1 s (`UP-TO-DATE`) |
| NFR-F0-02 full build | < 3 min | 3 s warm · ~2.5 min first run (distribution + dependency download) |
| NFR-F0-03 no Docker needed | always | ✅ Docker not started for any Phase 0 task |

### Deferred to later phases

Honest scope notes:

- `docker/compose.yml` is **written but never started.** Phase 0 needs no containers; the
  images are declared so the system's shape is visible, and the tags need verification
  before first use.
- The CI workflow is **committed but has not run** — no remote configured yet.
- `integrationTest` source set arrives with Testcontainers in Phase 2.

### Scope change — algorithm katas removed

Phase 0 originally shipped a `kata` module (`LruCache`, `RottingOranges`) as a
coding-interview track. **Removed**: StreamPulse is for learning the Java ecosystem in a
cloud environment, and DSA practice is a different skill that was diluting the project's
focus.

The code remains in git history at commit `41deee0` if it is ever wanted, and the full
worked solutions live in `../Java/interview-qa/17-coding-lru-and-graphs.md`.

It was replaced by `TenantId` and `EventId` — value objects that are genuinely load-bearing
from Phase 2 onward, and that teach the same Java fundamentals (records, compact
constructors, validation at the boundary, `equals`/`hashCode` correctness) on material the
project actually uses.

```
$ ./gradlew build
BUILD SUCCESSFUL in 3s
```

### Next

Phase 1 — contracts and schemas. Spec first.

---

## Phase 1 — Contracts & Schemas · 2026-09-28 · ✅ complete

### What landed

`libs/contracts`: four Avro schemas generating 20 Java classes, a build-time schema-evolution
gate, three OpenAPI documents and an AsyncAPI topic contract.

```
$ ./gradlew clean build
BUILD SUCCESSFUL

$ ./gradlew test --rerun-tasks
67 tests passing
```

### Measured — event size

Real numbers from `SerializationRoundTripTest`, not estimates:

```
Heartbeat serialized size: 134 bytes
Avro 134 bytes vs JSON ~409 bytes (67% smaller)
```

NFR-C1-04 set a 400-byte budget for the highest-volume event; observed 134. The saving is
almost entirely from Avro not writing field names on every record. This is also the baseline
the Phase 9 compression work will be measured against.

### The schema-evolution gate — proven by breaking it

Added a required field (no default) to a released schema:

```
$ ./gradlew :libs:contracts:test
SchemaCompatibilityTest > TelemetryEvent can read data written by its baseline FAILED
    TelemetryEvent is NOT backward compatible with its baseline.
      - READER_FIELD_MISSING_DEFAULT_VALUE at /fields/10 (abTestBucket)

    Most likely you added a field without a default, renamed one, or narrowed a type.
    See specs/01-contracts/design.md section 4.
    If the change is genuinely intended, update the baseline in
    src/test/resources/baseline/ IN THE SAME COMMIT so a reviewer sees both halves.
```

Reverted afterwards. Three permanent fixtures under `src/test/resources/incompatible/`
(added-required-field, renamed-field, narrowed-type) keep the gate honest — if someone
weakens the check, those tests go green and the weakening is visible.

The counterpart test matters as much: adding a field **with** a default passes. The lesson is
not "adding fields is dangerous".

> **Note on the failure message.** Avro's own `toString` embeds both complete schemas in
> every incompatibility — roughly 10 KB of JSON per failure for our event model. The first
> version of this test printed that verbatim. It was replaced with a summary of type,
> location and field name, because a developer scrolling past 10 KB of JSON is a developer
> who stops reading test output.

### Problems hit and resolved

**Avro codegen took three attempts.** Recorded because each failed for a different and
non-obvious reason:

1. The community Avro Gradle plugin — Gradle 9 support unverified, and a learning project
   should not be blocked on a third party's release cadence.
2. `avro-tools` via `JavaExec` — **cannot be resolved at all**. Its 1.12.x POM declares
   `trevni-core-tests.jar` and `trevni-avro-tests.jar`, which were never published to Maven
   Central. The `nodeps` classifier avoids that but then needs `jopt-simple` and friends
   supplied by hand.
3. `SpecificCompiler.main` — defaults to *protocol* mode, so a record schema fails with
   "No protocol name specified".

Resolved with a custom `AvroCodegenTask` in `build-logic/` calling
`SpecificCompiler.compileSchema(File[], File)` directly. `@InputDirectory`/`@OutputDirectory`
make it incremental and cacheable. Documented in ADR-0004.

**Logical types generate `java.time.Instant`, not `long`.** `timestamp-millis` produces
`Instant` accessors, which broke the first draft of the round-trip test. Worth knowing: it
keeps generated code inside the "no `java.util.Date`" architecture rule for free.

### Design decisions recorded

- **ADR-0004** Avro for events — with the measured size comparison, and the Avro-resolves-by-name
  vs Protobuf-resolves-by-number contrast that explains why both are used.
- **ADR-0005** BACKWARD compatibility + versioned topic names + frozen baselines.
- Envelope + payload union on **one** topic, so per-session ordering survives.
- `sessionId` as partition key: ordering is a correctness property, repartitioning for
  per-title aggregation is only a performance cost.
- `config.changes.v1` is compacted, so its payload is a full snapshot and a delete is a
  null tombstone.

### Deferred

- Schema Registry — Phase 2. The build-time gate needs no infrastructure; the registry adds
  runtime enforcement.
- TypeScript client generation from OpenAPI — Phase 7, with the frontend.
- Protobuf/gRPC contracts — Phase 2, with the gRPC adapter.
- Topics are **documented but not created**. Phase 2 creates them from the AsyncAPI document.

### Next

Phase 2 — ingest gateway. Spring Boot enters the build for the first time, which is also
where the Gradle 9 / Spring Boot plugin compatibility risk gets tested in isolation.
