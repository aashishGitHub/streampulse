# The story: say it in 2 minutes, then in 10

Everything here is checkable in the repo. If you cannot point at a file for a claim, do not say it.

## The 2-minute version

> "StreamPulse is a real-time telemetry platform I built in Java 21 and Spring Boot to learn the
> hard parts of streaming systems by making each one concrete. Devices post heartbeat and error
> events to an ingest gateway. It authenticates the caller with an OIDC token, enforces a
> per-tenant quota, deduplicates by event id, and publishes to Kafka. A Kafka Streams job windows
> the events by event time into 10-second aggregates, with a 5-second grace period, and emits one
> final record per window. A sink writes those to Postgres and Redis, and a query API serves them,
> including a live Server-Sent-Events feed. Beside that there is a control plane, which uses a
> transactional outbox and Debezium to publish config changes, and an alert engine that evaluates
> rules over the aggregates and suppresses downstream alerts when a root cause is firing.
>
> What I am proudest of is not the features but the verification. I measured it: ingest ran clean
> at 40,000 events a second on my laptop, ingest on its own with the per-tenant quota lifted, and
> started shedding load with 503s at 45,000. One machine, one run. I wrote an
> end-to-end accounting check that compares every closed window with what was sent. And when I
> killed the stream processor mid-run, that check found a real bug: on-time events were being
> dropped after the restart. I have it written up with the cause and the fix options, and I have
> not fixed it yet, because every fix changes a guarantee I documented."

Why this shape works: one sentence of what it is, one of the flow, then the part that shows judgement
(measuring, testing the test, owning an open defect).

## The picture

```
 device ──HTTP/gRPC──▶ ingest-gateway ──▶ Kafka telemetry.raw.v1 (12 partitions, key = session)
   (OIDC token)        quota 429, backpressure 503,            │
                       Redis idempotency, breaker+retry        ▼
                                                      stream-processor (Kafka Streams, exactly_once_v2)
                                                      decode → DLT │ dedup │ re-key │ 10 s window, 5 s grace
                                                                   │ suppress until window closes
                                                                   ▼
                                                      Kafka telemetry.agg.v1 (6 partitions)
                                                         │                      │
                                                         ▼                      ▼
                                                   sink-writer             alert-engine ──▶ alerts.v1, SQS, SSE
                                                   batch → Postgres upsert        ▲
                                                   then Redis, then ack           │ rules (compacted topic)
                                                         │                  config.changes.v1
                                                         ▼                        ▲
                                                   query-api (WebFlux)       Debezium ◀── outbox ◀── control-plane
                                                   overview (300 ms budget)        (JPA + Flyway, one transaction)
                                                   SSE live feed
 React dashboard ◀──────────── query-api, alert-engine, control-plane
```

## The 10-minute version, in order

Spend about a minute each. Stop wherever the interviewer pulls.

1. **Scope and numbers** (labelled assumptions). 10 M viewers, one heartbeat per 10 s each, so about
   1 M events/s at a live peak. A dashboard does not read raw events; it reads "viewers and error
   rate per title per region per 10 s", about 2,000 records a second, a roughly 500x reduction.
   So **the sink is cheap and the front door is expensive.** (`docs/script.md`)
2. **The front door.** Authenticate, authorise the tenant from the token (never from the body),
   quota, idempotency, publish. 429 means "you are over your quota"; 503 means "we are out of
   capacity". Different causes, different client behaviour. (ADR-0006, 0007, 0009)
3. **Ordering and keys.** Raw events keyed by session so a session stays ordered; aggregation needs
   a different key, so there is one repartition. Say what that costs. (ADR-0012)
4. **Delivery semantics.** Exactly-once is a Kafka-to-Kafka statement. The sink to Postgres is
   at-least-once made safe by an idempotent upsert and ack-after-write. (ADR-0012, 0013)
5. **Event time.** Windows, grace, suppress, and the freshness floor of window plus grace. A quiet
   stream holds its last window open. (ADR-0012)
6. **The hot path.** SSE with `Last-Event-ID` replay, then live, over Redis Streams. (ADR-0014)
7. **Config and alerts.** Transactional outbox → Debezium → compacted topic → alert engine. Rule
   dependency graph, cycle rejection at save time, root-cause suppression. (ADR-0015, 0016, 0017)
8. **How I know it works.** Tests at four levels, ArchUnit for architecture, and **mutation checks**:
   break the code on purpose and confirm the right test goes red.
9. **What I measured, and what broke.** Saturation, end-to-end accounting, the crash test.
   (`05-what-broke.md`, `06-numbers.md`)
10. **What is not done.** Say it plainly: Kubernetes manifests, observability never run live, the
    crash bug unfixed, one broker. (See below.)

## What exists, honestly

| Phase | Status | The honest note |
|---|---|---|
| 0 to 5 foundation, contracts, ingest, stream processing, sink, query | done | one broker, replication factor 1; Phase 3 measured on one laptop |
| 6 control plane, CDC, alert engine | core complete | alert state is lost on restart; no authentication on either service |
| 7 React dashboard | core complete | every test stubs the network; never run against real backends; Keycloak sign-in never run live |
| 8 observability | partly built, **never run** | Prometheus rules, Grafana dashboards, OTel config exist; `promtool` tests not in CI |
| 9 resilience, load, profiling | in progress | ingest saturation, accounting and latency measured; crash test **fails**; JVM profiling not done |
| 10 AWS and Terraform | core complete | emulators only (moto); `msk`, `vpc`, `eks` modules only validated |
| 11 containers and Kubernetes | partial | a Dockerfile exists; no Kubernetes manifests |
| 12 activity feed | core complete | no real producer of the watch signal |
| 13 MCP server | core complete | read-only tools; no LLM summariser |
| 14 interview packaging | this pack | |

Local AWS simulation proves API-level behaviour only (ADR-0003). It does not prove IAM, throttling,
latency, or real service limits.

## Questions that open the conversation

- "Walk me through the architecture." → the picture above, then ask which part to go deeper on.
- "What was the hardest problem?" → `05-what-broke.md`, story 3 (or 1 if they want something fixed).
- "What would you do differently?" → the crash bug's keying; and writing the accounting check
  *before* the processor, not after.
- "Is this production-ready?" → no, and here is the list. That answer is a strength when it is specific.
