# Phase 1 — Contracts & Schemas: Tasks

Requirement IDs refer to [`requirements.md`](requirements.md).

> **Implementation note (T1-03).** Three approaches to Avro codegen were tried before one
> worked; the dead ends are recorded in ADR-0004 because they cost real time:
> the community Gradle plugin (Gradle 9 support unverified), `avro-tools` via JavaExec
> (unresolvable — its POM references unpublished `trevni-*-tests.jar`), and
> `SpecificCompiler.main` (defaults to *protocol* mode). The working answer is a custom
> `AvroCodegenTask` calling `SpecificCompiler.compileSchema` directly.

## Module setup

- [x] **T1-01** Create `libs/contracts` module; add to `settings.gradle.kts`.
- [x] **T1-02** Add Avro + Jackson + swagger-parser to the version catalog. → REQ-C1-06
- [x] **T1-03** Write the `generateAvroJava` JavaExec task; wire generated sources into the
  main source set; gitignore the output. → REQ-C1-06, NFR-C1-03

## Schemas

- [x] **T1-04** `TelemetryEvent.avsc` — envelope + payload union. → REQ-C1-01/03/04/05
- [x] **T1-05** `MetricAggregate.avsc`. → REQ-C1-02
- [x] **T1-06** `AlertEvent.avsc`. → REQ-C1-02
- [x] **T1-07** `ConfigChange.avsc`. → REQ-C1-02
- [x] **T1-08** Round-trip test: serialize → deserialize → equals, per type.
- [x] **T1-09** Size test: a typical heartbeat stays under the budget. → NFR-C1-04

## Compatibility gate

- [x] **T1-10** Freeze baseline copies under `src/test/resources/baseline/`. → REQ-C1-07
- [x] **T1-11** Compatibility test using `SchemaCompatibility`. → REQ-C1-08/10
- [x] **T1-12** Prove the gate: an incompatible schema IS rejected. → REQ-C1-09
- [x] **T1-13** Prove the nuance: a field **with** a default passes, **without** one fails.

## Topic & API contracts

- [x] **T1-14** `api/asyncapi/streampulse-topics.yaml` with key, partitions, retention,
  cleanup policy and rationale per topic. → REQ-C1-11/12/13
- [x] **T1-15** `api/openapi/ingest-v1.yaml` — batch endpoint, `Idempotency-Key`, 429 +
  `Retry-After`, ProblemDetail. → REQ-C1-14/15
- [x] **T1-16** `api/openapi/query-v1.yaml` — metrics query + SSE stream.
- [x] **T1-17** `api/openapi/control-plane-v1.yaml` — tenants, titles, alert rules.
- [x] **T1-18** Test that validates every OpenAPI document parses. → REQ-C1-16

## Documentation

- [x] **T1-19** ADR-0004 — why Avro for events (vs Protobuf, JSON Schema).
- [x] **T1-20** ADR-0005 — topic/schema versioning and the BACKWARD choice.
- [x] **T1-21** `docs/learning/02-schema-evolution.md`.
- [x] **T1-22** Verified output into `MILESTONES.md`.

---

## Definition of done

The acceptance checklist in `requirements.md` passes, and `MILESTONES.md` shows the
compatibility gate **failing** on a deliberately broken schema — not just passing on a good one.
