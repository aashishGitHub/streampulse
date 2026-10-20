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

**Answer:** The dual-write problem is that a service cannot atomically commit to its own database and publish to a broker, because the two systems share no transaction. If the service commits first and crashes before it publishes, the database holds a change that the rest of the system never hears about, and no retry fixes it because the process that knew about the change is gone. If it publishes first and the commit then fails, the topic announces a change that never happened. The control plane has exactly this shape, because every change to a tenant or an alert rule has to reach other services, so I used a transactional outbox instead of writing to both stores.

**Code:** `docs/adr/0015-jpa-flyway-transactional-outbox.md:36-48` (why not commit-then-publish, and why not publish inside the transaction)

**If they push:** "Why not publish to Kafka inside the database transaction?" It looks atomic and is not: if the publish succeeds and the commit fails, the topic holds a change that cannot be recalled, and a slow broker keeps a database connection and transaction open while it waits. Kafka transactions do not include the database, so the only way to make the two atomic is a distributed transaction, which is the thing the outbox exists to avoid.

### B2. What is a transactional outbox?

**Answer:** A transactional outbox means the service writes the business change and an event row into an outbox table in the same database transaction, and a separate process publishes that row afterwards. Because both writes share one transaction they commit or roll back together, so a change cannot exist without its announcement. In the control plane, `ChangeRecorder` builds the event and `JdbcConfigChangeOutbox.append` inserts the row with the Avro payload, and Debezium relays it to Kafka. Delivery to the topic is at-least-once, because the relay can replay after a restart, so consumers must tolerate duplicates. The announcement carries a change id and the entity version. The only consumer I built, the alert engine, uses the version to ignore an older rule, and I did not find any use of the change id there.

**Example:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java:35-47`
```java
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(ChangeEvent event) {
        jdbc.sql(INSERT)
                .param("id", event.changeId())
                ...
                .param("payload", encoder.encode(event), Types.BINARY)
                ...
                .update();
```
The method is a plain insert; the point is the annotation, which forces it to join the caller's transaction instead of committing on its own.

**Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java:35-47` (the append), `services/control-plane/src/main/java/com/streampulse/controlplane/application/ChangeRecorder.java:42-44` (where a change becomes an announcement), `services/control-plane/src/main/java/com/streampulse/controlplane/application/TenantService.java:27-38` (a service writing the entity and then recording it in one transaction)

**If they push:** "Where is the relay?" It is Debezium reading the write-ahead log, covered in S1; the service itself never talks to Kafka.

### B3. What is a tombstone and why does a compacted topic need one?

**Answer:** A tombstone is a record with a key and a null value. A compacted topic keeps only the latest record per key, so a deletion has to be a record too, otherwise the key and its last value would live forever. Tombstones are themselves removed after the topic's `delete.retention.ms`, which means a consumer that starts after that point sees neither the old record nor the delete. In this project a delete writes an outbox row with a null payload, the encoder returns null for it, and the Debezium router is configured to turn an empty payload into a null-value record on `config.changes.v1`. The alert engine rebuilds its rule book from the start of the topic on every restart and keeps no persisted rule state, so a missed tombstone cannot leave a ghost rule there; a consumer that kept state across restarts could be left holding a deleted entity.

**Example:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/AvroConfigChangeEncoder.java:27-31`
```java
    /** @return the raw Avro record, or null: the delete's whole message is the absence of a value */
    public byte[] encode(ChangeEvent event) {
        if (event.isTombstone()) {
            return null;
        }
```
A delete is the absence of a value, so there is nothing to encode; the router setting `route.tombstone.on.empty.payload` then makes that a Kafka tombstone.

**Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/AvroConfigChangeEncoder.java:27-31` (the null payload), `docker/debezium/outbox-connector.json:33` (null payload becomes a tombstone), `services/control-plane/src/test/java/com/streampulse/controlplane/OutboxAtomicityIT.java:165-176` (a delete writes a row with a null payload), `docs/adr/0016-debezium-outbox-router-raw-avro.md:54-56` (retention caveat)

### B4. Why `ddl-auto: validate` with Flyway, and why `open-in-view: false`?

**Answer:** Flyway owns the schema, and Hibernate is only allowed to check that the entities still match it at startup, so drift between an entity and the migration fails the boot instead of failing a query later. With open-in-view switched off, a lazy collection touched outside a transaction throws instead of quietly running a query while the view is rendered, so an N+1 cannot hide behind the web layer. The service layer maps entities to domain records before anything leaves it, which is what keeps lazy collections from reaching a controller. One thing I observed: with `flyway-core` alone, Flyway 11.7.2 failed against PostgreSQL 16.15 with an unsupported-database error, so the build needs `flyway-database-postgresql` as well.

**Example:** `services/control-plane/src/main/resources/application.yml:39-44`
```yaml
    # WHY false: with open-in-view a lazy collection can be loaded while the view is rendered, which
    # hides N+1 behind a transaction nobody wrote. Off means a lazy load outside a service throws.
    open-in-view: false
    hibernate:
      # Flyway owns the schema. validate fails the boot on drift between entity and migration.
      ddl-auto: validate
```
Two settings, two reasons: one makes schema drift loud at boot, the other makes lazy loading loud at request time.

**Code:** `services/control-plane/src/main/resources/application.yml:39-44` (both settings), `services/control-plane/src/main/resources/db/migration/V1__control_plane.sql:1-3` (the migration says it is the owner of the schema), `docs/adr/0015-jpa-flyway-transactional-outbox.md:11-26` (the decision and the Flyway module observation)

### B5. What does problem+json give a client?

**Answer:** Problem+json gives every error the same standard shape from RFC 7807, with a status, a title and a detail, so a client can handle failures uniformly instead of parsing each endpoint's own format. The control plane maps a missing entity to 404, an existing id or a conflict to 409, a rule violation such as a would-be cycle to 422, bad input to 400, an optimistic-lock failure to 409, and anything unexpected to a 500 with a generic message so internals do not leak. A tenant delete blocked by a foreign key becomes a 409 rather than a 500: the repository catches the database's integrity error and rethrows it as a conflict, and there is a second handler for a duplicate that raced past the existence check and was caught by the primary key.

**Example:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/in/web/ProblemAdvice.java:32-44`
```java
    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler({AlreadyExistsException.class, ConflictException.class})
    ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(RuleViolationException.class)
    ProblemDetail unprocessable(RuleViolationException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Rule violation", e.getMessage());
```
Each domain exception maps to one status in one class, so controllers contain no error handling.

**Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/in/web/ProblemAdvice.java:32-68` (all the mappings), `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/JpaTenantRepository.java:54-62` (a foreign-key refusal becomes a conflict), `services/control-plane/src/test/java/com/streampulse/controlplane/ControlPlaneApiIT.java:152-171` (a referenced tenant is 409, stays, and announces nothing)

### B6. What is an alert dependency, and why suppress downstream alerts?

**Answer:** If service A is broken, an alert on service B that depends on A is usually a symptom, and suppressing it means on-call gets one page about the cause instead of twenty about the symptoms. In this engine each rule names the node it watches and the upstream nodes it depends on, the engine builds a per-tenant graph from those edges, and when an alert opens it walks upstream breadth-first to find the nearest alert that is firing. If it finds one, the new alert is born suppressed and names that cause. The dependencies must form a graph with no cycles, so a cycle is rejected when a rule is saved in the control plane, and the alert engine checks again when it loads the rules.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:218-229`
```java
    private Optional<Alert> findRoot(String tenantId, String node) {
        for (List<String> level : rules.graphFor(tenantId).upstreamLevels(node)) {
            Optional<Alert> nearest = active.values().stream()
                    .filter(a ->
                            a.tenantId().equals(tenantId) && a.state() == AlertState.FIRING && level.contains(a.node()))
                    .min(OLDEST_FIRST);
            if (nearest.isPresent()) {
                return nearest;
            }
        }
        return Optional.empty();
    }
```
It searches one level of ancestors at a time and stops at the first level that holds a firing alert, which is what makes the cause the nearest one.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:218-229` (nearest firing cause), `services/alert-engine/src/main/java/com/streampulse/alert/domain/RuleBook.java:38-50` (edges built from each rule's upstream set), `services/control-plane/src/main/java/com/streampulse/controlplane/application/AlertRuleService.java:41` (cycle check on save)

---

## Mid

### M1. Why `Propagation.MANDATORY` on the outbox append?

**Answer:** The outbox row must live or die with the business change, so the append must never run in a transaction of its own. With `REQUIRED`, a caller that forgot its transaction would silently get a fresh one that commits immediately. With `REQUIRES_NEW`, the row would commit even if the business change later rolled back, leaving an announcement for something that never happened. `MANDATORY` throws if there is no transaction, so a missing one is a loud failure. The tests cover it from three sides: a rolled-back change leaves no outbox row, and the test first asserts the row exists inside the transaction so it is not vacuous; writing outside a transaction is refused; and a failing outbox insert rolls the business change back. ADR-0015 records that mutating the propagation to `REQUIRES_NEW` turned two of these tests red, and the Phase 6 close-out lists an outbox-on-its-own-connection mutation as red then green.

**Example:** `services/control-plane/src/test/java/com/streampulse/controlplane/OutboxAtomicityIT.java:86-96`
```java
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    tenants.create(acme());
                    assertThat(count("outbox"))
                            .as("the row is really there, inside the transaction: the test is not vacuous")
                            .isEqualTo(1);
                    throw new IllegalStateException("abandon the unit of work");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("tenant")).isZero();
        assertThat(count("outbox")).isZero();
```
Point at the assertion inside the lambda: it proves the row existed before the rollback, so the final zero counts mean something.

**Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java:31-37` (the annotation and its reason), `services/control-plane/src/test/java/com/streampulse/controlplane/OutboxAtomicityIT.java:81-97` (rollback leaves no row), `services/control-plane/src/test/java/com/streampulse/controlplane/OutboxAtomicityIT.java:119-143` (outside a transaction refused; failing outbox rolls back the change)

**If they push:** "How does the third test make the outbox fail?" It adds a check constraint to the outbox table that refuses the aggregate id `tenant/boom`, creates that tenant, expects a data-integrity exception, and asserts that neither a tenant nor an outbox row exists afterwards; it drops the constraint in a `finally`.

### M2. How do you detect N+1 in a test and not in production logs?

**Answer:** I count the statements Hibernate prepares, using Hibernate statistics, and assert on the count. The method is to reproduce first and fix second. The reproduction test loads five tenants and touches each lazy collection, and asserts exactly `1 + 2N` statements, which proves the problem is real and measurable. Then the fix test builds the tenant tree for 3 tenants and for 30 and asserts the cost is the same and at most 3 statements, so it is flat in the number of rows rather than merely small. The same lab shows alert rules costing `1 + N` before the fix and one statement after. I also ran a mutation: removing the fetch from the reader turned the flat-count test red, which shows the test can fail.

**Example:** `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TenantTreeQueryCountIT.java:94-108`
```java
    @DisplayName("FIX: the tree costs the same two statements for 3 tenants as for 30, and returns all the data")
    void treeCostDoesNotGrowWithTenants() {
        seed(3);
        stats.clear();
        List<TenantTreeNode> small = treeService.tree();
        long smallCost = stats.getPrepareStatementCount();

        emptyEveryTable();
        seed(30);
        stats.clear();
        List<TenantTreeNode> large = treeService.tree();
        long largeCost = stats.getPrepareStatementCount();

        assertThat(smallCost).isLessThanOrEqualTo(3);
        assertThat(largeCost).as("flat in the number of tenants").isEqualTo(smallCost);
```
Two sizes are the point: a single size cannot tell a constant cost from a linear one.

**Code:** `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TenantTreeQueryCountIT.java:75-91` (the reproduction, `1 + 2N`), `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TenantTreeQueryCountIT.java:94-115` (the flat-cost fix), `services/control-plane/src/test/java/com/streampulse/controlplane/lab/AlertRuleQueryCountIT.java:57-58` (the `1 + N` reproduction for rules)

**If they push:** "Why does the HTTP endpoint get its own test?" Because a service call can be cheap while the controller or mapping layer lazily loads something; the endpoint test asserts the whole `GET /v1/admin/tenants/tree` response also costs at most 3 statements.

### M3. Fetch join versus `@EntityGraph`, and why two queries for the tenant tree?

**Answer:** A tenant has two collections, apps and titles, and fetching both bags in one statement either throws `MultipleBagFetchException` or returns a cartesian product of apps times titles for each tenant. So the reader runs two fetch-join queries in the same persistence context: the first loads the tenants with their apps, and the second loads the titles into the tenant instances Hibernate already holds, which is why the return value of the second call is ignored. That makes the cost two statements however many tenants there are, and the parent and child links are assembled in memory. The rule repository has only one collection to fetch, so it uses `@EntityGraph` on `dependsOn`, which gives one statement per call. The trick depends on both queries running in one transaction, and the tree service is marked read-only transactional.

**Example:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/TenantJpaRepository.java:11-20`
```java
    @Query("select t from TenantEntity t left join fetch t.apps")
    List<TenantEntity> findAllWithApps();

    /**
     * Run AFTER {@link #findAllWithApps()} in the same persistence context: Hibernate fills the
     * titles of the instances it already holds. Fetching both bags in one statement would either
     * throw (MultipleBagFetchException) or return apps x titles rows per tenant.
     */
    @Query("select t from TenantEntity t left join fetch t.titles")
    List<TenantEntity> findAllWithTitles();
```
The second query is only correct because it runs after the first in the same persistence context.

**Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/TenantJpaRepository.java:11-20` (two fetch joins), `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/JpaTenantTreeReader.java:26-32` (the call order), `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/persistence/AlertRuleJpaRepository.java:10-22` (`@EntityGraph` for the single collection)

**If they push:** "What if someone splits those two calls into separate transactions?" My reading is that the second query would fill different instances from the ones the reader assembles, so the titles of the assembled tenants would stay lazy and, with open-in-view off, reading them would throw. I have not tested that; what I did mutate was removing the fetch from the reader, and the flat-count test went red.

### M4. Name three `@Transactional` pitfalls this repo demonstrates.

**Answer:** The first is self-invocation: calling an annotated method through `this` bypasses the Spring proxy, so no transaction starts and a runtime exception does not roll anything back. The second is that a checked exception commits by default, and only `rollbackFor` makes it roll back. The third is `REQUIRES_NEW`, which suspends the outer transaction and gets its own transaction id, cannot see the outer transaction's uncommitted rows, and commits independently, so it survives the outer rollback. I observed these against a real Postgres by comparing transaction ids and checking which rows survived, not from documentation. There is a fourth risk that I did not test: with a connection pool of one, `REQUIRES_NEW` needs a second connection and would deadlock against itself, and that exists only as a comment in the pool configuration.

**Example:** `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TransactionBehaviourIT.java:195-205`
```java
        assertThatThrownBy(() -> probe.outerThenInner("outer-row", "inner-row", true))
                .isInstanceOf(IllegalStateException.class);

        var seen = probe.observed();
        assertThat(seen.innerXid()).as("a different database transaction").isNotEqualTo(seen.outerXid());
        assertThat(seen.innerTxName()).isNotEqualTo(seen.outerTxName());
        assertThat(seen.outerRowsVisibleToInner())
                .as("the outer's uncommitted row is invisible to the suspended-over inner")
                .isZero();
        assertThat(rowExists("inner-row")).as("committed independently").isTrue();
        assertThat(rowExists("outer-row")).as("rolled back with the outer").isFalse();
```
The transaction ids differ and the inner row survives while the outer row does not, which is the `REQUIRES_NEW` behaviour observed rather than asserted from theory.

**Code:** `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TransactionBehaviourIT.java:160-190` (self-invocation and checked exceptions), `services/control-plane/src/test/java/com/streampulse/controlplane/lab/TransactionBehaviourIT.java:192-219` (`REQUIRES_NEW` against `REQUIRED`), `services/control-plane/src/main/resources/application.yml:25-27` (the untested pool-of-one deadlock note)

**If they push:** "Why does this matter for the outbox?" It is the reason the outbox uses `MANDATORY` and not `REQUIRES_NEW`: a new transaction would let the event row commit while the business change rolled back.

### M5. How is the alert engine an idempotent consumer?

**Answer:** The engine keeps one cursor per rule and series, which is the last observation it accepted. An observation whose window start is not later than the cursor's is dropped before any evaluator runs, and it is counted as a duplicate when the start is equal and as stale when it is older. The cursor advances inside the same locked section as the alert transition it causes, so a duplicate can never observe a half-applied state. Dropping an older window is acceptable because aggregates are keyed by window, so one series spans partitions and order across windows is not guaranteed, and a stale window can lose at most a verdict that a newer window has already superseded. I mutation-checked it: removing the guard turned 3 engine tests red, which is recorded in the task list, though I have no raw output pasted for it.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:63-75`
```java
            for (AlertRule rule : rules.rulesFor(observation)) {
                AlertKey key = new AlertKey(rule.ruleId(), observation.series());
                Observation last = cursors.get(key);
                if (last != null && !observation.windowStart().isAfter(last.windowStart())) {
                    metrics.evaluated(
                            rule.condition().kind(),
                            observation.windowStart().equals(last.windowStart())
                                    ? EvaluationOutcome.DUPLICATE
                                    : EvaluationOutcome.STALE);
                    continue;
                }
                Verdict verdict = evaluators.onObservation(rule.condition(), Optional.ofNullable(last), observation);
                cursors.put(key, observation);
```
The `isAfter` check is the whole idempotence rule, and `cursors.put` runs only after it passes.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:59-83` (evaluate under one lock), `services/alert-engine/src/test/java/com/streampulse/alert/domain/AlertEngineTest.java:103-135` (duplicate, old CLEAR duplicate after a re-breach, and older window), `docs/adr/0017-alert-evaluation-and-suppression.md:17-35` (the rule and why dropping is acceptable)

**If they push:** "A duplicate CLEAR arrives after the alert re-fired. What happens?" Nothing: that window start is older than the cursor, so it is stale and cannot resolve the new breach; `duplicateOfOldClearDoesNotResolveNewBreach` asserts exactly that.

### M6. Why does the rule book loader use no consumer group?

**Answer:** Every alert-engine instance needs the whole rule table, not a share of it, so the loader assigns all partitions itself, seeks to the beginning, and never commits offsets. That is the opposite of a consumer group, which would split the partitions between instances. It records the end offsets at start and reports itself loaded only when its position has reached them. The aggregate listener is declared with auto-startup off and a lifecycle component starts it only after the loader reports loaded, so no aggregate is evaluated against a half-loaded rule book. If the loader's consumer fails, it is rebuilt and re-reads from the beginning, which is safe because applying a record is idempotent.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/RuleBookLoader.java:105-127`
```java
        kafka.assign(partitions);
        kafka.seekToBeginning(partitions);
        Map<TopicPartition, Long> end = kafka.endOffsets(partitions);
        ...
            if (!notified && partitions.stream().allMatch(tp -> kafka.position(tp) >= end.get(tp))) {
                loaded = true;
                notified = true;
                log.info("rule book loaded from {} up to {}", topic, end);
                onLoaded.run();
```
`assign` instead of `subscribe` is what opts out of a group, and `onLoaded` is the hook that starts the aggregate listener.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/RuleBookLoader.java:100-130` (assign, seek, catch up), `services/alert-engine/src/main/java/com/streampulse/alert/lifecycle/RuleBookLifecycle.java:33-46` (the listener starts after the load), `services/alert-engine/src/test/java/com/streampulse/alert/RuleBookLoaderIT.java:91-125` (a restart rebuilds the book, including tombstones and bad records)

**If they push:** "What if the config topic does not exist yet?" The loader logs a warning and sleeps one second between attempts until the partitions appear.

### M7. Why does the alert engine acknowledge only after delivery?

**Answer:** The engine updates its state before it delivers, so if delivery failed and the record were simply re-evaluated, the redelivered observation would be recognised as a duplicate and the alert would be lost. An in-memory outbox therefore remembers what has been applied but not yet delivered, per channel. The listener decodes, evaluates and drains the outbox, and only then acknowledges the offset; an exception propagates without acknowledging, so the container redelivers and the next drain finishes the delivery. Each channel retries alone and in order, and a failing channel stops at its first failure so it never reorders, while the others carry on. The error handler is configured never to skip a record. SQS standard queues are at-least-once and unordered, so consumers of that channel have to de-duplicate.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/AggregateListener.java:41-56`
```java
    public void onRecord(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        switch (decoder.decode(record.value())) {
            case AggregateDecoder.Decoded.Valid valid -> processor.process(valid.observation());
            case AggregateDecoder.Decoded.Rejected rejected -> {
                metrics.rejected("aggregate", rejected.reason());
                ...
            }
        }
        ack.acknowledge();
    }
```
`process` evaluates and delivers, and if it throws the method exits before `ack.acknowledge()`.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/AggregateListener.java:41-56` (acknowledge last), `services/alert-engine/src/main/java/com/streampulse/alert/application/AlertOutbox.java:61-90` (per-channel drain), `services/alert-engine/src/main/java/com/streampulse/alert/config/AlertConfig.java:105-122` (never skips, exponential back-off), `services/alert-engine/src/test/java/com/streampulse/alert/application/AlertProcessorTest.java:63-79` (a delivery failure surfaces and the redelivered duplicate finishes it)

**If they push:** "Never skipping sounds dangerous. What if a channel never recovers?" Then the consumer stalls on that record and retries with a back-off that doubles from one second up to thirty; I did not find a dead-letter path for that case in the alert engine, so it is a known cost of choosing never to lose an alert. An undecodable record is the exception: it is counted, acknowledged and skipped, because it would fail identically on every retry.

---

## Senior

### S1. CDC versus a polling publisher: defend the choice.

**Answer:** I chose change data capture because reading the write-ahead log gives low latency and puts no polling load on the outbox table, and Debezium's outbox router turns each row into a keyed record without any publishing code in the service. The cost is operational: a Kafka Connect cluster to run, a connector registered after the service has migrated its schema, and Postgres retaining write-ahead log for a replication slot that goes inactive, which is a disk risk. Both approaches are at-least-once, so neither removes the need for idempotent consumers. A polling publisher is simpler to run and has no Connect cluster, but it adds polling latency and the service has to handle a publish-then-mark failure itself. If the Connect cluster is not worth operating, I would choose polling, and I did not build that alternative.

**Example:** `docker/debezium/outbox-connector.json:25-34`
```json
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.topic.regex": ".*",
    "transforms.outbox.route.topic.replacement": "config.changes.v1",
    "transforms.outbox.route.tombstone.on.empty.payload": "true",
    "transforms.outbox.table.fields.additional.placement": "operation:header:operation,aggregate_type:header:entityKind"
```
The outbox column names are the router's configuration, which is why the migration warns that renaming `payload` or `aggregate_id` breaks the router silently.

**Code:** `docker/debezium/outbox-connector.json:25-34` (the router), `docs/adr/0016-debezium-outbox-router-raw-avro.md:61-69` (the alternatives considered), `docs/adr/0016-debezium-outbox-router-raw-avro.md:42-59` (what breaks)

**If they push:** "Is the end-to-end CDC path proven?" By hand, yes: create, update and delete of one tenant gave three records on the topic, the last a null-value tombstone, recorded in the Phase 6 section of `MILESTONES.md` and in ADR-0016. The Testcontainers version, `OutboxCdcIT`, exists but I did not rerun it in the close-out.

### S2. The connector is down for an hour. What happens?

**Answer:** Writes to the control plane still succeed, because the service only talks to Postgres: I observed 201 and 200 responses while Connect was stopped. The outbox rows accumulate, Postgres retains write-ahead log for the replication slot, and consumers see staleness rather than errors because no new records arrive. When the connector returns it resumes from the slot and the records flow in order, which I also observed. The real risk is that no alert exists on slot lag, and an abandoned slot makes Postgres retain log until the disk fills, so retiring the connector means dropping the slot with `pg_drop_replication_slot`. I would add monitoring on slot lag and on outbox age before relying on this.

**Example:** `docker/debezium/outbox-connector.json:14-18`
```json
    "plugin.name": "pgoutput",
    "slot.name": "control_plane_outbox",
    "publication.name": "control_plane_outbox_pub",
    "publication.autocreate.mode": "filtered",
    "table.include.list": "control_plane.outbox",
```
The named slot is what Postgres retains write-ahead log for while the connector is away.

**Code:** `docker/debezium/outbox-connector.json:14-18` (the slot), `docs/adr/0016-debezium-outbox-router-raw-avro.md:44-49` (the observed outage behaviour and the missing alert)

**If they push:** "How do you know the order survives?" Ordering is guaranteed per key only, because one key maps to one partition; across entities there is none, so a consumer must not assume a tenant's record precedes its title's.

### S3. Why full snapshots plus tombstones on the topic, not deltas?

**Answer:** The topic is compacted, which keeps only the latest record per key, so a delta would be unusable once compaction ran: a consumer replaying from the start would apply a diff to a state it never saw. Each announcement is therefore a complete snapshot of the entity, and a delete is a null-value tombstone. That is also what makes the alert engine's rebuild safe, because applying a record is idempotent and a version older than the one held is ignored. The announcement carries a change id too, but the alert engine does not read it; its protection is the version check. The remaining gap is the one from the tombstone question: a consumer that starts after tombstones have expired can miss deletes.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/ConfigChangeApplier.java:46-71`
```java
    public void apply(String key, byte[] value) {
        ...
        if (value == null) {
            remove(key);
            return;
        }
        ...
        if (change.getEntityType() != EntityType.ALERT_RULE) {
            return;
        }
        if (change.getOperation() == ChangeOperation.DELETED || change.getPayload() == null) {
            remove(key);
            return;
        }
        put(key, change);
```
A null value removes by key, and anything that is not an alert rule is ignored, which is how tenants and titles on the same topic are skipped.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/ConfigChangeApplier.java:46-72` (apply), `services/alert-engine/src/main/java/com/streampulse/alert/domain/RuleBook.java:61-72` (an older version is stale), `libs/contracts/src/main/avro/ConfigChange.avsc:24-25` (payload is a full snapshot, with the reason), `services/alert-engine/src/test/java/com/streampulse/alert/RuleBookLoaderIT.java:128-150` (re-create at version 0 and a stale replay on a fresh load)

**If they push:** "What if a rule is deleted and re-created at version 0?" `RuleBookLoaderIT` covers it: after a tombstone, a re-create at version 0 is applied on a fresh load, and a stale version 0 after version 1 is ignored.

### S4. Explain root-cause suppression and its invariant.

**Answer:** When an alert opens, the engine walks upstream breadth-first, level by level, and the first level holding a firing alert gives the nearest cause, with ties broken by age and then id. When a new alert starts firing, the engine walks downstream breadth-first and demotes the firing alerts it finds to suppressed, and re-attributes alerts that were suppressed by something that was demoted a moment earlier, so a suppressed alert always names a firing alert. When a root resolves, what it was suppressing is re-examined in topological order, ancestors first, because handling a node before its ancestor would promote both and leave a firing alert under a firing one. The invariant is that no firing alert has a firing alert upstream of it in the same tenant. Removing the breadth-first suppression turned 13 of 23 suppression tests red. The scope is tenant-wide, so unlike Alertmanager inhibit rules it can suppress across regions; that is a deliberate coarseness.

**Example:** `services/alert-engine/src/test/java/com/streampulse/alert/domain/SuppressionTest.java:332-341`
```java
        void releaseCascadesInTopologicalOrder() {
            breach("R");
            Alert a = breach("A").get(0);
            breach("B");

            clear("R");

            assertThat(states()).containsEntry("A", "FIRING").containsEntry("B", "SUPPRESSED<-A");
            assertThat(alertOn("B").suppressedBy()).isEqualTo(a.alertId());
        }
```
After the root resolves, A is released first and B ends up suppressed by A, not firing beside it.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:213-293` (find the root, suppress downstream, release), `services/alert-engine/src/test/java/com/streampulse/alert/domain/SuppressionTest.java:321-342` (release order), `docs/adr/0017-alert-evaluation-and-suppression.md:42-72` (the decision and the comparison with Alertmanager)

**If they push:** "Why would you not use Alertmanager?" I would use its model in production unless the topology had to come from rule data, as it does here; my version earns its place where the control plane validates the graph at save time and the cause must be named in the alert itself. The ADR says the same, and it notes I have to own the algorithm, tests and state.

### S5. Why reject cycles at save time if the walk keeps a visited set anyway?

**Answer:** The visited set only guarantees that the search terminates. It cannot give a deterministic topological order, and it cannot tell the person who saved a bad rule what went wrong. The release step needs a rank for every node so that ancestors are handled first, and Kahn's algorithm produces that rank as a by-product; if it cannot remove every node, whatever is left lies on a cycle or downstream of one, and the alert engine's validator names the cycle in its exception. So there are two layers: the control plane rejects a cycle when a rule is saved, so a person gets a 422 that lists the nodes it could not place, and the alert engine builds its graph with the same kind of check when it loads rules and skips and counts a rule that would close a cycle. The searches still keep a visited set because termination should not depend on validation done elsewhere. Each service has its own copy of the algorithm; the alert engine's was written to be lifted into a shared library, which I have not done.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/graph/RuleGraphValidator.java:91-95`
```java
        if (order.size() < inDegree.size()) {
            Set<String> remaining = new TreeSet<>(inDegree.keySet());
            remaining.removeAll(order);
            throw new CyclicDependencyException(findCycle(remaining, downstream));
        }
```
If the order has fewer nodes than the graph, the leftover nodes contain a cycle, and `findCycle` extracts and names it.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/graph/RuleGraphValidator.java:54-97` (Kahn), `services/control-plane/src/main/java/com/streampulse/controlplane/domain/DependencyGraph.java:37-67` (the control plane's own Kahn sort), `services/alert-engine/src/main/java/com/streampulse/alert/adapter/in/kafka/ConfigChangeApplier.java:87-92` (a cyclic rule is rejected and counted at load)

**If they push:** "Does the control plane cover only alert rules?" No: service-node dependencies go through the same graph check on create and update in `ServiceNodeService`.

### S6. The alert engine restarts. What breaks?

**Answer:** Everything the engine holds in memory is lost: the active alerts, the cursors, the SSE buffer and its sequence, and any change still sitting undelivered in the in-memory outbox. An alert that was firing is forgotten and no RESOLVED is ever emitted, so downstream consumers keep it open. Windows after the last committed offset are re-evaluated against empty state, so a still-true incident fires again, with the same id only if its first window is replayed. Restoring open alerts from the `alerts.v1` topic at startup is a known cut, task T6B-18, and I did not build it. The engine is also single-writer: with several instances, series are split by partition, but root-cause suppression across series on different instances cannot see each other.

**Example:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:48-50`
```java
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<AlertKey, Observation> cursors = new HashMap<>();
    private final Map<AlertKey, Alert> active = new HashMap<>();
```
The two maps are the entire alert state, and nothing persists them.

**Code:** `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:48-50` (in-memory state), `docs/adr/0017-alert-evaluation-and-suppression.md:74-89` (what is lost and the unbuilt mitigation), `MILESTONES.md:1548` (the Phase 6 honest gaps list this as cut)

**If they push:** "How would you fix it?" The ADR's unbuilt mitigation is to read the tail of `alerts.v1` at start and re-open the latest firing or suppressed alert per key; the topic key omits the series, so the series would have to be rebuilt from the rule's metric and the event's title and region. I have not tried it.

### S7. Where can the control plane's concurrency go wrong?

**Answer:** Two concurrent re-parentings of tenants can each pass the cycle check and together create a cycle. Each transaction walks from its proposed parent to the root, sees no loop, and updates a different row, so the version check on each row never conflicts. That is write skew across rows, and ADR-0015 says an advisory lock or serializable isolation would prevent it; it is not prevented today. Optimistic locking also protects only the read-modify-write window inside one request, because the update request has no version field, so the service re-reads the entity and applies the change to whatever it finds; two clients editing the same tenant end up with last write wins rather than a 409. The 409 handler for an optimistic-lock failure exists, but searching the control-plane sources for `Optimistic` finds only that handler, so I believe no test drives it, and I have not run a concurrent update to see the response.

**Example:** `services/control-plane/src/main/java/com/streampulse/controlplane/domain/TenantHierarchy.java:23-37`
```java
    public static void assertNoCycle(
            TenantId tenant, TenantId proposedParent, Function<TenantId, Optional<TenantId>> parentOf) {
        Set<TenantId> seen = new HashSet<>();
        TenantId cursor = proposedParent;
        while (cursor != null) {
            if (cursor.equals(tenant)) {
                throw new RuleViolationException(
                        "tenant '" + tenant + "' would become its own ancestor via '" + proposedParent + "'");
            }
            if (!seen.add(cursor)) {
                return;
            }
            cursor = parentOf.apply(cursor).orElse(null);
        }
    }
```
It reads other rows with no lock, so two transactions can each get a clean answer from a snapshot that the other is about to change.

**Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/domain/TenantHierarchy.java:23-37` (the check-then-write walk), `services/control-plane/src/main/java/com/streampulse/controlplane/application/TenantService.java:50-59` (update calls it, then writes), `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/in/web/dto/TenantDtos.java:26-29` (the update request carries no version), `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/in/web/ProblemAdvice.java:52-55` (the untested 409 handler), `services/control-plane/src/main/resources/db/migration/V1__control_plane.sql:1-14` (restrict foreign keys and the not-its-own-parent check)

**If they push:** "What does the database already prevent?" A tenant cannot be its own parent, through a check constraint in the migration, and every foreign key is `RESTRICT`, so deleting something still referenced is refused by the database even against a concurrent insert.

---

## Say this honestly

- There is no authentication on either service; the tenant is a filter the client asserts.
- Apps are not announced through the outbox. Two files confirm it: the `EntityType` enum in `libs/contracts/src/main/avro/ConfigChange.avsc:12` has the symbols ENTITY_UNKNOWN, TENANT, TITLE, ALERT_RULE and PARTNER_SUBSCRIPTION, with no app type, and `AppService` in `services/control-plane/src/main/java/com/streampulse/controlplane/application/AppService.java:19-25` takes only an app repository and a tenant repository, with no `ChangeRecorder`. Service nodes are announced, but as ENTITY_UNKNOWN, because the contract has no symbol for them (ADR-0015). Do not claim complete change-data-capture coverage of the control plane.
- Only the alert engine consumes `config.changes.v1`; a search of `services/*/src/main` finds the topic only in `services/alert-engine/src/main/resources/application.yml`. The README diagram line "every service refreshes its caches" is the target design, not the built system, and `README.md:58-63` says so itself.
- The outbox has no retention job and nothing alerts on relay lag or replication-slot lag.
- Red-first was not observed for every class; the mutation checks are what prove the key tests can fail.
- Pool sizing, and the heartbeat, buffer and grace values, are targets and have not been measured as limits.
- I did not rerun `OutboxCdcIT` (Testcontainers plus Debezium) in the close-out; the live proof was done by hand.

## Stories and numbers

[What broke](../05-what-broke.md): stories 1, 7 (mutation habit). [Numbers](../06-numbers.md): test counts
and targets.
