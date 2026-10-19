# ADR-0015 — JPA and Flyway for the write model; a transactional outbox instead of dual writes

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 6 (control plane) · **Related:** ADR-0016, ADR-0004

## Context

The control plane owns tenants (a tree), apps, titles, alert rules and service nodes, and every
other service must learn about changes to them. Two decisions follow: how the write model is
stored and evolved, and how a change reaches the rest of the system.

## Decision 1 — JPA (Hibernate) for the write model, Flyway for the schema

- **JPA** for the aggregates: small, relational, read-modify-write, with optimistic locking
  (`@Version`) whose value is also the announcement's `version`.
- **Flyway owns the schema; Hibernate only validates it** (`ddl-auto=validate`). An entity that
  drifts from the migration fails the boot (`FlywayMigrationIT` boots against the migrated
  schema). The control plane uses its own schema `control_plane`; the sink writer's
  `metric_window` is not touched.
- **`flyway-database-postgresql` is required.** Observed: with `flyway-core` alone, Flyway 11.7.2
  fails with `Unsupported Database: PostgreSQL 16.15`.
- **Entities are mapped to domain records at the edge.** The domain is framework-free (ArchUnit
  enforces it); entities and their lazy collections never reach a controller, and open-in-view is
  off, so a lazy load outside a transaction throws instead of hiding a query.
- **JPA is the wrong tool for aggregates over many rows** (billing usage). That endpoint is deferred,
  not faked; when built it should be hand-written SQL.

The N+1 lab is the cost of choosing JPA, made visible: loading the tenant tree naively costs
`1 + 2N` statements and listing alert rules `1 + N`; both are reproduced and fixed under test
(`TenantTreeQueryCountIT`, `AlertRuleQueryCountIT`; measured with Hibernate Statistics).

## Decision 2 — the transactional outbox, not a dual write

A change is written to the business tables and, **in the same database transaction**, to an
`outbox` table. A separate relay (ADR-0016) turns outbox rows into records on `config.changes.v1`.

### Why not "commit, then publish"

A crash between the commit and the publish leaves the database changed and the topic silent,
forever; no retry fixes a process that is gone. The two stores cannot be made atomic from the
application.

### Why not publish to Kafka inside the transaction

It looks atomic and is not. If the publish succeeds and the commit then fails, the topic announces
a change that never happened and cannot be recalled. If the commit succeeds and the publish was
only buffered, a crash loses it. A slow broker also holds a database connection and transaction
open for the duration. Kafka transactions do not include the database; a distributed transaction
across them is the thing the outbox exists to avoid.

### What the outbox guarantees, and what it does not

- Guarantee: a change and its announcement exist together or not at all
  (`OutboxAtomicityIT.rolledBackChangeLeavesNoOutboxRow`, `committedChangeLeavesExactlyOneRow`).
- The port is `MANDATORY`: called outside a transaction it throws rather than commit on its own
  (`outboxRefusesToWriteOutsideATransaction`). Mutating it to `REQUIRES_NEW` turns two of those tests red.
- Delivery to the topic is **at least once** (the relay can replay after a restart). The announcement
  carries its own id (`changeId` = outbox row id) and the entity `version`, so a consumer deduplicates
  or ignores an older version.
- Announcements are full snapshots, a delete is a null-value tombstone: the topic is compacted, and
  a delta would be unusable after compaction.

## Consequences

**Positive:** request latency and availability do not depend on Kafka; no distributed transaction;
the change history is queryable in SQL.

**Negative**
- The outbox table grows. **No retention job is built.** Rows stay after being relayed.
- A second moving part (the relay) must be operated and monitored. Nothing alerts on its lag yet.
- Service nodes have no entity type in the `ConfigChange` contract; they are announced as
  `ENTITY_UNKNOWN`. The contract needs a new enum symbol (with its default), outside this change.
- Two concurrent re-parentings of tenants can each pass the cycle check and together create a cycle
  (write skew across rows). Not prevented here; an advisory lock or serializable isolation would.
- No authentication on the API; `changedBy` is always empty.
