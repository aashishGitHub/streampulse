# Phase 1 — Contracts & Schemas: Requirements

**Status:** in progress
**Depends on:** Phase 0 (build foundation)
**Delivers:** the data contracts every later phase codes against

---

## Why this phase exists

Every service in StreamPulse talks to another service through either a **Kafka topic** or an
**HTTP endpoint**. Both are contracts. The question this phase answers is: *when a producer
changes, how do we know we haven't broken every consumer?*

In a monolith the compiler answers that. Across a message bus nothing does — unless you make
something. A producer can deploy a "harmless" field rename on Tuesday and a consumer that
only runs on Sunday fails, in production, with a stack trace that names neither the change
nor the person who made it.

So contracts come **before** implementation:

```
  ❌ write the service → discover the contract by reading its code → hope
  ✅ define the contract → GENERATE the code → drift is impossible by construction
```

This is also the phase that makes schema evolution — a standard senior interview topic —
concrete rather than theoretical.

---

## User stories

- **As a consumer team**, I want a producer's breaking schema change to fail **their** build,
  not my runtime, so that I learn about it before it is deployed.
- **As a developer**, I want event types generated from the schema, so that a typo in a field
  name is a compile error rather than a `null` at 3am.
- **As a developer**, I want every Kafka topic's key, partition count, retention and cleanup
  policy written down in one place, so that "why is this topic compacted?" has an answer.
- **As a frontend developer**, I want a TypeScript client generated from the same OpenAPI
  document the backend implements, so that the two cannot disagree.
- **As an operator**, I want topic and API versions in their names, so that two versions can
  run side by side during a migration.

---

## Functional requirements

### Event schemas (Avro)

- **REQ-C1-01** THE SYSTEM SHALL define `TelemetryEvent` in Avro with a stable envelope
  (`eventId`, `tenantId`, `sessionId`, `titleId`, `deviceType`, `region`, `occurredAt`,
  `type`) and a payload union covering session start/end, heartbeat, buffering, bitrate
  change and error.
- **REQ-C1-02** THE SYSTEM SHALL define `MetricAggregate`, `AlertEvent` and `ConfigChange`
  schemas for the downstream topics.
- **REQ-C1-03** Every schema field that may be absent SHALL be a union with `null` **and**
  declare a default, because a field without a default cannot be added compatibly.
- **REQ-C1-04** Enums SHALL declare an explicit unknown/unspecified symbol and a `default`,
  so that a consumer receiving a newer symbol degrades instead of throwing.
- **REQ-C1-05** Timestamps SHALL use Avro logical type `timestamp-millis` (UTC), never a
  bare string or a local time.
- **REQ-C1-06** WHEN the build runs THE SYSTEM SHALL generate Java classes from the `.avsc`
  files into a generated-sources directory that is compiled but never committed.

### Compatibility enforcement

- **REQ-C1-07** THE SYSTEM SHALL keep a frozen **baseline** copy of each released schema
  under version control.
- **REQ-C1-08** WHEN the current schema cannot read data written by the baseline schema
  (BACKWARD compatibility) THE SYSTEM SHALL fail the build, naming the incompatible field.
- **REQ-C1-09** THE SYSTEM SHALL include a test proving that a deliberately incompatible
  change **is** detected, so the gate is known to work.
- **REQ-C1-10** Compatibility SHALL be checked with Avro's own `SchemaCompatibility` API, so
  the check runs in CI with no Schema Registry running.

### Topic contracts (AsyncAPI)

- **REQ-C1-11** THE SYSTEM SHALL document every Kafka topic in AsyncAPI: message schema,
  **key**, partition count, replication factor, retention, cleanup policy and the reason for
  each choice.
- **REQ-C1-12** Topic names SHALL carry a major version suffix (`telemetry.raw.v1`), so an
  incompatible change can run alongside the old version rather than requiring a flag day.
- **REQ-C1-13** Every topic that can receive a poison message SHALL declare its dead-letter
  topic.

### API contracts (OpenAPI)

- **REQ-C1-14** THE SYSTEM SHALL define OpenAPI documents for the ingest, query and
  control-plane APIs, including error responses as RFC 7807 `application/problem+json`.
- **REQ-C1-15** The ingest contract SHALL specify the `Idempotency-Key` header, the batch
  size limit, and the `429` + `Retry-After` backpressure response.
- **REQ-C1-16** WHEN the build runs THE SYSTEM SHALL validate every OpenAPI document and
  fail on a malformed one.

---

## Non-functional requirements

| ID | Requirement | Target | How verified |
|---|---|---|---|
| NFR-C1-01 | Codegen adds little to the build | < 5 s | timed `./gradlew :libs:contracts:build` |
| NFR-C1-02 | No network needed to check compatibility | always | run offline |
| NFR-C1-03 | Generated sources are never committed | always | `.gitignore` + review |
| NFR-C1-04 | A serialized event stays compact | < 400 bytes typical | measured in a test |

> Targets chosen for the exercise. `MILESTONES.md` records what was observed.

---

## Out of scope for Phase 1

- Protobuf / gRPC contracts — Phase 2, with the gRPC adapter
- A running Schema Registry — Phase 2, with Docker. Phase 1 proves compatibility with a
  library so CI needs no infrastructure
- TypeScript client generation — Phase 7, with the frontend
- Actually producing or consuming messages — Phase 2

---

## Acceptance checklist

- [ ] `./gradlew :libs:contracts:build` generates Java classes from `.avsc` and compiles them
- [ ] A round-trip test serializes and deserializes every event type
- [ ] Baseline compatibility test passes for all schemas
- [ ] A deliberately incompatible schema **fails** the compatibility test (gate proven)
- [ ] Adding a field **with** a default passes; adding one **without** a default fails
- [ ] Every topic in AsyncAPI documents key, partitions, retention and cleanup policy
- [ ] Every OpenAPI document parses without errors
- [ ] Verified output pasted into `MILESTONES.md`
