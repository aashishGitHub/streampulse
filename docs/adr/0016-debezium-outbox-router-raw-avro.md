# ADR-0016 — Debezium outbox router relaying raw Avro to `config.changes.v1`

**Status:** accepted · **Date:** 2026-10-12 · **Phase:** 6 (control plane) · **Related:** ADR-0015, ADR-0004, ADR-0005

## Context

ADR-0015 puts every announcement in an `outbox` table. Something must read those rows and produce
them to the compacted topic `config.changes.v1`, keyed by entity id, as the **raw bytes** of an Avro
`ConfigChange` (the repo uses no schema registry: `AvroBinaryCodec`, ADR-0004), with a **null value**
for a delete.

## Decision

Kafka Connect with the **Debezium PostgreSQL connector** reading the write-ahead log (`pgoutput`),
plus Debezium's **outbox `EventRouter`** single message transform. Image
`quay.io/debezium/connect:3.0.8.Final` (verified with `docker manifest inspect`, multi-arch). Config:
`docker/debezium/outbox-connector.json`; compose profile `cdc`.

| Setting | Value | Why |
|---|---|---|
| `table.include.list` | `control_plane.outbox` | only the outbox is captured |
| `publication.autocreate.mode` | `filtered` | the publication covers only that table. The table must already exist when the connector starts (otherwise: `No table filters found for filtered publication`), so register it after the service has migrated. |
| `transforms.outbox.table.field.event.key` | `aggregate_id` | the Kafka key; same key, same partition, per-entity order |
| `transforms.outbox.table.field.event.payload` | `payload` (`bytea`) | the Avro bytes |
| `transforms.outbox.route.topic.regex` / `.replacement` | `.*` / `config.changes.v1` | one fixed topic regardless of `aggregate_type` |
| `transforms.outbox.route.tombstone.on.empty.payload` | `true` | a NULL payload becomes a null-value record |
| `key.converter` | `StringConverter` | the key is text |
| `value.converter` | `ByteArrayConverter` | writes the `bytea` as-is: no JSON, no envelope |

The option names were read out of `EventRouterConfigDefinition` in `debezium-core-3.0.8.Final.jar`,
not remembered.

## Evidence

Run on the compose stack (`kafka`, `postgres`, `connect`) with the control plane on the host:
create, update and delete of one tenant gave three records on `config.changes.v1`, same partition,
offsets 0, 1, 2: two raw Avro records that `AvroBinaryCodec` decodes to `ConfigChange` (210 and 214
bytes, `operation` CREATED then UPDATED, versions 0 and 1), then a record with value `null`.
`OutboxCdcIT` automates the same against Testcontainers (Postgres with `wal_level=logical`, Kafka,
Debezium Connect) using this very connector file.

## What breaks

- **The connector is down.** The control plane keeps accepting changes (observed: 201 and 200 while
  Connect was stopped). Outbox rows accumulate, and Postgres **retains write-ahead log for the
  replication slot** (observed: the slot went inactive with WAL retained). Consumers see no new
  records, so their lag is staleness, not an error. On return the connector resumes from the slot and
  the records arrive in order (observed). An abandoned slot would fill the disk: drop it
  (`pg_drop_replication_slot`) if the connector is retired. No alert exists for slot lag.
- **At-least-once.** After a restart the connector can re-emit rows; consumers use `changeId` and
  `version` to ignore duplicates.
- **Ordering** is guaranteed per key only (one partition per key). Across entities there is none:
  a consumer must not assume a tenant's record precedes its title's.
- **Compaction** keeps the last record per key; a tombstone is retained for `delete.retention.ms`
  and then removed. A consumer that starts after that sees neither. The topic was created with
  3 partitions and `cleanup.policy=compact` (`kafka-init`).
- **Schema changes** to the outbox table (renaming `payload`, `aggregate_id`) break the router
  configuration silently until the next change. The migration comment names the dependency.
- **Service nodes** arrive as `ENTITY_UNKNOWN` (contract gap, ADR-0015).

## Alternatives considered

- **A polling publisher** in the service (scan `outbox`, publish, mark done): simpler to run, no
  Connect cluster, but polling latency and the service must handle publish-then-mark failure
  itself (it is at-least-once too). Not built.
- **JSON payloads with `JsonConverter`:** what the router's defaults suit, but the repo's contract is
  Avro bytes on every topic.
- **Deleting outbox rows in the same transaction** (Debezium's documented pattern to keep the table
  small): the WAL still carries the insert. Not done: a retention job is the gap instead.

## VERIFY

- Debezium and Kafka Connect behaviours above are from the 3.0.8.Final image run here; re-check the
  option names and the tombstone behaviour when upgrading the tag.
