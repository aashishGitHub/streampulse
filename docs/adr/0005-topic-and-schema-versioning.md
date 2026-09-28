# ADR-0005 — Topic versioning and BACKWARD compatibility

**Status:** accepted · **Date:** 2026-09-28 · **Phase:** 1

## Context

Producers and consumers deploy independently. Something must decide what "a compatible
change" means and enforce it — otherwise the definition is whatever the last person to touch
the schema believed.

## Decision

1. **BACKWARD** compatibility for all event schemas.
2. **Major version in the topic name** (`telemetry.raw.v1`).
3. Compatibility checked at **build time** against a **frozen baseline** committed to git.

## Why BACKWARD

| Mode | Guarantees | Upgrade order | Fits us? |
|---|---|---|---|
| **BACKWARD** | new reader reads old data | **consumers first**, then producers | ✅ |
| FORWARD | old reader reads new data | producers first | ✗ |
| FULL | both | either | over-restrictive |
| NONE | nothing | — | ✗ |

History is retained and replayed here: seven days on the raw topic, indefinitely in the S3
archive. A newly deployed consumer must be able to read **everything already written**, which
is precisely BACKWARD. It is also Confluent's default, so it matches what an interviewer
expects.

The operational consequence is a rule the team has to follow: **deploy consumers before
producers.** Worth stating explicitly, because BACKWARD only protects you if you honour it.

## Why the version is in the topic name

Some changes cannot be made compatibly — splitting a field, changing a unit, restructuring a
union. Without versioned names, those need a flag day where every producer and consumer
switches at once.

With them, `v1` and `v2` run side by side: producers dual-write, consumers migrate at their
own pace, `v1` is retired when it is idle. A topic rename is cheap; a coordinated
organisation-wide deploy is not.

## Why a frozen baseline instead of a registry

```
  src/test/resources/baseline/TelemetryEvent.avsc   ← committed, reviewed
                     │
     SchemaCompatibility.checkReaderWriterCompatibility(
           reader = current schema,
           writer = baseline)
                     │
       COMPATIBLE ───┴─── INCOMPATIBLE → build fails, naming the field
```

A Schema Registry enforces this at **runtime**, when a producer first publishes. That is too
late: the code is already merged and deployed. The baseline check fails the **pull request**.

A registry still arrives in Phase 2 — belt and braces, and it is what production actually
relies on. But the build-time gate is the one that changes behaviour, because it gives
feedback while the author is still looking at the change.

Updating a baseline is deliberately manual: you copy the new schema over it in the same
commit, so a reviewer sees both halves and can ask whether the evolution is genuinely safe.
Updating it to silence a red test, without understanding the failure, is how you break every
consumer at once.

## Consequences

**Positive**
- A breaking change fails the build of whoever made it, with the offending field named.
- Three fixtures under `src/test/resources/incompatible/` prove the gate actually rejects
  breaking changes, so weakening it is visible.
- The nuance is captured as a test: adding a field **with** a default passes, **without** one
  fails. The lesson is not "adding fields is dangerous".

**Negative**
- Baseline maintenance is a manual step people will forget. Mitigation: the failure message
  says exactly what to do, and a test asserts that every schema *has* a baseline — so a new
  schema cannot be silently ungated.
- BACKWARD forbids some changes that are genuinely safe in context. The escape hatch is a
  new topic version, which is intentionally slightly painful.
- Nothing yet enforces the consumers-first deployment order. That is a Phase 11 concern.
