# ADR-0001 — Multi-module single repository

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 0

## Context

StreamPulse will grow to roughly five deployable services plus shared libraries, a React
frontend and infrastructure code. They share Avro schemas, an OpenAPI contract, an error
model and cross-cutting concerns such as tracing and idempotency.

Three options were considered.

## Decision

**One Git repository containing multiple Gradle modules**, each service its own module,
with shared code in `libs/`.

## Alternatives considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **Multi-module monorepo** (chosen) | atomic cross-service changes; one build; shared contracts cannot drift; trivial local setup | one CI pipeline can get slow; needs discipline to keep boundaries real | ✅ |
| Repo per service | independent deploy cadence; small CI; clear ownership | a schema change spans N pull requests; version skew between producer and consumer; painful local dev | ❌ for a learning project — the coordination cost buys nothing here |
| Single monolithic module | simplest possible build | no enforced boundaries; cannot demonstrate a distributed system, which is the entire point | ❌ |

## Consequences

**Positive**
- A contract change and both sides of its usage land in one commit — genuinely hard to do
  safely across repositories.
- One `./gradlew build` proves the whole system compiles together.
- Module boundaries are enforced by ArchUnit rather than by repository walls, which makes
  the boundaries *visible in tests*.

**Negative**
- The build will get slower as modules accumulate. Mitigations: Gradle's build cache,
  `--parallel`, and running only affected modules in CI.
- Nothing physically prevents a module reaching into another's internals. This is why the
  ArchUnit rules exist from Phase 0 rather than being added later.

**Revisit if:** the build exceeds ~10 minutes in CI, or genuinely independent release
cadences become necessary. Neither applies to a single-author learning project.

## Interview relevance

"Monorepo or polyrepo?" is a common system-design probe. The senior answer is not a
preference — it is naming the trade-off (**coordination cost vs deployment independence**)
and tying the choice to team size and release cadence. Here: one author, tightly coupled
contracts, so coordination cost dominates.
