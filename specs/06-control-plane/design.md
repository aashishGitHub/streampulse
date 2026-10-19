# Phase 6 (part A) — Control Plane: Design

## Flow of control and data

```
PUT /v1/admin/tenants/acme  {displayName, quota, parent}
   │
   ▼
TenantController           Bean Validation on the DTO (400 problem+json, per-field errors)
   │  command
   ▼
TenantService  @Transactional ─────────────────────────── ONE database transaction ──────────┐
   │  1 load  TenantRepository (port) ──▶ JPA adapter ──▶ tenant                              │
   │  2 domain rule: TenantHierarchy.assertNoCycle(...)   pure code, no framework             │
   │  3 save   TenantRepository.update(...)  (flush: version is now the NEW version)           │
   │  4 ChangeRecorder.updated(tenant)       builds ChangeEvent(full snapshot) from the Clock  │
   │        └──▶ ConfigChangeOutbox (port, MANDATORY propagation) ──▶ INSERT INTO outbox      │
   │              payload = AvroBinaryCodec.encode(ConfigChange)   (NULL on delete)           │
   ▼                                                                                          │
 commit  ◀────────────────────────────────────────────────────────────────────────────────────┘
   │   business row + outbox row become visible together, or neither does
   ▼
Postgres WAL ──▶ Debezium connector (EventRouter SMT, ByteArrayConverter) ──▶ config.changes.v1
                  key = aggregate_id, value = the raw Avro bytes, NULL value for a delete
```

The relay is a separate process reading the write-ahead log. The request path never calls
Kafka, so a broker outage cannot fail or slow a configuration change (ADR-0015).

## Layers

```
adapter.in.web          controllers, DTO records, ProblemDetail advice      (Spring MVC, Bean Validation)
application             services (@Transactional), ChangeRecorder            (no adapter imports; ArchUnit)
domain                  records, rules, ports                                (no framework imports; ArchUnit)
adapter.out.persistence JPA entities, Spring Data repositories, adapters
adapter.out.outbox      JdbcConfigChangeOutbox, AvroConfigChangeEncoder
config                  properties record, Clock and id beans
```

Entities live in the persistence adapter and are mapped to domain records at the edge. That
costs a mapper per aggregate; it buys a domain whose rules are unit-tested with no database and
no Spring, and entities whose lazy collections cannot leak into the web layer (open-in-view is off).

## Data model (schema `control_plane`, owned by Flyway)

```
tenant(tenant_id PK, display_name, monthly_event_quota NULL, parent_tenant_id NULL FK tenant, version)
app(app_id PK, tenant_id FK, name, platform, version)
title(title_id PK, tenant_id FK, name, kind, version)
alert_rule(rule_id PK, tenant_id FK, metric, comparator, threshold, severity, category NULL, version)
  alert_rule_dependency(rule_id FK, upstream_category)                  ← element collection
service_node(node_id PK, tenant_id FK, name, category, version)
  service_node_dependency(node_id FK, depends_on_node_id FK RESTRICT)    ← element collection
outbox(id uuid PK, aggregate_type, aggregate_id, tenant_id, operation, payload bytea NULL, created_at)
```

All foreign keys are RESTRICT, so "delete something still referenced" is refused by the database
itself, which is the only place that is safe against a concurrent insert.
`metric_window` belongs to the sink writer and is not touched; nothing here is in the `public` schema.

## The announcement

| Field | Value |
|---|---|
| Kafka key | `aggregate_id`: `tenant/acme`, `title/acme/t-1`, `service-node/acme/cdn-1`, and for an alert rule the BARE rule id `r-1` (the alert engine names a rule by the key and removes it by the tombstone's key). Unique across kinds: every prefixed key contains `/`, an id never does. Per-key order is per-entity order. |
| value (create/update) | raw `ConfigChange` Avro bytes, `payload` = full JSON snapshot |
| value (delete) | NULL: a real compaction tombstone, not a record whose `payload` is null |
| `changeId` | the outbox row's id |
| `version` | the entity's row version AFTER the write |
| `changedAt` | injected `Clock` |
| snapshot discriminator | every snapshot has `entityKind` (TENANT, TITLE, ALERT_RULE, SERVICE_NODE). NOT `kind`: for an alert rule `kind` is the CONDITION type (`THRESHOLD`) the alert engine's parser reads, and an entity kind there would get the rule rejected. |
| service node | `entityType = ENTITY_UNKNOWN`; the snapshot says `"entityKind":"SERVICE_NODE"` (contract gap, see requirements) |

`ConfigChange.operation = DELETED` therefore never appears on the topic: the delete's
information is the tombstone itself. The outbox row still carries `operation` for operators.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Business change rolls back after the outbox write | no outbox row | `OutboxAtomicityIT.rolledBackChangeLeavesNoOutboxRow` |
| Business change commits | exactly one outbox row per change | `OutboxAtomicityIT.committedChangeLeavesExactlyOneRow` |
| Outbox written on its own connection | would survive a rollback; refused instead (MANDATORY) | `OutboxAtomicityIT.outboxRefusesToWriteOutsideATransaction` |
| Outbox insert fails | the business change rolls back with it | `OutboxAtomicityIT.failingOutboxRollsTheBusinessChangeBack` |
| Delete | row with NULL payload, operation DELETED | `OutboxAtomicityIT.deleteWritesATombstoneRow` |
| Update after update | full snapshot each time, version increases | `OutboxAtomicityIT.updateWritesAFullSnapshotWithTheNewVersion` |
| Outbox bytes decode | contract record, fields as specified | `AvroConfigChangeEncoderTest` |
| Alert-rule payload drifts from what the engine parses | field names asserted by hand | `AlertRulePayloadContractTest` |
| Tenant becomes its own ancestor | rejected, nothing written, no announcement | `TenantHierarchyTest`, `TenantServiceTest.cycleIsRejectedAndNothingIsAnnounced`, `ControlPlaneApiIT.tenantCycleIsRejectedOverHttp` |
| Rule graph has a cycle | rejected with 422 | `DependencyGraphTest`, `AlertRuleServiceTest`, `ControlPlaneApiIT.ruleCycleIsRejectedOverHttp` |
| Node dependency cycle / foreign tenant / missing node | 422 | `ServiceNodeServiceTest` |
| Delete still-referenced row | 409, nothing changed | `ControlPlaneApiIT.deletingATenantThatIsStillReferencedIsRefused` |
| Tree costs 1 + 2N queries | reproduced, then fixed to <= 3 | `TenantTreeQueryCountIT` |
| Rule list costs 1 + N queries | reproduced, then fixed to 1 | `AlertRuleQueryCountIT` |
| Self-invoked `@Transactional` method | no transaction | `TransactionBehaviourIT.selfInvocationBypassesTheProxy` |
| Checked exception | commits | `TransactionBehaviourIT.checkedExceptionCommitsByDefault` |
| `REQUIRES_NEW` | outer suspended; inner commits alone | `TransactionBehaviourIT.requiresNewSurvivesTheOuterRollback` |
| Migration drifts from the entities | boot fails (`ddl-auto=validate`) | `FlywayMigrationIT` |
| Context assembles with no database | beans and health groups wired | `ControlPlaneWiringTest` |
| Bad input | 400 problem+json naming fields | `TenantControllerTest` |
| Usage asked for | 501 problem+json, deferred | `BillingControllerTest` |
| Connector reads bytes, tombstone | raw Avro on the topic, null value on delete | `OutboxCdcIT` (Testcontainers: Postgres + Kafka + Debezium running the real `docker/debezium/outbox-connector.json`), and the compose-stack run recorded in the report |
| Relay down | request path unaffected; rows accumulate; delivered in order on return | observed by hand only |

## The N+1 lab

`TenantEntity` has two lazy collections (`apps`, `titles`). Loading all tenants and then touching each
collection issues one query for the tenants plus one per collection per tenant: `1 + 2N`. The tree
reader loads tenants with their apps in one statement, then tenants with their titles in a second
(Hibernate fills the already-managed instances' collections), and assembles parent/child links in
memory from `parent_tenant_id`. Two bags cannot be fetch-joined in one statement
(`MultipleBagFetchException`), and joining two sets in one statement returns apps x titles rows per
tenant, hence two statements. Alert rules and service nodes use `@EntityGraph` on their dependency
element collections: one statement. The count comes from Hibernate Statistics
(`getPrepareStatementCount`), cleared before each measurement.

## Transactions: what is demonstrated

A Spring `@Transactional` is a proxy around the bean. `this.method()` does not pass through it, so a
self-invoked `@Transactional` method runs without one. A checked exception does not mark the
transaction for rollback unless `rollbackFor` says so. `REQUIRES_NEW` suspends the outer transaction
and takes a second connection: the inner commit is independent, the inner cannot see the outer's
uncommitted rows, and with a pool of one connection it would deadlock waiting on itself.
Each is a test that observes the thing (row visible or not, transaction active or not).

## Deliberately absent

Authentication; billing; pagination; outbox retention; a relay health signal; optimistic-lock
tokens in the request (the version is returned, not yet required back).
