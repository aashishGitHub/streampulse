# Phase 0 — Foundation: Design

Implements [`requirements.md`](requirements.md). ADRs:
[0001](../../docs/adr/0001-multi-module-single-repo.md) ·
[0002](../../docs/adr/0002-gradle-kotlin-dsl.md) ·
[0003](../../docs/adr/0003-local-aws-simulation.md)

---

## 1. Build architecture

```
  ┌──────────────────────────────────────────────────────────────┐
  │  settings.gradle.kts        WHICH modules exist               │
  │    ├── pluginManagement { includeBuild("build-logic") }       │
  │    ├── dependencyResolutionManagement (PREFER_SETTINGS)       │
  │    └── include("libs:platform")                               │
  └───────────────────────────┬──────────────────────────────────┘
                              │ compiled FIRST (composite build)
  ┌───────────────────────────▼──────────────────────────────────┐
  │  build-logic/                                                 │
  │    streampulse.java-conventions.gradle.kts                    │
  │      • java-library plugin   (api vs implementation)          │
  │      • toolchain = Java 21   (Gradle provisions the JDK)      │
  │      • JUnit 5 + AssertJ + Mockito + ArchUnit on test cp      │
  │      • JaCoCo, wired to run after every test task             │
  │      • Spotless + palantir-java-format                        │
  │      • -Xlint:all  -parameters                                │
  └───────────────────────────┬──────────────────────────────────┘
                              │ applied by id
                              │ applied by id
                              ▼
                     ┌──────────────────────┐
                     │  libs:platform       │  shared kernel
                     │  • TenantId, EventId │  → Spring Boot starter (Phase 2)
                     │  • ArchUnit rules    │
                     └──────────────────────┘

  Services (ingest-gateway, stream-processor, sink-writer, query-api,
  control-plane) are added from Phase 2 onward, each applying the same
  convention plugin.
```

**Why a composite build rather than `buildSrc`:** a change to `buildSrc` invalidates the
whole build cache; an included build is cached independently. `buildSrc` is also implicitly
on every module's classpath, which hides coupling.

**Why convention plugins rather than `subprojects { }`:** `subprojects` pushes configuration
*down* from the root, so a module cannot be understood without reading the root file, and it
breaks Gradle's configuration cache. Convention plugins make a module **opt in** with one
readable line.

---

## 2. Flow of control: what `./gradlew build` actually does

```
  ./gradlew build
      │
      ├─ 1. gradle-wrapper.jar reads gradle-wrapper.properties
      │     └─ downloads gradle-9.8.0-bin.zip if absent
      │        └─ VERIFIES SHA-256 against distributionSha256Sum  ← supply-chain gate
      │
      ├─ 2. INITIALIZATION  settings.gradle.kts runs
      │     ├─ build-logic is configured and COMPILED
      │     └─ the module graph is established
      │
      ├─ 3. CONFIGURATION   every build.gradle.kts runs top-to-bottom
      │     ├─ conventions applied → toolchain resolved (JDK 21 downloaded if needed)
      │     └─ a TASK GRAPH is built. No task has executed yet.
      │
      └─ 4. EXECUTION       tasks run in dependency order, in parallel where possible
            compileJava → processResources → classes → compileTestJava
                       → test → jacocoTestReport → spotlessCheck → check → build
```

The single most useful thing to internalise: **configuration runs on every invocation**, even
`./gradlew help`. Putting real work directly in a build script (rather than inside a task)
makes every build slower forever. This is the root cause of most "Gradle is slow" complaints.

---

## 3. Version management

```
  gradle/libs.versions.toml          ← THE only place versions are written
        │
        ├── [versions]   java = "21", junit = "5.12.2", ...
        ├── [libraries]  alias → group:artifact + version.ref
        ├── [bundles]    testing = [junit, assertj, mockito, awaitility]
        └── [plugins]    spotless
                │
    ┌───────────┴────────────┐
    ▼                        ▼
  module build files      convention plugin
  `libs.assertj`          extensions.getByType<VersionCatalogsExtension>()
  (type-safe accessors)   (documented API — accessors don't exist here)
```

**The catalog-in-convention-plugin gotcha.** Type-safe `libs.` accessors are generated per
project and are *not* available inside a precompiled script plugin. The popular workaround
injects the generated `LibrariesForLibs` class via a reflection trick; we deliberately use
the ordinary `VersionCatalogsExtension` API instead. It is more verbose, but it fails loudly
with the missing alias named, and it teaches the model rather than a hack.

---

## 4. Domain primitives

Two value objects that every later phase uses. They are in `libs:platform` because they are
shared kernel — not owned by any one service.

### Why types instead of `String`

```java
void publish(String tenantId, String titleId) { ... }
publish(titleId, tenantId);   // compiles fine. ships. leaks data across tenants.
```

With distinct types that call does not compile. In a multi-tenant system the tenant id
decides **data isolation**, so a swapped argument is not a bug — it is a cross-tenant leak.
A type is the cheapest available control against the most expensive possible failure.

Validating once at the boundary also means every layer downstream can assume well-formed
input: no defensive re-checking, no "has this been lowercased yet?" uncertainty.

### `TenantId`

```
   input  "  StreamFlix  "
      │
      ├─ trim + toLowerCase(Locale.ROOT)   ← explicit locale: with a Turkish default,
      │                                       "I".toLowerCase() is a dotless "ı" and the
      │                                       same input yields different ids per machine
      ├─ reject blank
      ├─ reject length > 32
      └─ reject ^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$ mismatch
      │
   value  "streamflix"  ──▶ safe as: Kafka key · Redis prefix · DynamoDB PK
                                     S3 prefix · Prometheus label
```

The character set is deliberately narrow. A tenant id containing `/`, a space or `#` would
break at least one of those five sinks — in production, months later, for one customer.

Normalisation happens **before** validation, so `" StreamFlix "` is accepted and stored
canonically. Doing it the other way round would reject values we intend to allow.

### `EventId` — why the client generates it

```
   player ──[event, id=abc]──▶ ingest ──▶ Kafka
      │                           │
      │      ✗ the RESPONSE is lost (the write already succeeded)
      │
      └──[event, id=abc]──▶ ingest ──▶ "seen abc" → 200 OK, no duplicate
              retry
```

If the **server** minted the id, the retry would be a different event and the duplicate
would be undetectable. Because the client generates it once and reuses it across retries,
at-least-once delivery plus a dedup check becomes **effectively-once** processing. Same
reasoning as Stripe's `Idempotency-Key`.

It is stored as a canonical lowercase string, not a `UUID` object: the value crosses Avro,
JSON and protobuf boundaries as text, so re-parsing at every hop is wasted work. It is
validated once, here, by round-tripping through `UUID.fromString` — a hand-rolled regex
would accept input the JDK rejects.

### Why `record`

Records give a canonical constructor, accessors, `equals`, `hashCode` and `toString` for
free and correct, and are implicitly final and shallowly immutable. Correct `equals`/
`hashCode` is what makes these safe as `HashMap` keys — asserted directly in the tests.

The **compact constructor** (`TenantId { ... }` with no parameter list) runs before fields
are assigned, so reassigning the parameter changes what gets stored. That is where
normalisation and validation belong.

> **.NET note:** close to a C# `record`, with two differences. Java accessors are methods —
> `tenantId.value()`, not `.Value`, since Java has no properties. And there is no `with`
> expression; you construct a new instance explicitly.

## 5. Architecture rules

Rules live in `libs:platform` and run as ordinary tests. Each is written with
`allowEmptyShould(true)` so it passes on today's near-empty codebase and starts policing
real classes as later phases add them — rules arrive *before* the code they constrain,
which is the only ordering that works.

| Rule | Guards against | Becomes load-bearing in |
|---|---|---|
| no field injection | hidden dependencies, untestable classes | Phase 2 |
| domain imports no framework | the hexagon collapsing into layers | Phase 2 |
| inbound ⇏ outbound adapters | controllers bypassing the use case | Phase 2 |
| no `java.util.Date` | mutable time, untestable clocks | Phase 3 |

---

## 6. Test plan

| Level | What | Tooling | Needs Docker |
|---|---|---|---|
| unit | `TenantId`, `EventId` — mostly **rejection** cases | JUnit 5 + AssertJ | no |
| architecture | the four rules above | ArchUnit | no |
| build | wrapper, toolchain, catalog, spotless | manual + CI | no |

Phase 0 deliberately has no integration tier — there is nothing to integrate with yet.
Testcontainers arrives in Phase 2.

---

## 7. Failure modes considered

| Failure | Mitigation |
|---|---|
| Gradle distribution swapped or corrupted | SHA-256 pinned in `gradle-wrapper.properties` |
| Developer on a different JDK | toolchain declares 21; Gradle provisions it |
| Versions drift between modules | single version catalog; no literal versions allowed |
| A module pulls from an untrusted repo | `RepositoriesMode.PREFER_SETTINGS` |
| Architecture decays silently | rules are failing tests, not wiki pages |
| Formatting arguments in review | Spotless applies mechanically; CI checks |
