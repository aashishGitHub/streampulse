# Phase 0 — Foundation: Requirements

**Status:** in progress
**Depends on:** nothing
**Delivers:** a build you can trust, and the domain primitives every service will use

---

## Why this phase exists

Every later phase adds a service. If the build, the toolchain or the test wiring is shaky,
every one of those phases inherits the shakiness — and you end up debugging Gradle when you
meant to be learning Kafka.

So Phase 0 has a deliberately narrow goal: **prove the foundation works on plain Java,
before any framework is involved.** No Spring, no Docker, no Kafka. If something fails
here, there is nothing else in the room.

It also lands the two value objects that every later phase depends on — `TenantId` and
`EventId`. They are small enough to belong in a foundation phase and load-bearing enough
that getting them wrong would contaminate everything built on top.

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
- **As a developer**, I want tenant and event identifiers to be validated types rather than
  bare strings, so that a swapped argument is a compile error instead of a cross-tenant data
  leak.

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

### Domain primitives

- **REQ-F0-12** THE SYSTEM SHALL provide a `TenantId` value type that validates its input
  and rejects anything that would be unsafe as a Kafka record key, Redis key prefix,
  DynamoDB partition key, S3 object prefix or Prometheus label value.
- **REQ-F0-13** `TenantId` SHALL normalise by trimming and lowercasing with an explicit
  `Locale.ROOT`, so that the same input produces the same identifier on every machine.
- **REQ-F0-14** `TenantId` SHALL expose the shared key prefix in exactly one place, so that
  the storage key layout is defined once rather than string-concatenated at each call site.
- **REQ-F0-15** THE SYSTEM SHALL provide an `EventId` value type wrapping a canonical UUID,
  which serves as the pipeline's idempotency key, and which is **client-generated** so that
  a retried request carries the same id as the original.
- **REQ-F0-16** `EventId` SHALL produce an idempotency key namespaced by `TenantId`, so that
  one tenant's traffic can neither collide with nor evict another's in a shared cache.
- **REQ-F0-17** WHEN an identifier is malformed THE SYSTEM SHALL throw
  `IllegalArgumentException` naming the offending value, and SHALL preserve the underlying
  parse failure as the cause.

---

## Non-functional requirements

| ID | Requirement | Target | How verified |
|---|---|---|---|
| NFR-F0-01 | Incremental build with no changes | < 5 s | `./gradlew build` twice; second run is `UP-TO-DATE` |
| NFR-F0-02 | Full clean build on this machine | < 3 min | `./gradlew clean build`, timed |
| NFR-F0-03 | Phase 0 tests need no Docker, no network | always | run with Docker stopped |
| NFR-F0-04 | Build is reproducible across machines | — | toolchain + pinned wrapper + locked versions |

> These are **targets chosen for this exercise**, not measurements. `MILESTONES.md` records
> what was actually observed.

---

## Out of scope for Phase 0

Deliberately excluded, to keep the failure surface small:

- Spring Boot (Phase 2) · Docker (Phase 2) · Kafka (Phase 2) · CI beyond a build job
- Algorithm/DSA practice — **out of scope for this project entirely.** StreamPulse is for
  learning the Java ecosystem in a cloud environment. Coding-round preparation lives in
  `../Java/interview-qa/17-coding-lru-and-graphs.md`
- The full telemetry event model and its Avro schema (Phase 1)

---

## Acceptance checklist

- [ ] `./gradlew build` succeeds on a clean clone with only JDK 21
- [ ] `./gradlew build` a second time reports `UP-TO-DATE` for unchanged tasks
- [ ] `gradle-wrapper.properties` contains `distributionSha256Sum`
- [ ] No module build file contains a literal dependency version
- [ ] `./gradlew spotlessCheck` passes; deliberately misformatting a file makes it fail
- [ ] JaCoCo HTML report exists at `libs/platform/build/reports/jacoco/test/html/index.html`
- [ ] All four architecture rules execute and pass
- [ ] `TenantIdTest` and `EventIdTest` pass, including every rejection case
- [ ] Verified command output pasted into `MILESTONES.md`
