# Phase 6B — Alert engine: Requirements

**Status:** draft · **Depends on:** Phase 3 (aggregates), Phase 6A control-plane (rules on the config topic) ·
**Delivers:** rules evaluated against windowed aggregates, de-duplicated alert state, root-cause
suppression, and alerts delivered to a topic, a queue and a live HTTP stream.

## Why

An aggregate nobody evaluates is a chart. The engine turns "viewers dropped 40%" into one alert
that fires once, resolves when it clears, and stays quiet when an upstream failure already
explains it. The interesting parts are small algorithms (strategy evaluation, an idempotent state
machine, breadth-first suppression, cycle detection) that must be provably correct under
at-least-once delivery.

## Vocabulary

- **Series**: one (tenant, title, region, metric) stream of windows. An absent title or region
  means "across all".
- **Rule**: a condition on one metric, owned by one tenant, attached to one service node.
- **Node**: a stage of the delivery chain (for example CDN, PACKAGER, PLAYER).
- **Upstream**: a node whose failure can cause failures in the nodes that depend on it.
- **Incident**: one continuous firing of one rule on one series, with one stable alert id.

## Requirements (EARS, no technology named)

### Evaluation

- **REQ-AL-01** WHEN a final aggregate arrives for a series and a rule of the same tenant targets
  that metric, THE SYSTEM SHALL evaluate the rule's condition against that aggregate. `[evaluation]`
- **REQ-AL-02** WHEN a threshold rule's comparison holds for the current window, THE SYSTEM SHALL
  report the condition as breaching, and otherwise as clear. `[strategy]`
- **REQ-AL-03** WHEN a rate-of-change rule is evaluated and the immediately preceding window of
  the same series is known, THE SYSTEM SHALL compare the relative change between the two windows
  with the rule's fraction and direction. `[strategy]`
- **REQ-AL-04** WHEN the preceding window is not the immediately adjacent one, THE SYSTEM SHALL
  make no decision for a rate-of-change rule rather than compare across a gap. `[strategy]`
- **REQ-AL-05** WHEN a series that has been seen before delivers no window for the configured
  number of windows plus the settling delay, THE SYSTEM SHALL report an absence rule as breaching,
  and WHEN data returns SHALL report it as clear. `[strategy]`
- **REQ-AL-06** WHEN an aggregate for a series arrives whose window start is not later than the
  latest window already evaluated for that rule and series, THE SYSTEM SHALL leave all state
  unchanged and emit nothing. `[idempotence, at-least-once]`

### Alert lifecycle

- **REQ-AL-07** WHEN a condition becomes breaching and no alert is active for that tenant, rule
  and series, THE SYSTEM SHALL open an alert in the firing state and notify. `[state machine]`
- **REQ-AL-08** WHILE an alert is active, WHEN the condition is breaching again, THE SYSTEM SHALL
  NOT notify again. `[dedupe]`
- **REQ-AL-09** WHEN the condition clears while an alert is active, THE SYSTEM SHALL mark it
  resolved, notify, and forget it as active. `[state machine]`
- **REQ-AL-10** WHEN an alert reopens after being resolved, THE SYSTEM SHALL treat it as a new
  incident with a new alert id. `[state machine]`
- **REQ-AL-11** THE SYSTEM SHALL derive an incident's alert id deterministically from tenant,
  rule, series and the window in which the incident began, and keep it unchanged for the incident's
  whole life (firing, suppressed, resolved). `[idempotent consumers]`

### Root-cause suppression

- **REQ-AL-12** WHEN a condition becomes breaching and any node upstream of the rule's node, in
  the same tenant, has a firing alert, THE SYSTEM SHALL record the alert as suppressed, naming the
  nearest such upstream alert as its cause. `[BFS]`
- **REQ-AL-13** WHEN an alert starts firing, THE SYSTEM SHALL mark every firing alert on a node
  downstream of it, in the same tenant, as suppressed by it. `[BFS]`
- **REQ-AL-14** WHEN a firing alert resolves, THE SYSTEM SHALL re-examine each alert it was
  suppressing: those whose condition still holds and that have no other firing upstream alert SHALL
  become firing, and those that do SHALL be re-attributed to that alert. `[BFS, release]`
- **REQ-AL-15** WHEN the condition of a suppressed alert clears, THE SYSTEM SHALL resolve it.
- **REQ-AL-16** THE SYSTEM SHALL NOT suppress across tenants, and SHALL treat two independent
  upstream failures independently.

### Rules

- **REQ-AL-17** WHEN a rule is created or changed, THE SYSTEM SHALL use the latest version of it
  for later evaluations, and SHALL ignore a change older than the version it already holds.
- **REQ-AL-18** WHEN a rule is deleted, THE SYSTEM SHALL resolve its active alerts and stop
  evaluating it.
- **REQ-AL-19** WHEN a rule's upstream dependencies would make the dependency graph cyclic, THE
  SYSTEM SHALL reject it and name the cycle, both when saved and when read back.
- **REQ-AL-20** WHEN a rule definition cannot be understood, THE SYSTEM SHALL skip that rule,
  count it, and continue loading the others.
- **REQ-AL-21** WHILE the rule set has not yet been fully loaded, THE SYSTEM SHALL NOT consume
  aggregates, so none is evaluated against a partial rule set.

### Delivery

- **REQ-AL-22** WHEN an alert changes state, THE SYSTEM SHALL deliver it to the alert topic, the
  alert queue and every matching live stream subscriber.
- **REQ-AL-23** WHEN delivery to one channel fails, THE SYSTEM SHALL retry that channel only,
  and SHALL NOT acknowledge the aggregate that caused the change until every channel has it.
- **REQ-AL-24** WHEN an aggregate cannot be decoded, THE SYSTEM SHALL count it, log it,
  acknowledge it and carry on.
- **REQ-AL-25** THE SYSTEM SHALL acknowledge an aggregate only after the state update and the
  deliveries it caused.

### Read interface

- **REQ-AL-26** WHEN a client asks for a tenant's alerts, THE SYSTEM SHALL return the active ones
  (firing and suppressed) as a JSON array. `[contract]`
- **REQ-AL-27** WHEN a client opens the alert stream for a tenant, THE SYSTEM SHALL send each
  alert change for that tenant as one event named `alert`, each with a strictly increasing
  sequence number as its id, and a comment line at a fixed heartbeat interval while idle.
- **REQ-AL-28** WHEN a client reconnects presenting the id of the last event it saw, THE SYSTEM
  SHALL replay the retained events after that id, in order, before live events, without gap or
  duplicate between the two.
- **REQ-AL-29** WHEN a subscriber cannot keep up, THE SYSTEM SHALL end its stream rather than
  block delivery to others or to the evaluation path.
- **REQ-AL-30** WHEN the tenant is missing or malformed, THE SYSTEM SHALL answer 400 with a
  problem document, on both operations.

## Non-functional

- Evaluation per aggregate is in-memory and takes microseconds; targets, not measured until Phase 9.
- Alert delivery is at-least-once. Consumers de-duplicate on the alert id and state.
- Targets until Phase 9 measures them: heartbeat 15 s, replay buffer 1000 events.

## Honest gaps (stated, not hidden)

- State is in memory. After a restart the active-alert set and the previous-window cursors are
  gone; an alert that was firing is forgotten without a RESOLVED. ADR-0017 records this.
- The read endpoints have no authentication; `tenantId` is a filter the client asserts, not an
  authorisation.
- A series' windows are spread across partitions, so windows can be consumed out of order. A
  window older than the latest one already evaluated is dropped, not reordered.

## Out of scope

Silences, notification routing, escalation, rule-evaluation `for:` durations, per-region
suppression scoping, persistence of alert history, restoring open alerts after a restart.
