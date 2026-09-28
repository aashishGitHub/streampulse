# Phase 0 — Foundation: Requirements

**Status:** in progress
**Depends on:** nothing
**Delivers:** a build you can trust, and the first algorithms with tests

---

## Why this phase exists

Every later phase adds a service. If the build, the toolchain or the test wiring is shaky,
every one of those phases inherits the shakiness — and you end up debugging Gradle when you
meant to be learning Kafka.

So Phase 0 has a deliberately narrow goal: **prove the foundation works on plain Java,
before any framework is involved.** No Spring, no Docker, no Kafka. If something fails
here, there is nothing else in the room.

It also starts the kata track, which runs in parallel through every later phase, because
coding-round practice needs repetition over weeks rather than a cram at the end.

---

## User stories

- **As a developer**, I want to clone the repo and run `./gradlew build` successfully
  without installing Gradle, so that onboarding is one command.
- **As a developer**, I want the same JDK used on my Mac, a colleague's Linux box and CI,
  so that "works on my machine" cannot happen.
- **As a developer**, I want dependency versions declared in exactly one file, so that
  upgrades are a one-line change and versions cannot drift between modules.
- **As a developer**, I want formatting applied mechanically, so that no code review ever
  again contains the comment "nit: spacing".
- **As an architect**, I want architectural rules enforced by failing tests rather than by
  documentation, so that the design cannot silently decay.
- **As an interview candidate**, I want the LRU cache and multi-source BFS implemented and
  tested, so that I can practise against something that actually runs.

---

## Functional requirements

### Build

- **REQ-F0-01** WHEN a developer runs `./gradlew build` on a clean clone with only JDK 21
  installed THE SYSTEM SHALL compile all modules and run all tests without requiring a
  separate Gradle installation.
- **REQ-F0-02** THE SYSTEM SHALL pin the Gradle distribution by SHA-256 in
  `gradle-wrapper.properties`, so a compromised or swapped distribution fails the build
  rather than executing.
- **REQ-F0-03** THE SYSTEM SHALL declare the Java toolchain as version 21, and Gradle SHALL
  provision that JDK if it is absent rather than using whatever `java -version` reports.
- **REQ-F0-04** THE SYSTEM SHALL declare every third-party version in
  `gradle/libs.versions.toml` and nowhere else. No module build file may contain a literal
  version string.
- **REQ-F0-05** THE SYSTEM SHALL forbid modules from declaring their own repositories
  (`RepositoriesMode.PREFER_SETTINGS`), so no module can quietly resolve a jar from an
  unreviewed source.
- **REQ-F0-06** THE SYSTEM SHALL apply shared build configuration through convention
  plugins in `build-logic/`, not through `allprojects`/`subprojects` blocks.
- **REQ-F0-07** WHEN any module is compiled THE SYSTEM SHALL pass `-parameters`, so that
  frameworks reading parameter names at runtime behave correctly from Phase 2 onward.

### Quality gates

- **REQ-F0-08** WHEN `./gradlew spotlessCheck` runs against unformatted code THE SYSTEM
  SHALL fail the build and name the offending files.
- **REQ-F0-09** WHEN `./gradlew test` completes THE SYSTEM SHALL produce a JaCoCo coverage
  report in both XML and HTML without a separate command.
- **REQ-F0-10** THE SYSTEM SHALL enforce, as executable tests: no field injection; the
  domain layer imports no framework types; inbound adapters do not depend on outbound
  adapters; `java.util.Date`/`Calendar` are not used.
- **REQ-F0-11** Architecture rules SHALL be written so they pass on an empty codebase
  (`allowEmptyShould(true)`) and begin policing real classes as later phases add them.

### Kata

- **REQ-F0-12** THE SYSTEM SHALL provide an `LruCache` with O(1) `get` and `put`,
  implemented with a hash map plus a doubly linked list, **not** `LinkedHashMap`.
- **REQ-F0-13** `LruCache` SHALL treat both `get` and a `put` on an existing key as an
  access that protects the entry from eviction, and `containsKey` as a peek that does not.
- **REQ-F0-14** `LruCache` SHALL reject a capacity ≤ 0 at construction.
- **REQ-F0-15** THE SYSTEM SHALL provide `RottingOranges.minutesUntilAllRot` implementing
  multi-source BFS, returning the elapsed minutes, `0` when no fresh cells exist, and `-1`
  when any fresh cell is unreachable.
- **REQ-F0-16** Kata tests SHALL assert eviction **order** and BFS **level counting**
  explicitly, not merely final membership, because those are where the real bugs are.

---

## Non-functional requirements

| ID | Requirement | Target | How verified |
|---|---|---|---|
| NFR-F0-01 | Incremental build with no changes | < 5 s | `./gradlew build` twice; second run is `UP-TO-DATE` |
| NFR-F0-02 | Full clean build on this machine | < 3 min | `./gradlew clean build`, timed |
| NFR-F0-03 | Kata tests need no Docker, no network | always | run with Docker stopped |
| NFR-F0-04 | Build is reproducible across machines | — | toolchain + pinned wrapper + locked versions |

> These are **targets chosen for this exercise**, not measurements. `MILESTONES.md` records
> what was actually observed.

---

## Out of scope for Phase 0

Deliberately excluded, to keep the failure surface small:

- Spring Boot (Phase 2) · Docker (Phase 2) · Kafka (Phase 2) · CI beyond a build job
- The remaining kata problems (LFU, Word Ladder, Clone Graph) — added as the kata track
  continues alongside later phases

---

## Acceptance checklist

- [ ] `./gradlew build` succeeds on a clean clone with only JDK 21
- [ ] `./gradlew build` a second time reports `UP-TO-DATE` for unchanged tasks
- [ ] `gradle-wrapper.properties` contains `distributionSha256Sum`
- [ ] No module build file contains a literal dependency version
- [ ] `./gradlew spotlessCheck` passes; deliberately misformatting a file makes it fail
- [ ] JaCoCo HTML report exists at `kata/build/reports/jacoco/test/html/index.html`
- [ ] All four architecture rules execute and pass
- [ ] `LruCacheTest` and `RottingOrangesTest` pass, including the order/level assertions
- [ ] Verified command output pasted into `MILESTONES.md`
