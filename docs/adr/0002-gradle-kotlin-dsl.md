# ADR-0002 — Gradle with the Kotlin DSL

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 0

## Context

A JVM build tool is needed. The developer's background is .NET/MSBuild, so the learning cost
of each option is a real factor — but so is what a prospective employer's codebase uses.

## Decision

**Gradle 9.8.0 with the Kotlin DSL (`.gradle.kts`)**, convention plugins in a composite
`build-logic` build, and a version catalog.

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| **Gradle + Kotlin DSL** (chosen) | type-safe build scripts with IDE autocomplete; incremental builds and build cache; toolchain auto-provisioning; convention plugins scale to many modules | steeper learning curve; more ways to do it wrong |
| Gradle + Groovy DSL | more StackOverflow answers | dynamic typing, no autocomplete; errors surface at runtime |
| Maven | ubiquitous in enterprise Spring shops; XML is unambiguous; interviewers read `pom.xml` instantly | verbose; customisation means writing a plugin; slower for large multi-module builds |

## Consequences

**Positive**
- The toolchain feature pins the JDK per project and downloads it if missing — this alone
  eliminates a whole class of environment problems.
- Convention plugins express "our house style" once. Twelve modules later, changing the
  Java version is a one-line edit.
- The version catalog gives central package management (the `Directory.Packages.props`
  equivalent) with type-safe accessors.

**Negative**
- Kotlin DSL has real sharp edges. We hit one immediately: type-safe catalog accessors do
  not exist inside precompiled script plugins. Documented in `specs/00-foundation/design.md`
  §3 rather than worked around silently.
- Enterprise Spring shops still lean Maven. **If the target company uses Maven, be able to
  read a `pom.xml` fluently** — the concepts transfer, the syntax does not.

**Verify:** Spring Boot's Gradle plugin compatibility with Gradle 9.x before Phase 2. Phase 0
is deliberately plain Java, which defers this risk to a phase where it can be tested in
isolation.

## Interview relevance

Expect "Maven or Gradle, and why?". Strong answer: name the trade-off (convention and
ubiquity vs flexibility and speed), then show you know Gradle's genuinely differentiating
features — toolchains, build cache, convention plugins — rather than just "Gradle is faster".
