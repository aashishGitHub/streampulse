# Phase 6B — Alert engine: Design

## Flow of control and data

```
 config.changes.v1 (compacted)                     telemetry.agg.v1 (6 partitions, FINAL windows)
        |  read from the beginning, then follow            |  consumer group alert-engine, manual ack
        v                                                  v
 RuleBookLoader ──decode──▶ ConfigChangeApplier     AggregateListener ──decode──▶ Observation
   (all partitions, no group,    | RuleJsonParser     (bad bytes: count, log, ack)        |
    no commits)                  v                                                          v
                            ┌─────────┐   rules, graph   ┌────────────────────── AlertProcessor ─────────────────┐
                            │ RuleBook│◀─────────────────│ 1 engine.evaluate(obs) ── under one lock ──▶ changes  │
                            └─────────┘                  │ 2 outbox.add(changes)                                 │
                                 ▲                       │ 3 outbox.drain(): per alert, per channel not yet done │
   AbsenceSweeper ── tick ───────┼───▶ engine.sweep(now) │ 4 only then: listener acks the offset                │
                                                         └───────────────────────────────────────────────────────┘
                                                                  |             |               |
                                                          KafkaAlertChannel  SqsAlertChannel  AlertFeed (SSE hub)
                                                          alerts.v1 (Avro)   queue `alerts`   ring buffer + subscribers
                                                                                                    |
 GET /v1/alerts?tenantId=  ◀── engine.activeAlerts ──                                    GET /v1/alerts/stream?tenantId=
```

### Inside `AlertEngine.evaluate(obs)` (domain, framework-free, one lock)

```
for each rule of obs.tenant targeting obs.metric (and title/region filter):
    cursor = cursors[(rule, series)]                  -- the last observation accepted for that pair
    if cursor exists and obs.windowStart <= cursor.windowStart:  DUPLICATE/STALE → stop, no state change
    verdict = Evaluators.onObservation(rule.condition, cursor, obs)    BREACHING | CLEAR | NO_DECISION
    cursors[(rule, series)] = obs                     -- advance AFTER the verdict, so a retry of this call is a no-op
    transition = AlertStateMachine.next(active alert?, verdict, root cause?)
    apply transition, then cascade (below)
```

`AlertStateMachine` (pure; time and ids passed in):

| current        | BREACHING                          | CLEAR                | NO_DECISION |
|----------------|------------------------------------|----------------------|-------------|
| none           | root found ? SUPPRESSED : FIRING   | nothing              | nothing     |
| FIRING         | nothing (dedupe)                   | RESOLVED, forgotten  | nothing     |
| SUPPRESSED     | nothing                            | RESOLVED, forgotten  | nothing     |

A RESOLVED alert is removed from the active set; a later breach is a new incident.

### Root-cause suppression (breadth-first search on the node graph)

The graph has an edge `U -> D` for every rule on node `D` that lists `U` in `dependsOn`
(upstream). One graph per tenant, rebuilt (and checked for cycles) when a rule changes.

```
findRoot(tenant, node)     BFS over REVERSE edges from `node`, nearest ancestors first.
                           First node level holding a FIRING alert wins; ties broken by
                           earliest fired-at, then alert id. visited set ⇒ terminates even on bad input.
suppressDownstream(alert)  BFS over edges from alert.node; every FIRING alert on a reached node
                           becomes SUPPRESSED(suppressedBy = alert.id).
release(resolved root)     candidates = SUPPRESSED alerts reached by BFS downstream whose suppressedBy = root.
                           Process in TOPOLOGICAL order (Kahn rank, ancestors first):
                               newRoot = findRoot(candidate.node)
                               present → stay SUPPRESSED, re-attributed to newRoot
                               absent  → FIRING (its condition last evaluated true), then suppressDownstream
```

Why topological order for release: with a diamond `R -> A -> B` and `R -> B`, processing B before A
would promote B, and then A would promote too although B has A upstream, leaving a firing alert
with a firing ancestor. Ancestors first removes the order dependence. Invariant held after every
call: **no FIRING alert has a FIRING alert at an upstream node of the same tenant.**

### Rule-graph validation (Kahn)

`RuleGraphValidator.topologicalOrder(edges)` repeatedly removes nodes with no remaining
incoming edge (smallest name first, so output is deterministic). Whatever is left is on or behind a
cycle; to NAME a cycle, walk backwards through remaining predecessors until a node repeats.
Used by `RuleBook` (read side, defence in depth) and designed to be called by the control plane at
save time (see "Reuse" below).

## Keys, partitions, ordering

| Thing | Key | Why |
|---|---|---|
| `telemetry.agg.v1` in | `tenant:title:region:metric:windowStart` | Set by the processor. One series' windows spread over partitions, so cross-window order is NOT guaranteed; REQ-AL-06 drops a window older than the latest evaluated. |
| `config.changes.v1` in | `entityId` | Compacted: the latest value per rule is the rule. Null value = tombstone. |
| `alerts.v1` out | `tenantId:ruleId` | A FIRING and its RESOLVED stay ordered. Two series of one rule share a key and a partition. |
| Alert id | `hash(tenant, rule, series, windowStart of incident start)` | Deterministic, stable for the incident's life. The contract says `hash(ruleId, windowStart)`; without tenant and series two series of one rule breaching in the same window would collide and a consumer would drop one. Deviation recorded in ADR-0017. |
| SSE `id:` | per-instance counter | Strictly increasing within an instance only. After a restart it restarts; a client that sees `id` go backwards must re-read `GET /v1/alerts`. |

## Rule payload (what `ConfigChange.payload` carries for ALERT_RULE)

`ConfigChange.avsc` says the payload is entity JSON owned by the control plane and does not define
the rule's shape. The engine parses the shape of `AlertRule` in `api/openapi/control-plane-v1.yaml`
(`ruleId, tenantId, metric, comparator, threshold, severity, category, dependsOn`) and ignores
unknown fields. Optional additions it understands: `name`, `kind` (`THRESHOLD` default |
`RATE_OF_CHANGE` | `ABSENCE`), `fraction`, `direction` (`RISE`|`FALL`|`EITHER`), `windows`,
`titleId`, `region`. Missing `ruleId`/`tenantId` fall back to the record's `entityId` /
`tenantId`. A missing category becomes the node `UNCATEGORISED`, which has no edges.

## Start-up and restart

1. `RuleBookLoader` assigns every partition of the config topic (no consumer group, no commits),
   seeks to the beginning, notes the end offsets at that moment, and applies records until it
   has reached them. Then it flips `loaded` and keeps following on its own thread.
2. Only when `loaded` is true does the lifecycle start the aggregate listener container.
   Aggregates that "arrive" earlier simply wait in the topic (lag grows, nothing is lost or
   evaluated against a partial rule set). If the config topic is empty the end offsets are 0 and
   loading completes at once.
3. The aggregate group resumes from its committed offset; a brand-new group starts at the end
   (`latest`), because replaying days of history through the rules would fire and resolve a
   backlog of long-finished incidents.
4. Lost on restart: active alerts, cursors, the SSE buffer and sequence. Windows after the last
   committed offset are re-evaluated against empty state. See ADR-0017.

## Delivery and the in-memory outbox

State is updated first, delivery second. If a channel then fails and the aggregate is redelivered,
the idempotence guard would treat it as a duplicate and the alert would never be delivered.
So every change is first appended to `AlertOutbox` with the set of channels still owing it.
`drain()` delivers in order, per channel, removing a channel when it succeeds and leaving the
entry on failure; a redelivered duplicate aggregate still calls `drain()`, which finishes the job.
The listener acks only after `drain()` returns without throwing.

| Channel | Failure semantics |
|---|---|
| Kafka `alerts.v1` | idempotent producer, `acks=all`; send is awaited with a timeout; failure throws |
| SQS `alerts` | awaited; failure throws; the queue is created idempotently at start-up and lazily on first use |
| SSE feed | never throws: non-blocking offers to bounded per-subscriber queues |

## SSE

`AlertFeed` assigns the sequence, appends to a bounded ring buffer and offers to each subscriber's
bounded queue, all under one lock; `subscribe(tenant, lastEventId)` replays from the buffer into the
queue and registers for live events under the same lock, so replay and live cannot interleave or
leave a gap. A full queue closes that subscriber (REQ-AL-29): the stream ends and the client
reconnects with `Last-Event-ID`. One virtual thread per connection drains the queue to the
`SseEmitter` and sends `: heartbeat` when nothing arrives within the interval, so a slow client
can never block the Kafka consumer thread. MVC + `SseEmitter` rather than WebFlux: ADR-0017.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| Aggregate redelivered (same window) | no state change, no second alert | `AlertEngineTest.duplicateAggregateChangesNothing`, `AlertEngineIT.duplicateAggregateProducesNoSecondAlert` |
| Older window arrives after a newer one | dropped | `AlertEngineTest.olderWindowIsIgnored` |
| Breach repeats while firing | no second notification | `AlertEngineTest.repeatedBreachDoesNotRefire` |
| Upstream fires, downstream firing | downstream becomes SUPPRESSED(by root) | `SuppressionTest.rootFiringSuppressesFiringDescendants` |
| Downstream breaches while upstream firing | born SUPPRESSED, nearest root named | `SuppressionTest.chainNamesTheNearestRoot`, `diamond` (tieBrokenByAge, reattributedToTheOtherParent), `independentRoots` |
| Root resolves | still-true suppressed alerts become FIRING, in topological order | `SuppressionTest.releaseCascadesInTopologicalOrder` |
| Cyclic rule dependencies | rejected, cycle named | `RuleGraphValidatorTest` (self-loop, 2-cycle, 3-cycle), `RuleBookTest.cyclicRuleIsRejected` |
| Kafka/SQS send fails | aggregate not acked, redelivered, only that channel retried | `AlertOutboxTest.failedChannelIsRetriedAloneAndOthersNotRepeated`, `AggregateListenerTest.deliveryFailureIsNotAcknowledged` |
| Undecodable aggregate | counted, acked, next record processed | `AggregateDecoderTest`, `AggregateListenerTest.undecodableRecordIsAcknowledged` |
| Garbage rule JSON | skipped and counted, others load | `ConfigChangeApplierTest.badPayloadIsSkipped` |
| Tombstone | rule removed, its alerts resolved | `ConfigChangeApplierTest.tombstoneRemovesTheRule`, `RuleBookLoaderIT.restartRebuildsTheBookIncludingTombstones` |
| Out-of-order rule replay | older version ignored | `RuleBookTest.olderVersionIsIgnored` |
| Aggregates before book loaded | not consumed | `RuleBookLoaderIT`, `AlertEngineWiringTest.aggregateListenerIsNotAutoStarted` |
| Slow SSE client | its stream ends; others and the evaluator unaffected | `AlertFeedTest.fullQueueClosesOnlyThatSubscriber` |
| SSE reconnect | replay after Last-Event-ID, no gap, no duplicate, ordered | `AlertFeedTest.replayThenLiveHasNoGapOrDuplicate`, `SseStreamTest.reconnectWithLastEventIdResumes` |
| Missing tenantId | 400 problem+json on both operations | `AlertApiTest` |
| Restart | in-memory alert state lost | documented (ADR-0017), not mitigated |

## Reuse by the control plane

`com.streampulse.alert.domain.graph` (`RuleGraphValidator`, `Edge`, `CyclicDependencyException`)
imports only `java.*`. The control plane cannot depend on a service module, so the clean
move is to lift those three files unchanged into `libs/platform` (shared kernel) and have both
depend on it; the engine's tests move with them. API:
`RuleGraphValidator.validate(existingEdges, candidateNode, candidateUpstreams)` throws
`CyclicDependencyException` (carries `cycle()`; map to HTTP 422 as the control-plane contract says).

## Decisions

- ADR-0017: evaluation model, idempotent state, BFS suppression vs Alertmanager inhibition,
  where state lives, MVC SSE.
