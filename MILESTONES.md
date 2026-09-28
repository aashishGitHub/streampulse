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
