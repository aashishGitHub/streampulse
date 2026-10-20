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

Spend about a minute on each step and stop wherever the interviewer pulls. Every step is written as the sentences you would say, followed by the file to open.

**1. Scope and numbers.** I start with numbers, and I say they are assumptions: ten million viewers each sending one heartbeat every ten seconds is about one million events a second at a live peak. A dashboard does not read raw events, though, it reads viewers and error rate per title per region per ten seconds, which is about two thousand records a second, so aggregation is a reduction of roughly five hundred times. That is why I say the sink is cheap and the front door is expensive. **Code:** `docs/script.md:46-64` (the capacity section).

**2. The front door.** The gateway authenticates the caller, takes the tenant from the token and never from the body, applies a per-tenant quota, claims event ids for idempotency, and then publishes to Kafka and waits for the broker's acknowledgement. I make a point of the two refusal codes: 429 means you are over your quota, and 503 means we are out of capacity, because the right client behaviour differs. The quota check runs first so that a throttled tenant costs one Redis call and not a permit.

**Example:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:103-111`
```java
// ---- 0. QUOTA, before anything expensive ----
// Checked first, deliberately. A tenant over its limit should cost us a single
// Redis round trip, not a permit, a dedup claim and a publish. Admission control
// that runs late is admission control that does not protect you.
if (!rateLimiter.tryConsume(command.tenantId(), command.size())) {
    metrics.recordRejectedForQuota(command.size());
    throw new QuotaExceededException(
            "tenant " + command.tenantId() + " exceeded its ingest quota", RETRY_AFTER_QUOTA);
}
```

**Code:** `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java:101-135` (the whole flow), `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md:1` (the resilience decision).

**3. Ordering and keys.** Raw events are keyed by session id so that one session's events stay in order on one partition. Aggregation needs a different key, tenant and title and region, so there is one repartition, and I say what that costs: an extra topic to write and read, and, as I found later, a way for events to reach the window out of order. **Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:105-108` (the re-key and the grouping).

**4. Delivery semantics.** I say that exactly-once is a statement about Kafka to Kafka and no further. The sink writes to Postgres with an idempotent upsert on a primary key and acknowledges the offset only after the write is durable, so a replay is harmless. **Code:** `services/sink-writer/src/main/resources/schema.sql:4-14` (the table and its key), `docs/adr/0013-sink-durable-then-commit.md:1`.

**5. Event time.** Windows are ten seconds with five seconds of grace, and a final record is emitted only when a window has closed, so freshness cannot be better than window plus grace. The cost of that choice is that a quiet partition holds its last window open. **Code:** `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java:109-121`.

**6. The hot path.** The live feed is Server-Sent Events: the server subscribes to the live feed first, then replays what the client missed after its `Last-Event-ID`, then continues live, dropping entries it has already sent. **Code:** `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java:1`.

**7. Config and alerts.** A change to a rule is written to Postgres together with an outbox row in one transaction, Debezium relays the row to a compacted Kafka topic, and the alert engine rebuilds its rule book from that topic. The engine rejects dependency cycles when a rule is saved and suppresses downstream alerts while a root cause is firing. **Code:** `services/control-plane/src/main/java/com/streampulse/controlplane/adapter/out/outbox/JdbcConfigChangeOutbox.java:1`, `services/alert-engine/src/main/java/com/streampulse/alert/domain/AlertEngine.java:1`.

**8. How I know it works.** I test at four levels and I enforce the architecture with ArchUnit rules. What I am proudest of is mutation checking: I break the code on purpose and confirm that the right test goes red, because a passing suite only proves the code passes the tests. **Code:** `services/control-plane/src/test/java/com/streampulse/controlplane/ControlPlaneArchitectureRulesTest.java:1`.

**9. What I measured, and what broke.** I measured the ingest ceiling, end-to-end accounting, latency and freshness, and I killed the stream processor to see what happened. The stories are in `05-what-broke.md` and every number is in `06-numbers.md` with its conditions: one laptop, one broker, one run each.

**10. What is not done.** I say it plainly: there are no Kubernetes manifests, the observability rules were written but never run, the crash bug is unfixed, there is one broker, and the continuous integration build on GitHub currently fails because of a platform-dependent test. Saying this myself is better than letting the interviewer find it.

## What exists, honestly

| Phase | Status | The honest note |
|---|---|---|
| 0 to 5 foundation, contracts, ingest, stream processing, sink, query | done | one broker, replication factor 1; Phase 3 measured on one laptop; **GitHub Actions CI has failed on every run** (one test assumes macOS thread names; not yet fixed) |
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
