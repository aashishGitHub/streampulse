# ADR-0017 — Alert evaluation: idempotent in-memory state, BFS root-cause suppression, MVC SSE

**Status:** accepted · **Date:** 2026-10-13 · **Phase:** 6B · **Builds on** ADR-0006, ADR-0013, ADR-0014

## Context

The alert engine consumes final windowed aggregates (at-least-once, windows of one series spread over
partitions) and rules from a compacted topic, and must emit alerts that fire once, resolve once, and
stay quiet when an upstream failure already explains them. Four decisions are not obvious.

## Decision 1 — evaluation model: a strategy per condition, a cursor per (rule, series)

`Threshold`, `RateOfChange` and `Absence` are strategies behind one interface, chosen by a `switch`
over a sealed `Condition` (the compiler fails the build when a new kind has no strategy). A strategy
is stateless; the engine owns one **cursor** per (rule, series): the last observation accepted.

- An observation whose window start is not later than the cursor's is dropped before any strategy
  runs. That is the whole idempotence rule: a redelivery (equal start) and a reordered older window
  (smaller start) both change nothing.
- The cursor advances in the same locked section as the alert transition it causes.
- Rate of change compares only adjacent windows; a gap is "no decision", not a rate.
- Absence is clock-driven (`sweep`), sized by window length times N plus the aggregator's grace, and
  only for series seen at least once.

The state machine (FIRING / SUPPRESSED / RESOLVED, dedupe by `tenant:rule:series`) is a pure function
of (current alert, verdict, nearest firing upstream alert). A resolved alert is forgotten; the next
breach is a new incident. The alert id is `hash(tenant, rule, series, window the incident began in)`:
deterministic and stable for the incident's life. The contract's doc says `hash(ruleId, windowStart)`;
that would collide for two series of one rule breaching in the same window, so tenant and series are
included (deviation, intentional).

**Why dropping older windows is acceptable.** Aggregates are keyed by window, so one series spans
partitions and order across windows is not guaranteed. Dropping a stale window loses at most a
verdict that a newer window has already superseded. Re-keying by series upstream would give order
but changes the processor's contract; not done.

**Delivery after the state update.** State is updated before delivery, so a retry that re-evaluates
would see a duplicate and never deliver. An in-memory outbox records which channels still owe each
change; a redelivered aggregate drains it. Each channel is retried alone, never sees an alert twice
after accepting it, and does not hold back the others.

## Decision 2 — suppression by breadth-first search on a rule-derived graph

Each rule names the node it watches (`category`) and its upstream nodes (`dependsOn`); the per-tenant
graph is the union of those edges, rebuilt and cycle-checked (Kahn) whenever a rule changes.

- Alert opens: BFS over reverse edges, level by level; the first level holding a FIRING alert is the
  nearest cause (ties: oldest, then id). A suppressed middle node is skipped, since only FIRING alerts
  are causes.
- Alert starts firing: BFS forward; firing descendants become SUPPRESSED by it, and alerts suppressed
  by something just demoted are re-attributed to it, so `suppressedBy` always names a firing alert.
- Root resolves: what it suppressed is re-examined in topological order (ancestors first). Still-true
  alerts without another firing ancestor become FIRING; others are re-attributed.

Invariant: no FIRING alert has a FIRING alert upstream of it in the same tenant. Tested on a chain, a
500-node chain, a diamond, a release-order graph and two independent roots, and
mutation-checked (below). Cycles are rejected at the rule book; BFS keeps a visited set anyway.

### Compared with Alertmanager inhibition (honest)

| | This engine | Alertmanager `inhibit_rules` |
|---|---|---|
| Relationship | derived from a topology (`dependsOn`), transitive by construction | pairs of label matchers, written per pair; transitivity must be spelled out |
| Scoping | tenant only | arbitrary `equal` labels (region, cluster, ...); **ours is coarser and can suppress across regions** |
| Attribution | `suppressedBy` names the nearest firing cause | inhibition is a notification-time filter; the API reports what inhibited an alert (VERIFY in the current docs) |
| Release | explicit state change, emitted as an event | the inhibited alert simply starts notifying again |
| Cost | we own the algorithm, tests and state | a maintained, widely understood product |
| Where state lives | this process | the Alertmanager cluster (gossip-replicated silences and notification log) |

If this were production I would use Alertmanager's model unless the topology genuinely had to come from
the rule data, as it does here. The BFS version earns its keep where the dependency is a graph the
control plane validates at save time and the cause must be named in the alert.

## Decision 3 — state is in memory and rebuilt from topics

- **Rule book**: rebuilt at start from `config.changes.v1` read from the beginning (a table loader:
  no group, no commits). The aggregate listener starts only after it has read to the end offsets
  noted at start, so no aggregate meets a partial book; early aggregates wait in the topic.
- **Aggregates**: resume from the group's committed offset. A new group starts at `latest`, because
  replaying retained history would fire and resolve long-finished incidents.
- **Lost on restart**: active alerts, cursors, the SSE buffer and sequence. An alert that was firing
  is forgotten **with no RESOLVED ever emitted**; downstream consumers keep it open. Windows after the
  last commit are re-evaluated against empty state, so a still-true incident fires again, with the same
  id only if its first window is replayed. Mitigation not built: read the tail of `alerts.v1` at start
  and re-open the latest FIRING/SUPPRESSED per key (the topic key omits the series, so the rule's metric
  and the event's title and region would be used to reconstruct it).
- **Single writer**: one instance's engine is behind one lock. Running several instances splits
  series by partition, so alerts for one rule and series stay on one instance, but root-cause
  suppression across series on different instances cannot see each other. Not solved here.

## Decision 4 — MVC with `SseEmitter` on virtual threads, not WebFlux

ADR-0014 chose WebFlux for the query API because it holds thousands of dashboard streams with
backpressure. Here the fan-out is small (operators, not end users), everything else in the service
is blocking (Kafka client, SQS client, a lock), and the Kafka thread must never write to a socket.
So each stream is a bounded queue drained by one virtual thread into an `SseEmitter`; a full queue ends
that stream and the client resumes by `Last-Event-ID` from a bounded replay buffer. The queue, the
buffer and the sequence share one lock, which is what makes replay-then-live gap-free (tested
concurrently). WebFlux would give the same with `Flux.merge` for the heartbeat and `onBackpressure`
operators, at the price of a second concurrency model in a service that otherwise has none. If
thousands of concurrent streams became the requirement, revisit.

## Consequences

**Positive**: duplicates cannot double-alert; a failing channel cannot lose or repeat an alert; the
domain and the graph package are plain Java (the graph package is liftable into a shared library for the
control plane); suppression is explained in the alert itself.

**Negative**: state loss on restart (above); tenant-wide suppression scope; the sequence restarts with
the process, so a client must re-read the list when an id goes backwards; delivery to the queue is
at-least-once and unordered; no authentication on the read endpoints.

## Verification (what was run)

Unit tests for every strategy, the state machine, suppression (chain, long chain, diamond, release
order, two roots, cross-tenant), cycle detection (self-loop, 2-cycle, 3-cycle), duplicate and stale
windows; integration tests against a real broker and moto. Mutation checks and counts are in
`MILESTONES.md` once the coordinator records them; local simulation proves API-level behaviour only
(ADR-0003).
