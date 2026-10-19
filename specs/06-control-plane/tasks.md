# Phase 6 (part A) — Control Plane: Tasks

Order is the order of work. Each line: failing test first, then code. Tick only with passing
output in MILESTONES.md.

- [x] T6-01 spec, design written; OpenAPI corrected to what is built (billing deferred, every implemented operation declared); ADR-0015, ADR-0016
- [x] T6-02 `ControlPlaneArchitectureRulesTest` fails -> module skeleton with layered packages
- [x] T6-03 `TenantHierarchyTest` fails -> `TenantHierarchy` (cycle prevention)
- [x] T6-04 `DependencyGraphTest` fails -> `DependencyGraph` (Kahn topological sort)
- [x] T6-05 domain record tests fail -> `Tenant`, `Title`, `AlertRule`, `ServiceNode` and their rules
- [x] T6-06 `AvroConfigChangeEncoderTest` fails -> `AvroConfigChangeEncoder`
- [x] T6-07 `TenantServiceTest`, `AlertRuleServiceTest`, `ServiceNodeServiceTest` fail -> application services, `ChangeRecorder`
- [x] T6-08 `TenantControllerTest`, `BillingControllerTest` fail -> controllers, DTOs, problem-detail advice
- [x] T6-09 `FlywayMigrationIT` fails -> migrations V1, entities, repositories, adapters
- [x] T6-10 `OutboxAtomicityIT` fails -> `JdbcConfigChangeOutbox` (MANDATORY propagation)
- [x] T6-11 mutation: outbox on its own connection, `rolledBackChangeLeavesNoOutboxRow` goes red; restore
- [x] T6-12 `TenantTreeQueryCountIT` + `AlertRuleQueryCountIT`: reproduction asserts 1 + 2N / 1 + N; fixed asserts <= 3 / 1
- [x] T6-13 mutation: tree reader without the fetch, the flat-count test goes red; restore
- [x] T6-14 `TransactionBehaviourIT` (self-invocation, checked exception, REQUIRES_NEW)
- [x] T6-15 `ControlPlaneApiIT` end to end over HTTP
- [x] T6-16 `ControlPlaneWiringTest` (no Docker)
- [x] T6-17 compose `cdc` profile, `docker/debezium/outbox-connector.json`, topic added to `kafka-init`
- [x] T6-18 CDC proven by hand: create, update, delete through REST -> Avro bytes, then tombstone, on `config.changes.v1`
- [x] T6-19 `OutboxCdcIT` (Testcontainers + Debezium) if it can be made to run
- [x] T6-20 `:libs:contracts:test --rerun` green after the OpenAPI edit

Honesty note on order: the domain, services and adapters were written in the same pass as their unit
tests, so for those the red-first step was NOT observed run by run; the mutation checks (T6-11, T6-13)
are what prove the lab and outbox tests can fail. T6-18's proof is in the phase report, with the
literal commands.

## Not done (cut, stated rather than hidden)

See "Honest gaps" in requirements.md.
