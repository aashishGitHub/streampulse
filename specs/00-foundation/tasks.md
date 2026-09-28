# Phase 0 — Foundation: Tasks

Each task is: **write the check first, then make it pass.** Requirement IDs refer to
[`requirements.md`](requirements.md).

---

## Build foundation

- [x] **T0-01** Create repo, `git init`, `.gitignore` (Gradle, IDE, OS, secrets). → REQ-F0-01
- [x] **T0-02** Write `gradle/libs.versions.toml` with stable versions only. → REQ-F0-04
  - Verified each version is a stable release, not `-M`/`-RC`.
- [x] **T0-03** Write `settings.gradle.kts`: composite build, `PREFER_SETTINGS`, modules.
  → REQ-F0-05, REQ-F0-06
- [x] **T0-04** Write `build-logic` with the `java-conventions` plugin. → REQ-F0-03, F0-07
  - Hit the known "catalog accessors unavailable in precompiled script plugin" issue;
    resolved with the documented `VersionCatalogsExtension` API rather than the reflection
    workaround. Documented in `design.md` §3.
- [x] **T0-05** Generate the Gradle wrapper **with** `--gradle-distribution-sha256-sum`.
  → REQ-F0-02
  - Downloaded the distribution, verified its SHA-256 against `services.gradle.org` before
    using it to generate the wrapper.
- [x] **T0-06** Add the `libs:platform` module build file. → REQ-F0-06
- [x] **T0-07** Confirm `./gradlew build` succeeds end to end. → REQ-F0-01

## Quality gates

- [x] **T0-08** Configure Spotless + palantir-java-format. → REQ-F0-08
- [x] **T0-09** Configure JaCoCo to run after every test task. → REQ-F0-09
- [x] **T0-10** Write the four ArchUnit rules with `allowEmptyShould(true)`. → REQ-F0-10/11
- [ ] **T0-11** Verify `spotlessCheck` **fails** on deliberately misformatted code.
  *(A quality gate nobody has seen fail is not a quality gate.)*
- [ ] **T0-12** Verify an ArchUnit rule **fails** when violated, then revert.

## Domain primitives

- [x] **T0-13** `TenantId`: validated record, `Locale.ROOT` normalisation, key prefix.
  → REQ-F0-12/13/14
- [x] **T0-14** `TenantIdTest`: parameterized **rejection** cases for every character class
  that would break a downstream key. → REQ-F0-12/17
- [x] **T0-15** `EventId`: canonical UUID, client-generated, tenant-namespaced idempotency
  key. → REQ-F0-15/16
- [x] **T0-16** `EventIdTest`: malformed input, case normalisation, cause preservation,
  cross-tenant key isolation. → REQ-F0-16/17

## Infrastructure scaffolding

- [ ] **T0-17** `Makefile` with a self-documenting `help` target.
- [ ] **T0-18** `docker/compose.yml` with profiles (declared now, exercised in Phase 2).
- [ ] **T0-19** GitHub Actions workflow: build + test on push.
- [ ] **T0-20** `CLAUDE.md` so AI assistance follows the repo's conventions.

## Documentation

- [x] **T0-21** ADR-0001 multi-module single repo.
- [x] **T0-22** ADR-0002 Gradle + Kotlin DSL.
- [x] **T0-23** ADR-0003 local AWS simulation strategy.
- [ ] **T0-24** `docs/learning/00-how-to-read-this-repo.md`.
- [ ] **T0-25** `docs/learning/01-gradle-for-dotnet-devs.md`.
- [ ] **T0-26** Paste verified command output into `MILESTONES.md`.

> **Out of scope:** algorithm/DSA practice was removed from this project. StreamPulse is
> for learning the Java ecosystem in a cloud environment; coding-round preparation lives in
> `../Java/interview-qa/17-coding-lru-and-graphs.md`.

---

## Definition of done for Phase 0

Every box above that is not explicitly deferred is ticked, the acceptance checklist in
`requirements.md` passes, and `MILESTONES.md` contains **real terminal output** — not a
claim that it worked.
