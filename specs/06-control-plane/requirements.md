# Phase 6 (part A) — Control Plane: Requirements

**Status:** in progress · **Depends on:** Phase 1 (change-record contract, `api/openapi/control-plane-v1.yaml`)
· **Delivers:** the write model (tenants and their tree, apps, titles, alert rules, service nodes and
their dependencies) and an atomic, ordered announcement of every change to the rest of the system

## Why

Everything the pipeline treats as configuration has to be changed somewhere, by someone, and
the other services have to hear about it. "Save, then tell the others" has no atomicity: a
crash between the two leaves the store and the announcement stream permanently disagreeing.
This service makes the change and its announcement one atomic act, and it is where the repo
teaches the data-access traps (N+1) and the transaction-boundary traps (self-invocation,
checked exceptions, suspended transactions).

## Requirements (EARS; no technology named)

### Tenants and the tenant tree

- **REQ-CP-01** WHEN a client creates a tenant with an unused id THE SYSTEM SHALL store it and
  answer "created"; WHEN the id is already in use THE SYSTEM SHALL answer "conflict" and change nothing.
- **REQ-CP-02** THE SYSTEM SHALL accept only tenant ids that satisfy the platform tenant-id rule,
  and SHALL refuse the reserved id `tree`, which would shadow the tree resource; otherwise "bad request".
- **REQ-CP-03** WHEN a tenant names a parent THE SYSTEM SHALL require the parent to exist; otherwise
  "unprocessable".
- **REQ-CP-04** THE SYSTEM SHALL never let a tenant become its own ancestor. WHEN a create or update
  would make it one (itself as parent, or one of its descendants as parent) THE SYSTEM SHALL answer
  "unprocessable" and change nothing.
- **REQ-CP-05** WHEN a client asks for the tenant tree THE SYSTEM SHALL return every root tenant with
  its descendants, and each tenant's apps and titles, using a number of data-store round trips that
  does NOT grow with the number of tenants (target: at most 3). `[N+1]`
- **REQ-CP-06** WHEN a client deletes a tenant that still has children, apps, titles, alert rules or
  service nodes THE SYSTEM SHALL answer "conflict" and change nothing.
- **REQ-CP-07** WHEN a client reads, updates or deletes a tenant that does not exist THE SYSTEM SHALL
  answer "not found".

### Apps and titles

- **REQ-CP-08** THE SYSTEM SHALL let a client create, list and delete the apps of an existing tenant;
  an unknown tenant is "not found", a duplicate app id is "conflict".
- **REQ-CP-09** THE SYSTEM SHALL let a client create, read, list (optionally by tenant), update and
  delete titles; a title belongs to exactly one existing tenant; the tenant cannot be changed.

### Alert rules

- **REQ-CP-10** THE SYSTEM SHALL let a client create, read, list (optionally by tenant), update and
  delete alert rules; a rule belongs to exactly one existing tenant; the tenant cannot be changed.
- **REQ-CP-11** WHEN a rule declares upstream categories THE SYSTEM SHALL require it to name its own
  category, and WHEN the declared edges, together with the tenant's other rules, would contain a
  cycle (including a category depending on itself) THE SYSTEM SHALL answer "unprocessable" and store
  nothing. The check is a topological sort at save time. `[BS-05]`
- **REQ-CP-12** WHEN a client lists alert rules THE SYSTEM SHALL return each with its upstream
  categories using a number of round trips that does NOT grow with the number of rules. `[N+1]`

### Service nodes and their dependencies

- **REQ-CP-13** THE SYSTEM SHALL let a client create, read, list (optionally by tenant), update and
  delete service nodes, each declaring the other nodes of the same tenant it depends on.
- **REQ-CP-14** WHEN a declared dependency does not exist, or belongs to another tenant, THE SYSTEM
  SHALL answer "unprocessable"; WHEN the declared dependencies would form a cycle (including a node
  depending on itself) THE SYSTEM SHALL answer "unprocessable" and store nothing.
- **REQ-CP-15** WHEN a client deletes a node that another node depends on THE SYSTEM SHALL answer
  "conflict" and change nothing.

### Change announcements (transactional outbox)

- **REQ-CP-16** WHEN a tenant, title, alert rule or service node is created, updated or deleted THE
  SYSTEM SHALL record exactly one change announcement in the SAME atomic unit of work as the change.
- **REQ-CP-17** WHEN that unit of work is abandoned for any reason THE SYSTEM SHALL leave no
  announcement behind; WHEN it commits THE SYSTEM SHALL leave exactly one. `[outbox]`
- **REQ-CP-18** THE SYSTEM SHALL write a create or update announcement as a FULL snapshot of the
  entity, never a delta, because the stream it feeds forgets history. `[outbox]`
- **REQ-CP-19** WHEN an entity is deleted THE SYSTEM SHALL record an announcement with NO payload (a
  tombstone), which is how the stream forgets the key. `[outbox]`
- **REQ-CP-20** THE SYSTEM SHALL identify each announcement by a key that is unique across entity
  kinds and tenants, so that all changes to one entity are announced in the order they were made.
  An alert rule's key is its bare rule id, because its consumer names a rule by the key and removes
  it by the tombstone's key; the other kinds are prefixed, so no collision is possible.
- **REQ-CP-20a** THE SYSTEM SHALL write an alert rule's snapshot in the shape its consumer parses
  (rule id, tenant, metric, comparator, threshold, severity, category, upstream categories, and a
  condition kind that is never the entity kind).
- **REQ-CP-21** THE SYSTEM SHALL encode each announcement as the contract-defined change record
  (`ConfigChange`), with the announcement's own id as the change id, the entity's version, and a
  timestamp from the injected clock.
- **REQ-CP-22** THE SYSTEM SHALL refuse to record an announcement outside an atomic unit of work.
- **REQ-CP-23** WHERE the change-capture relay is deployed THE SYSTEM SHALL deliver every recorded
  announcement to the configuration-changes stream at least once, in per-key order, as the raw bytes
  of the contract record (and an empty value for a tombstone), with no change to the request path.
  WHILE the relay is down THE SYSTEM SHALL keep accepting changes; announcements accumulate and are
  delivered, in order, when the relay returns. `[CDC]`

### Billing usage

- **REQ-CP-24** THE SYSTEM SHALL NOT invent usage figures. WHEN a client asks for billing usage THE
  SYSTEM SHALL answer "not implemented" with a problem-detail that says usage is deferred. The OpenAPI
  contract says the same. `[honesty]`

### Errors and operations

- **REQ-CP-25** THE SYSTEM SHALL report every error as a problem-detail document; a validation failure
  SHALL name each offending field.
- **REQ-CP-26** THE SYSTEM SHALL expose liveness that depends on no data store, and readiness that
  reflects only whether the process can take traffic.
- **REQ-CP-27** THE SYSTEM SHALL carry executable demonstrations, observed and not assumed, that (a)
  a call from one method of an object to another of its own methods does not start a unit of work, (b)
  a checked failure does not abandon a unit of work by default, and (c) a nested unit of work that
  suspends the outer one commits independently of it. `[tx-lab]`

## Non-functional

- Pool sizing and timeouts are **targets** until Phase 9 measures them.
- The read path of the lab endpoints (tree, rule list) must stay flat in round trips as data grows;
  the number is asserted, not eyeballed.

## Honest gaps (stated, not hidden)

- **No authentication or authorization.** The contract declares a bearer scheme; nothing enforces it
  here, as in the query API. `changedBy` is therefore always empty.
- **Billing usage is deferred** (REQ-CP-24). No usage table exists in this repo; reading the sink
  writer's table from here would couple two services through a schema one of them owns.
- **Service nodes have no entity type in the change-record contract** (`EntityType` has TENANT, TITLE,
  ALERT_RULE, PARTNER_SUBSCRIPTION). They are announced as `ENTITY_UNKNOWN` with the real kind in the
  snapshot, until the contract gains a symbol. The contract module is not part of this change.
- **No outbox retention.** Rows are kept after they are relayed; a purge job is not built.
- **Lists are not paginated.**
- **Alert rules author threshold conditions only.** The alert engine also parses RATE_OF_CHANGE and
  ABSENCE conditions and optional name, titleId and region; this API has no fields for them.
- **Apps are not announced** (the contract has no entity type for them and the brief lists none).

## Out of scope

- Reading change announcements (the consumers are other phases).
- The alert engine's use of the rule graph.
