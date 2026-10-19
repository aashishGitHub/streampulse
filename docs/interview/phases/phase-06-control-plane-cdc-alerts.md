# Phase 6 — Control plane, Debezium CDC, alert engine

**Status:** core complete. 102 control-plane tests and 191 alert-engine tests, no failures. Change
data capture proven live by hand (create, update, delete). Not built: restoring open alerts on restart,
authentication on either service. Not rerun: `OutboxCdcIT` (Testcontainers plus Debezium).

**One line to say first:** "Config is written to Postgres with an event row in the same transaction,
Debezium relays it to a compacted topic, and the alert engine rebuilds its rule book from that topic
and evaluates rules over the aggregates, suppressing downstream alerts when a root cause fires."

## Code map

| Thing | Where |
|---|---|
| Outbox append (`MANDATORY` propagation) | `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java` |
| Where changes are recorded | `services/control-plane/src/main/java/com/streampulse/controlplane/application/ChangeRecorder.java` |
| Avro payload for the outbox row | `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/AvroConfigChangeEncoder.java` |
| Tenant tree reader (N+1 fix) | `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/JpaTenantTreeReader.java` |
| Cycle prevention | `services/control-plane/src/main/java/com/streampulse/controlplane/domain/TenantHierarchy.java`, `DependencyGraph.java` |
| Schema | `services/control-plane/src/main/resources/db/migration/V1__control_plane.sql` |
| Problem+json mapping | `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/in/web/ProblemAdvice.java` |
| Debezium connector | `docker/debezium/outbox-connector.json` |
| Rule book from the compacted topic | `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/RuleBookLoader.java`, `ConfigChangeApplier.java` |
| Evaluation, dedupe, suppression | `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java` |
| State machine (a pure function) | `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertStateMachine.java` |
| Cycle check | `services/alert-engine/src/main/java/com/streampulse/alert/domain/graph/RuleGraphValidator.java` |
| Delivery with per-channel retry | `services/alert-engine/src/main/java/com/streampulse/alert/application/AlertOutbox.java`, `AlertProcessor.java` |
| SSE alert feed | `services/alert-engine/src/main/java/com/streampulse/alert/application/AlertFeed.java` |
| Decisions | `docs/adr/0015-jpa-flyway-transactional-outbox.md`, `0016-debezium-outbox-router-raw-avro.md`, `0017-alert-evaluation-and-suppression.md` |

## Flow

```
REST write ─▶ @Transactional service ─▶ save entity ─▶ ChangeRecorder ─▶ INSERT outbox row   (one transaction)
                                                                                │ Postgres WAL
                                                                       Debezium (EventRouter SMT)
                                                                                ▼
                                          config.changes.v1  (compacted, key = aggregate id, delete = tombstone)
                                                                                ▼
alert-engine: RuleBookLoader (no consumer group, read from the start, never commits) ─▶ RuleBook
telemetry.agg.v1 ─▶ AggregateListener ─▶ AlertProcessor ─ lock ─▶ AlertEngine.evaluate ─▶ outbox
        ─▶ drain per channel (Kafka alerts.v1, SQS, SSE feed) ─▶ only then ack the offset
```

---

## Beginner

### B1. What is the dual-write problem?

**A strong answer contains**
- You cannot atomically commit to your database and publish to a broker. Commit then publish, and a
  crash between them leaves a change the rest of the system never hears about. Publish then commit
  has the opposite flaw.
- The fix here is the transactional outbox (B2).

**Open** `docs/adr/0015-jpa-flyway-transactional-outbox.md`

### B2. What is a transactional outbox?

**A strong answer contains**
- Write the business change *and* an event row in the **same** database transaction; a separate process
  publishes the row afterwards.
- They commit or roll back together, so a change cannot exist without its event.
- Delivery is at-least-once, so consumers de-duplicate (here on a change id and version).

**Open** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java`

### B3. What is a tombstone and why does a compacted topic need one?

**A strong answer contains**
- A record with a key and a null value. Compaction keeps the latest record per key, so a deletion must
  itself be a record or the key lives forever.
- Tombstones are removed after `delete.retention.ms`, so a consumer that starts later can miss a delete.
- Here a delete is an outbox row with a null payload, which the router turns into a null-value record.

**Open** `docs/adr/0016-debezium-outbox-router-raw-avro.md`

### B4. Why `ddl-auto: validate` with Flyway, and why `open-in-view: false`?

**A strong answer contains**
- Flyway owns the schema; Hibernate only checks entities against it at startup, so drift fails at boot.
- With open-in-view off, a lazy load outside a transaction throws, so N+1 cannot hide in the web layer;
  entities are mapped to records before leaving the service.

**Open** `services/control-plane/src/main/resources/application.yml`

### B5. What does problem+json give a client?

**A strong answer contains**
- A standard error shape (RFC 7807). Mappings: not found 404, already exists or conflict 409, rule
  violation 422, bad input 400, optimistic-lock failure 409, anything else 500.
- A deletion blocked by a foreign key becomes a 409, not a 500.

**Open** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/in/web/ProblemAdvice.java`

### B6. What is an alert dependency, and why suppress downstream alerts?

**A strong answer contains**
- If service A is broken, B's alert is a symptom. Suppressing it means on-call sees one page, not
  twenty.
- Dependencies must form a graph with no cycles, so cycles are rejected when a rule is saved.

**Open** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java`

---

## Mid

### M1. Why `Propagation.MANDATORY` on the outbox append?

**A strong answer contains**
- `REQUIRED` would silently open its own transaction if the caller had none. `REQUIRES_NEW` would commit
  the row even if the business change rolled back, leaving an event for something that never happened.
- `MANDATORY` throws if there is no transaction. Tests: a rolled-back change leaves no outbox row (and
  they first assert the row *exists* inside the transaction, so the test is not vacuous); writing outside
  a transaction is refused; a failing outbox rolls the business change back.
- Mutation: the outbox on its own connection turns the rollback test red.

**Open** `services/control-plane/src/test/java/com/streampulse/controlplane/OutboxAtomicityIT.java`

### M2. How do you detect N+1 in a test and not in production logs?

**A strong answer contains**
- Count statements with Hibernate statistics. Reproduce first (a tenant tree costs `1 + 2N`; alert rules
  `1 + N`), then assert the fixed cost is flat across sizes (at most 3 statements; 1 for rules).
- Removing the fetch turns the flat-count test red.

**Open** `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TenantTreeQueryCountIT.java`

### M3. Fetch join versus `@EntityGraph`, and why two queries for the tenant tree?

**A strong answer contains**
- Fetching two collections at once either throws `MultipleBagFetchException` or returns a cartesian
  product (apps x titles).
- Two fetch-join queries in the same persistence context fill the already-managed instances.
- The rule repository uses `@EntityGraph` for its one collection.

**Open** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/JpaTenantTreeReader.java`

### M4. Name three `@Transactional` pitfalls this repo demonstrates.

**A strong answer contains**
- Self-invocation bypasses the proxy, so a runtime exception does not roll back.
- Checked exceptions commit by default unless `rollbackFor` is set.
- `REQUIRES_NEW` gets its own transaction id, cannot see the outer's uncommitted rows, and survives the
  outer rollback.
- Observed on real Postgres by comparing transaction ids.
- A pool of one connection plus `REQUIRES_NEW` can deadlock; that is a configuration comment only, **not
  tested**.

**Open** `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TransactionBehaviourIT.java`

### M5. How is the alert engine an idempotent consumer?

**A strong answer contains**
- A cursor per (rule, series). A window start not later than the cursor is a no-op, counted as duplicate
  or stale. The cursor advances under the same lock as the state transition.
- Removing the guard turned 3 engine tests red (recorded in the task list; no raw output pasted).

**Open** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java`

### M6. Why does the rule book loader use no consumer group?

**A strong answer contains**
- Every instance needs the *whole* rule table, so it reads all partitions from the beginning and never
  commits.
- The aggregate listener starts only after the loader reaches the end offsets, so no aggregate meets a
  partial rule book. Applying a record is idempotent, so a rebuild is safe.

**Open** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/RuleBookLoader.java`

### M7. Why does the alert engine acknowledge only after delivery?

**A strong answer contains**
- State changes first, so a redelivered observation would be a no-op and the alert would be lost. An
  in-memory outbox remembers "applied, not yet delivered" per channel.
- Each channel retries alone and in order. The error handler never skips a record.
- SQS standard queues are at-least-once and unordered, so consumers de-duplicate.

**Open** `services/alert-engine/src/main/java/com/streampulse/alert/application/AlertOutbox.java`

---

## Senior

### S1. CDC versus a polling publisher: defend the choice.

**A strong answer contains**
- Reading the write-ahead log gives low latency and no polling load on the table.
- Cost: a Kafka Connect cluster, and Postgres retains WAL for an inactive replication slot (disk risk).
- Both approaches are at-least-once. A polling publisher is simpler to operate; choose it if the
  Connect cluster is not worth it.

**Open** `docs/adr/0016-debezium-outbox-router-raw-avro.md`

### S2. The connector is down for an hour. What happens?

**A strong answer contains**
- Writes still succeed and the outbox grows. The slot retains WAL; consumers see staleness, not errors.
- On return, records flow in order.
- **No alert exists on slot lag**; an abandoned slot needs `pg_drop_replication_slot` or it fills the
  disk.

**Open** `docs/adr/0016-debezium-outbox-router-raw-avro.md`

### S3. Why full snapshots plus tombstones on the topic, not deltas?

**A strong answer contains**
- Compaction keeps only the latest record per key, so a delta is unusable once compacted.
- Consumers ignore an older version than the one they hold, and de-duplicate on change id.
- A late consumer can miss deletes once tombstones expire.

**Open** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/ConfigChangeApplier.java`

### S4. Explain root-cause suppression and its invariant.

**A strong answer contains**
- Walk upstream breadth-first to find the nearest firing cause; walk downstream to demote. When a root
  resolves, release dependents in topological order (ancestors first).
- Invariant: no firing alert has a firing alert upstream in the same tenant.
- Removing the breadth-first walk turned 13 of 23 suppression tests red.
- Scope is tenant-wide; unlike Alertmanager inhibit rules it can suppress across regions.

**Open** `services/alert-engine/src/test/java/com/streampulse/alert/domain/SuppressionTest.java`

### S5. Why reject cycles at save time if the walk keeps a visited set anyway?

**A strong answer contains**
- A deterministic topological order and a named cycle in the error. Release order needs a rank.
- Defence in depth: the control plane rejects on save (Kahn's algorithm), the alert engine validates
  again when it loads rules.

**Open** `services/alert-engine/src/main/java/com/streampulse/alert/domain/graph/RuleGraphValidator.java`

### S6. The alert engine restarts. What breaks?

**A strong answer contains**
- Active alerts, cursors, the SSE buffer and the sequence are in memory and are lost. Firing alerts are
  forgotten without a RESOLVED ever being emitted. Undelivered changes in the in-memory outbox are lost.
- Restoring state from the alert topic is a known cut (T6B-18), not built.
- Also single-writer only: with several instances, suppression cannot see alerts on another instance.

**Open** `docs/adr/0017-alert-evaluation-and-suppression.md`

### S7. Where can the control plane's concurrency go wrong?

**A strong answer contains**
- Concurrent re-parenting can create a cycle through write skew; the ADR says an advisory lock or
  serializable isolation would fix it. **Not prevented.**
- Optimistic locking guards only the read-modify-write window; clients do not send the version.
- I found no test for a concurrent-update 409 (agent-reported; verify).

**Open** `docs/adr/0015-jpa-flyway-transactional-outbox.md`

---

## Say this honestly

- No authentication on either service; tenant is a filter the client asserts.
- Apps are not announced through the outbox (`AppService` has no `ChangeRecorder`); check the spec
  before claiming complete CDC coverage.
- The outbox has no retention job and nothing alerts on relay lag.
- Red-first was not observed for every class; the mutation checks are what prove the key tests can fail.
- Pool sizing and heartbeat, buffer and grace values are targets.

## Stories and numbers

[What broke](../05-what-broke.md): stories 1, 7 (mutation habit). [Numbers](../06-numbers.md): test counts
and targets.
