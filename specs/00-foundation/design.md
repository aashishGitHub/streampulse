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
  │    └── include("kata"), include("libs:platform")              │
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
        ┌─────────────────────┴─────────────────────┐
        ▼                                           ▼
  ┌───────────┐                             ┌──────────────┐
  │  kata     │  plain Java algorithms      │ libs:platform│  future starter
  │           │  LruCache, RottingOranges   │              │  ArchUnit rules
  └───────────┘                             └──────────────┘
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

## 4. Kata design

### LruCache — why two data structures

| Requirement | Structure that provides it in O(1) |
|---|---|
| look up a value by key | hash map |
| know which key is least recently used | ordered list |
| move an entry to most-recent | **doubly** linked list |

```
  map:  {"a" → ●,  "b" → ●,  "c" → ●}
               │        │        │
               ▼        ▼        ▼
  head ⇄ [ c ] ⇄ [ b ] ⇄ [ a ] ⇄ tail
         MRU                LRU
```

Two design decisions worth defending out loud:

1. **Doubly, not singly, linked.** Unlinking a node requires its predecessor. In a singly
   linked list that search is O(n), which would make `get` O(n) and defeat the point.
2. **Sentinel head and tail.** They are never removed, so every real node always has both
   neighbours, and link/unlink have *no* null checks and *no* special cases. Two wasted
   objects buy a provably simpler implementation.

The node stores its own key so eviction can remove it from the map — a detail people forget,
producing a map that grows forever while the list stays bounded.

### RottingOranges — multi-source BFS

The instinct is one BFS per rotten orange, taking the minimum: O(sources × cells). Instead,
**seed every source into the queue before the loop**. All sources are at distance 0, BFS
expands them in lockstep, and the first time a cell is reached is automatically via its
nearest source. One pass, O(cells).

Two bugs this design guards against, both encoded as tests:

| Bug | Guard |
|---|---|
| counting nodes instead of minutes | snapshot `queue.size()` per level and drain exactly that many |
| returning one minute too many | the `fresh > 0` loop guard — the last level adds nothing, so no minute passed |

Cells are marked rotten **on enqueue, not on dequeue**. The grid doubles as the visited set,
so no `boolean[][]` is needed; marking late would let two neighbours enqueue the same cell in
one minute and corrupt the fresh count.

---

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
| unit | `LruCache`, `RottingOranges` | JUnit 5 + AssertJ | no |
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
