# StreamPulse — Spoken Answer Script (SD1)

> Updated 2026-10-19 to match the code and MILESTONES.md. The per-phase interview pack is in docs/interview/.

**Prompt:** "Design real-time telemetry collection and monitoring for Disney+, Hulu and ESPN+."

**Target: 15–20 minutes. ~150 words/minute. Pause at every `---`.**
Timings are cumulative. Say the words in **bold** — they are the terms the interviewer is listening for.

How to read the markers:

- A figure shown as **(not measured: ...)** was never measured. **Do not say a number for it.** Say "I did not measure that; here is how I would." Figures without that tag come from the runs recorded in MILESTONES.md (Phase 3 Day 5, and Phase 9 T9-11, T9-12, T9-13, T9-34): one laptop, one broker, one run each, not production.
- **assumption** — interview-prompt arithmetic (from the Question bank), not a measurement. Say "assume".
- **Honest note** — a place where the code I wrote differs from the idealised answer. Say it out loud. It is a strength.
- `Code:` lines are for you, not for saying. Every claim points at a file you can open.

**Time-boxing.** Paragraphs that start with **[DEPTH]** are not on the 20-minute clock. They are the answers you reach for when the interviewer pushes, or when you have time left. Say everything that is not marked [DEPTH] and you land at about 20 minutes. Every honest note below is a [DEPTH] paragraph unless it says otherwise, but the **closing section (19:15) repeats the important ones**, so the interviewer hears them either way.

The six beats, used on every decision: **clarify → assumption with a number → pick → name the cost → name the failure mode and what the user sees → say how I'd measure it.**
Phrase to reuse: "I'd pick X because Y. The cost is Z. I'd watch metric M, and if it crosses N I'd do W."

---

## 0:00 — 1:30 | Scope and clarifying questions

"Before I draw anything, four questions. Each one changes the design.

**One. Which events?** Heartbeat, session start and end, buffering, bitrate change, error. I'll treat heartbeat and error as the core, and make sure an event type I don't know about never breaks the pipeline.

**Two. Which producers?** Web, mobile, smart TVs. Smart TVs matter because they are battery- and handshake-sensitive, so I'd offer a second protocol, gRPC streaming, next to REST. Same use case behind both.

**Three. How fresh must the dashboard be?** I'll assume **under 30 seconds** from event time to a number on screen. That target drives the window size, so I want it agreed up front.

**Four. Which events may be lost?** Heartbeats, yes, a few. Billing, never. So this is a **telemetry** system. I would rather accept a duplicate than lose an event, and I'll say where.

One principle before the boxes: **telemetry must never break playback.** Every failure mode I show you later ends in that sentence.

Out of scope for the hour: a raw-event data lake, alert routing, multi-region. An alert engine and an archive compactor exist, and multi-region is a design document only. I'll mention each at the end."

> Beats: clarify (all four), assumption (30 s), pick (telemetry, not billing), failure (never break playback).

**If they push:** "Why 30 seconds?" — "It is an assumption I'd confirm. My pipeline has a hard floor of 15 seconds from window start, so 30 leaves 15 for everything else. If they said 5 seconds, I'd change the window and the grace period, and I'd tell them what late data that costs."

Code: event types in `libs/contracts/src/main/avro/TelemetryEvent.avsc`; unknown type counted, not an error — `TelemetryTopologyTest.unknownTypeCountsTowardTotalsOnly`.

---

## 1:30 — 3:00 | Capacity numbers (assumptions, labelled)

"Numbers before boxes. These are **assumptions** from the prompt, not measurements.

Assume **10 million concurrent viewers**, each sending **one heartbeat every 10 seconds**. That is **1 million events per second** at a live-sports peak. At about **1 kilobyte** an event, that is about **1 gigabyte per second** before compression. The live spike drives the design, not the average.

Second number. Raw events are not what a dashboard reads. A dashboard reads 'viewers and error rate, per title, per region, per 10 seconds'. Assume **1,000 titles in 10 regions**. That is 10,000 series, two metrics each, so **20,000 aggregate records per 10-second window — about 2,000 records a second**. So the aggregation step is a **roughly 500-times reduction**. That is why the database behind it can be boring.

**[DEPTH]** Third number, and it is a warning. If I keep a dedup state for event ids with a 30-second retention, at 1 million events a second that is about **30 million ids** in state. I'll come back to that at 10x.

Why do I quote these? Because the **sink is cheap and the front door is expensive**. Everything I spend on correctness goes at the front door and the stream processor."

> Beats: assume (1M/s, 1 GB/s, 2k/s), pick (aggregate before storing), cost (state size), measure (see numbers table).

**If they push:** "Is 1 GB/s real?" — "No. It is the prompt's arithmetic, and it is before compression. I turned lz4 on. I did not measure the ratio on real payloads: (not measured: lz4 compression ratio on realistic event payloads). I'd size the partition count from a load test, not from a number I half-remember."

Code: `services/ingest-gateway/src/main/resources/application.yml` (`compression.type: lz4`, `linger.ms: 20`, `batch.size: 65536`); window and grace in `services/stream-processor/src/main/resources/application.yml`.

---

## 3:00 — 4:15 | API

"One endpoint on the hot path: **`POST /v1/events:batch`**.

**Batched**, up to 500 events, because one request per event spends more on TLS and headers than on payload. The batch is **atomic**: all accepted or none. Partial success forces every client to write per-event retry bookkeeping, and most get it wrong.

**`202 Accepted`**, not 200 and not 201. The events are durable in Kafka but not processed yet. I only return 202 **after the broker acknowledges**. A 202 before the ack is a lie the client acts on by deleting its copy.

**Idempotent by `eventId`.** The client generates a UUID per event. A retry after a lost response is recognised and counted as a duplicate. Still 202, so the client does not retry again.

**Two kinds of 'slow down'**, and I keep them apart. **`429` plus `Retry-After`** means *your* tenant exceeded its quota — your problem. **`503` plus `Retry-After`** means *we* are saturated or the broker path is broken — our problem, another instance may cope. Clients **must add jitter** to `Retry-After`, or every throttled client comes back in the same instant and the second spike is worse than the first.

**[DEPTH] Honest note one.** The Question bank's idealised answer has 429 for all backpressure. In my code, saturation is 503 and 429 is only the per-tenant quota. The OpenAPI file now says the same: 429 is tenant quota, 503 is saturation or an unavailable stream.

**[DEPTH] Honest note two.** The idealised answer says client-side gzip. I did not implement or test request decompression. I'd say 'the client should gzip, and I'd verify the server handles `Content-Encoding` before I promised it'.

Identity: the tenant comes from a verified **JWT claim**, never from the body. The request has no tenant field at all, so there is nothing to spoof."

> Beats: pick (batch, 202 after ack, 429 vs 503), cost (client complexity: jitter), failure (client sees 503 + Retry-After and keeps its copy), measure (ingest 5xx ratio and 429 rate).

**If they push:** "Why not partial success?" — "Because a mixed response makes every client track which of 500 events failed. Atomic plus idempotent is simpler: resend the whole batch, duplicates are absorbed."

Code: `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestController.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java` (429 quota, 503 saturation, 503 breaker), `api/openapi/ingest-v1.yaml`. Tests: `IngestServiceTest` nested `Quota` and `Backpressure`; `IngestEndToEndIT.bodyCannotOverrideTheTenant`.

---

## 4:15 — 6:30 | The pipeline

"Here is the whole path. I'll walk it left to right.

**One. Ingest gateway.** Stateless. Spring MVC on **virtual threads**. Order of work: check the tenant's **token bucket** first, so an over-quota tenant costs one Redis call, not a permit and a publish. Then take a permit from a **bounded semaphore**, 64 in flight, wait at most 250 milliseconds, else 503. Then **claim the event ids** in Redis with `SET NX EX`. Then **publish** and wait for the ack.

**Two. Kafka.** Topic `telemetry.raw.v1`, **12 partitions, keyed by `sessionId`**. The record timestamp is the event's `occurredAt`, not the broker's append time, so the stream processor gets **event time** for free.

**Three. Stream processor — Kafka Streams.** Decode and validate; bad bytes go to a **dead-letter topic** unchanged. Dedup by `eventId`. Re-key to tenant, title, region. **10-second tumbling windows**, **5-second grace**, suppress until the window closes. Output: one final record per metric per window on `telemetry.agg.v1`, 6 partitions.

**Four. Sink writer.** Reads the aggregate topic, writes **Postgres first** — that is the durable store — then **Redis**, then acknowledges the Kafka offset. Redis holds the latest value per series and a capped stream for the live feed.

**Five. Query API.** **WebFlux**. A title overview that fans out to three dependencies under one 300-millisecond budget, a history endpoint, and an **SSE** stream for live dashboards.

**[DEPTH] Honest note.** The idealised answer says Flink or Kinesis, hot store in Redis or OpenSearch, raw events to Firehose and S3. I built Kafka plus Kafka Streams because it runs free and locally and I can test the topology in memory. I did **not** build OpenSearch. An S3 bucket in Terraform against a local AWS simulator and an archive compactor exist, but no service writes raw events to the archive. And **Postgres is the source of truth; Redis is a rebuildable projection.** I'll defend that in the delivery section.

**[DEPTH]** Also: **single Kafka broker, replication factor 1**. `acks=all` is configured, but with one broker it cannot show you what happens when a replica is lost. I won't claim it does.

Also: **MVC with virtual threads in ingest, WebFlux only in the query API.** Different workloads, different answers. I'll explain in the SSE section."

> Beats: pick (Kafka + Streams, Postgres durable), cost (no raw archive, single broker), failure (each hop's failure is in the failure section), measure (lag, freshness, 5xx).

**If they push:** "Why not Kinesis plus Flink, like Disney?" — "I do not have a verified source for what their stack is, so I won't claim it. I picked Kafka Streams because I could test it with no cloud account. I kept a Kinesis publisher behind the same interface as a comparison, so the choice is one bean."

Code: `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java`, `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`, `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java`, `services/sink-writer/src/main/java/com/streampulse/sink/application/AggregateSink.java`, `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java`. Decisions: `docs/adr/0006-virtual-threads-over-webflux.md`, `docs/adr/0012-kafka-streams-primary.md`, `docs/adr/0013-sink-durable-then-commit.md`, `docs/adr/0014-webflux-sse-redis-streams.md`. Topics: `docker/compose.yml` (lines creating `telemetry.raw.v1` with 12 partitions and `telemetry.agg.v1` with 6).

---

## 6:30 — 7:30 | Per-session ordering (M3)

"Ordering. **Key by `sessionId`.** Order holds only **within a partition**, so one viewing session lands on one partition and `session_start → heartbeat → session_end` stays in order. The producer has **idempotence on**, so a retry cannot reorder or duplicate a record on the wire. Global order I never need, and I'd say so.

**The cost:** my aggregation is per tenant, title and region, not per session. So I **re-key** after dedup, and Kafka Streams writes a **repartition topic**. That is network and disk. I paid it knowingly. The alternative, keying by title, makes one hot title a hot partition from the first byte.

**The failure mode:** if someone adds partitions later, `hash(key) mod partitions` changes and per-session order breaks at the boundary. So I picked 12 up front, higher than I need, and I'd treat changing it as a breaking change.

**[DEPTH] Honest note.** The two metrics I compute, distinct sessions and error rate, **do not depend on order**. They depend on event time. Per-session order would matter for session-level metrics like rebuffer ratio or startup time, which I defined in the schema but did not build. I'd say that rather than pretend order is doing work here.

**[DEPTH] Another honest note.** My aggregation key is `tenant:title:region` with **no salting**. One hot live match in one region is one key, so one stream task. The Question bank says salt the key for hot partitions. I did not. I'll come back to it at 10x."

> Beats: pick (sessionId), cost (repartition), failure (adding partitions; hot key), measure (per-partition lag — one partition lagging means a hot key).

**If they push:** "Does your dedup survive the repartition?" — "I put the dedup **before** the repartition, while the key is still the session. A retried event carries the same session, so the duplicate is always on the same partition as the original, and a store local to that task sees both."

Code: `services/ingest-gateway/src/main/java/com/streampulse/ingest/domain/IngestCommand.java` (`partitionKey()`), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java` (keys and repartition), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/EventIdDeduplicator.java`. Tests: `KafkaIngestIT.sameSessionSamePartition`, `IngestServiceTest` nested `HappyPath` (`partitionKeyIsSessionId`), `TelemetryTopologyTest.duplicateEventIdCountedOnce`.

---

## 7:30 — 10:00 | Delivery semantics: no loss, no double count (M2)

"The question is 'how do you guarantee no loss and no double counting'. I'll say it in one line, then show where each half lives.

**'Effectively-once equals at-least-once plus idempotent processing.'** I never say 'exactly-once end to end'. I say where.

Walk the path:

**Client to gateway.** The client retries until it sees a 202. Retries are safe because of the **`eventId`** claim in Redis: `SET NX EX`, atomic. Check-then-set has a race, `SET NX` does not.

**Gateway to Kafka.** **Idempotent producer plus `acks=all`.** The broker keys on producer id and sequence number, so a producer-side retry cannot duplicate or reorder.

**[DEPTH] One bug the tests found, and one decision.** The bug: I claim the ids in Redis *before* publishing. If the publish then failed, the claims stayed, the client's retry saw every id as a duplicate, got 202 with nothing published, and dropped its copy. Silent data loss reported as success. The fix is a compensating `release()` on failure, and a test that reproduces the old behaviour so nobody deletes it as dead code. The decision: if Redis is down, I **fail open** — publish anyway, count it, and flag `degraded: true`. A duplicate is a wrong number I can reconcile. A gap is gone forever.

**Inside Kafka.** The stream processor runs **`exactly_once_v2`**. Output records, state-store changelog and input offsets commit in **one transaction**. That holds **only Kafka to Kafka**. The moment data leaves Kafka, it does not. And I killed the processor once: no duplicates, but on-time events were dropped. I tell that story in the failure section.

**Out of Kafka, into the database.** The sink gives me at-least-once, so correctness has to come from the **write**. Three rules. One: **commit offsets only after the durable write** — auto-commit is off, acknowledgement is manual, and auto-commit is the classic trap because it commits what `poll()` returned, not what you processed. Two: the Postgres write is an **idempotent upsert** on the aggregate's identity — tenant, title, region, metric, window start. Three: the upsert only replaces a row whose **`computed_at` is older or equal**, so a replay or a reordering cannot move a value backwards.

**Proof.** One integration test makes the code throw **after the durable write and before the ack**, three times. The container redelivers. Then the test counts: same number of rows, same values, committed offset exactly N.

**[DEPTH] What that test does not prove.** The failure is an exception, not a killed JVM, and not a broker or Postgres dying mid-write. The upsert is what makes a real crash safe, and the test only exercises it through redelivery.

**[DEPTH] Why Redis is not the source of truth.** Redis is written **after** Postgres. If Redis fails, I log, count and carry on, because everything in Redis can be rebuilt from Postgres. Writing Redis first would let a reader see a value Postgres never stored. **Honest note:** nothing rebuilds Redis yet. It is a stated gap, not a built job. And the live feed may repeat a record after a redelivery; Postgres never does.

**[DEPTH] Cost of all this:** at-least-once means the Redis live feed can show a duplicate, and the Redis latest hash can briefly show an older window after a redelivery across partitions. Postgres is correct."

> Beats: pick (idempotent sink, commit after write), cost (duplicate in live feed; fail-open inflates numbers during a Redis outage), failure (crash between write and ack → redelivery, absorbed), measure (rows vs distinct windows; DLQ rate; `streampulse.ingest.idempotency.unavailable` counter).

**If they push:** "Why not Kafka transactions into Postgres?" — "The database is not a Kafka resource. The alternatives are an outbox or two-phase commit, which add machinery this write doesn't need. An idempotent upsert gets the same outcome. Where I do have a real dual write, in the control plane, I built the outbox with Debezium."

**If they push:** "Why does a poison record not stop the pipeline, but an outage does?" — "They are different failures. A poison record fails the same way every time, so I dead-letter the original bytes and carry on. An outage fails *every* record, so I retry forever with backoff and never dead-letter. If I dead-lettered healthy records because Postgres was briefly down, I'd lose data that was never bad. To tell them apart: when a batch keeps failing, write the records one at a time. If some succeed, the failures are poison. If none succeed, the store is down."

Code: `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/kafka/KafkaEventPublisher.java`, `services/ingest-gateway/src/main/resources/application.yml` (producer block), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/in/kafka/AggregateBatchListener.java`, `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/postgres/JdbcMetricWindowStore.java`, `services/sink-writer/src/main/java/com/streampulse/sink/application/AggregateSink.java`. Tests: `NoLossNoDoubleCountIT.failureBetweenWriteAndAckLosesNothingAndDoubleCountsNothing`, `MetricWindowUpsertIT` (`sameRecordTwiceGivesOneRow`, `olderComputedAtDoesNotOverwriteNewer`), `IngestServiceTest` nested `PublishFailures` (`aFailedPublishCanBeRetried`, `withoutTheReleaseTheRetryIsSwallowed`), `KafkaIngestIT.producerDeliveryGuarantees` (asserts configuration, not behaviour under failure). ADR: `docs/adr/0007-fail-open-on-idempotency-store-failure.md`, `docs/adr/0012-kafka-streams-primary.md`, `docs/adr/0013-sink-durable-then-commit.md`.

---

## 10:00 — 12:00 | Windows, late data, grace, suppression, and the freshness cost

"Windows. **Event time, not processing time.** A phone that was offline for a minute and reconnects sends events with old timestamps. If I windowed by arrival, those would land in the wrong window. The record timestamp is the event's `occurredAt`, so Kafka Streams windows on event time without a custom extractor.

**Tumbling windows, 10 seconds**, per tenant, title and region. **Grace period 5 seconds**: an event that arrives after its window but inside grace is still counted in its own window. After grace, it is **dropped and counted** in a metric — never silently.

**Suppression.** Without it, every late event re-emits the window and a downstream consumer sees a stream of revisions. With `suppress(untilWindowCloses)`, I emit **one final record per metric per window**, after the window and its grace have closed.

**Here is the cost, and I'd say it as a deliberate trade.** A final aggregate cannot appear sooner than **window plus grace** — 10 plus 5 — **15 seconds after the window starts**. That is the **freshness price of correctness for late events.** I'm buying one answer per window and tolerance for 5 seconds of lateness, and I'm paying 15 seconds of floor. Against my 30-second target, that is fine. If they wanted 5 seconds, I'd emit early provisional values marked `isFinal=false` and let the sink overwrite them with the final one. The upsert is already built for that.

**The failure mode I want you to know about:** suppress emits when **stream time** advances, and stream time only advances when *later records arrive on that partition*. A **quiet partition holds its last window open indefinitely.** At 1 million events a second this does not happen. On a small or idle title it will. In my tests I pipe a 'tick' record to close the window, and one test asserts nothing is emitted without it. In production I'd want either steady traffic, or a synthetic heartbeat per partition, or I'd measure it.

**[DEPTH] A guard I added:** an event dated more than 5 minutes in the future is rejected to the dead-letter topic. A device with a wrong clock would otherwise push stream time past every real window and make them all 'late'.

**What I measure:** `streampulse.sink.freshness` — the time from window end to the durable Postgres write. **Honest note:** that metric starts at window **end**, not at event time. So true event-to-dashboard freshness is that number plus up to 10 seconds of window. Measured, one laptop, one run each: per window in Postgres, 5.2 to 5.4 s after window end at 1,500, 1,000 and 2,000 events a second, and grace is 5 s. The loadgen's own freshness histogram is wrong for back-to-back runs, so I quote Postgres per window.

**[DEPTH] Honest note on the metric itself:** distinct viewers is an **exact set of session ids**, not a HyperLogLog sketch. Each event copies the set, so cost per event is proportional to sessions in the window. It is correct, and it does not survive a million-viewer title. I'd name the approximate sketch as the fix."

> Beats: clarify (freshness target), pick (event time, 10 s, 5 s grace, suppress), cost (15 s floor), failure (quiet partition stalls), measure (freshness p99; late-drop counter).

**If they push:** "Why 5 seconds of grace?" — "It is a target. And I have the cost of getting it wrong: 5 seconds was too small to absorb a 40 to 50 second restart backlog, which is the crash story later. The mechanism: grace trades freshness for tolerance of lateness. I'd look at Kafka Streams' dropped-records metric on real traffic and set grace to cover the p99 lateness I actually see."

**If they push:** "What if a duplicate arrives after the window closed?" — "Dropped by the window anyway. So the dedup store only needs to remember an id for window plus grace. I give it twice that range, so it stays bounded."

Code: `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java`, `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/EventIdDeduplicator.java`, `services/stream-processor/src/main/java/com/streampulse/processor/domain/EventValidator.java`, `services/stream-processor/src/main/java/com/streampulse/processor/domain/WindowAccumulator.java`. Tests in `services/stream-processor/src/test/java/com/streampulse/processor/adapter/streams/TelemetryTopologyTest.java`: `noOutputBeforeWindowCloses`, `emitsOneFinalAggregatePerMetricAfterGrace`, `outOfOrderWithinGraceIsCounted`, `lateAfterGraceIsDroppedNotEmitted`, `futureEventIsRejected`. ADR: `docs/adr/0012-kafka-streams-primary.md`.

---

## 12:00 — 13:15 | The hot path: SSE to live dashboards (SD3)

"Pushing to thousands of ops dashboards. **SSE over WebSocket.** One-way, plain HTTP, automatic reconnect, and a built-in resume: the browser sends **`Last-Event-ID`** on reconnect. A WebSocket buys me two-way I do not need and costs me a custom protocol and reconnect logic.

How it works in mine:

**One shared reader per instance.** A single Redis Stream reader fans out to every connected client through a hot multicast sink. Cost scales with **instances**, not with **clients**.

**The SSE event id is the Redis stream entry id.** It is totally ordered, so it doubles as the resume point. No second sequence number to keep.

**Replay then live, with no gap and no duplicate.** On reconnect I subscribe to the live feed **first**, holding entries in a buffer, then read the missed range from the stream, then drop any live entry at or below the last replayed id. Subscribe-before-read gives no gap. The id filter gives no duplicate. And entry ids compare **numerically**, because as strings `9-0` sorts after `10-0`.

**Coalescing.** Every 250 milliseconds each client gets the **latest value per series**, not a queue. A slow client costs me one map entry per series, not a growing backlog. The cost: intermediate values are dropped by design. A consumer that needs every window uses the history endpoint.

**Heartbeat.** An SSE comment every 15 seconds on an idle stream, so proxies and load balancers do not close it. I'd check the load balancer's idle timeout against that 15 seconds. I don't have a verified default to quote.

**Why WebFlux here and MVC with virtual threads in ingest.** Ingest is blocking I/O with no streaming requirement: virtual threads give me the scalability with ordinary code. The query API is long-lived streams with **demand control**. That is what Reactor was built for. Two models in one repo is a real cost, and I accepted it because the workloads differ.

**The WebFlux rule: never block an event-loop thread.** My JDBC reads and the catalog call run on **one bounded, named, rejecting executor**, and a test asserts no blocking port ever runs on a thread named `webflux-http-nio-N`.

**[DEPTH] Honest notes.** **No authentication on the query API.** The contract declares a bearer token and nothing validates it, and the tenant comes from the caller. Any client can read any tenant. I'd close that before any non-local use. **Replay is bounded.** The stream is trimmed to roughly 10,000 entries across all tenants, so a busy system shortens the resumable window and a longer outage gets a gap, not an error. **Replay is not coalesced**; only live is. **Every instance reads the whole stream** and discards other tenants' entries. Fine here; per-tenant streams are the next step.

Measured: (not measured: concurrent SSE streams per query-api instance and CPU/heap at that count)."

> Beats: pick (SSE + one shared reader + coalescing), cost (dropped intermediates; bounded replay), failure (client reconnects with Last-Event-ID; too-long outage gets a gap), measure (streams per instance; coalesce lag; reconnect rate).

**If they push:** "Why Redis Streams and not pub/sub or Kafka?" — "Pub/sub has no replay, so Last-Event-ID could not work. Kafka would give each query instance its own consumer group and rebalancing for a UI feed. A capped stream gives replay by id for cheap."

**If they push:** "What happens when Redis goes down?" — "The feed retries with backoff from its last cursor. The overview endpoint marks that part degraded and still answers. Probes stay up. **Honest note:** the retry loop was observed by hand only; there is no dedicated automated test."

Code: `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/SseStreamController.java`, `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Coalescer.java`, `services/query-api/src/main/java/com/streampulse/query/adapter/in/sse/Heartbeat.java`, `services/query-api/src/main/java/com/streampulse/query/adapter/out/redis/RedisLiveFeed.java`, `services/query-api/src/main/java/com/streampulse/query/domain/EntryId.java`. Tests: `SseStreamControllerTest` (`resumeReplaysThenGoesLiveWithoutDuplicates`, `entryDuringReplayIsNotLost`, `replayIsTenantFiltered`), `SseResumeIT.resumeThenLive`, `CoalescerTest` (`burstCollapsesToLastValuePerSeries`, `slowConsumerGetsLatestNotBacklog`), `HeartbeatTest.idleStreamGetsAComment`, `EntryIdTest.comparesNumerically`, `BlockingIsolationTest.noBlockingPortRunsOnAnEventLoopThread`. ADR: `docs/adr/0014-webflux-sse-redis-streams.md`.

---

## 13:15 — 15:00 | Two pieces of code I wrote: the batch writer (J5) and the 300 ms fan-out (J6)

"Two interview-shaped problems that are real parts of my system. I'll tell them as 'here is the code I wrote'.

### The batch writer

The sink must write 'in batches' — one row at a time cannot keep up, and a batch can fail half-way. So: a **bounded queue**, **one flusher thread**, flush at **200 records or 100 milliseconds after the first**, whichever comes first.

Backpressure: `submit` does an **`offer` with a timeout** — one second. A full buffer fails the submission explicitly; it never blocks forever and never drops silently. That propagates: slow Postgres fills the buffer, the listener stops polling, **consumer lag rises**. Lag is the honest signal.

Every submission returns a **future that completes only when its batch is durably written**. The Kafka listener waits for all futures of a poll, **then** acknowledges. And the flusher never touches the Kafka consumer, because the consumer is not thread-safe. The flusher completes futures; the listener thread acks.

Failure: retry the batch three times with backoff. Still failing: write **one at a time**. Some succeed — the rest are **poison**, dead-lettered. None succeed — **outage**, futures fail, nothing dead-lettered, Kafka redelivers with exponential backoff that **never gives up**.

Shutdown: **stop the Kafka listener first, then close the writer.** A `SmartLifecycle` bean at a phase below the listener container does it. Close stops intake, writes everything accepted, then returns within a bounded time.

**The race I want to tell you about.** Without care, a `submit` can pass the 'is it open?' check, `close()` can set the flag, the flusher can see an empty queue and exit, and then the late record is enqueued — **accepted, and nothing will ever write it.** The fix is a **`ReentrantReadWriteLock`**: `submit` takes the **read** lock around check-and-enqueue; `close` takes the **write** lock to set the flag. Many submitters run at once; `close` waits for any in flight.

**[DEPTH]** Why not `synchronized`? Two reasons. It serialises every submitter on one monitor. And on Java 21 a virtual thread that blocks inside `synchronized` **pins its carrier thread**. **VERIFY:** a later JDK is reported to remove that pinning; check your version. **Honest note:** in this repo `submit` is called from Spring Kafka listener threads, which I believe are platform threads (the sink-writer does not enable virtual threads in its `application.yml`). So the lock choice is defensive for virtual threads, and the no-serialisation reason holds regardless.

**[DEPTH — but tell this one if you have 30 seconds] The honest finding about the test.** I wrote a randomised race test: 48 threads submitting while a closer thread closes at a random moment, repeated 100 times. In my mutation check, with the lock removed, **that test did not go red.** The deterministic test did. The deterministic one hands the writer a queue whose `offer` stalls, holds a submit between its open-check and its enqueue, calls `close()`, and asserts `close()` **waits**. **A randomised concurrency test that passes 100 times proves the interleaving was not hit, not that it cannot happen.** The window between the check and the enqueue is nanoseconds wide. To test a race, **force the interleaving** instead of hoping the scheduler finds it.

**[DEPTH] What is missing, and I'd say it before you do:** no per-record retry budget beyond three attempts and the listener's redelivery; the dead-letter topic being down fails only that record; metrics exist for queue depth and flush latency, and I have not tuned any of the numbers. Measured under load: (not measured: batch flush latency p99 and peak queue depth under the load run)."

### The 300-millisecond fan-out

"The title overview needs three unrelated facts at once: **latest metrics** from Redis, **recent history** from Postgres, and **title metadata** from a catalog. One request, **one 300 millisecond budget**.

The shape: compute **one deadline** when the request arrives. For each part: `supplyAsync` on a **dedicated bounded executor** — never the common pool — then `orTimeout(remaining)`, then `exceptionally` to a fallback marked **degraded**. `allOf` joins. The response is **always 200**, even when all three degrade, and a `degraded` list names what is missing.

**The trap everyone misses: `orTimeout` completes the future but does not cancel the work.** The slow call keeps its worker until it returns. So an unbounded pool piles up abandoned calls behind a slow dependency. My executor is **8 threads, 16 queue slots, and it rejects when full**, and a rejection degrades the part immediately. And the database connection pool is the same size as the executor, so the executor — not the pool — is the limit.

**[DEPTH]** **Structured concurrency** is the cleaner alternative, but it is a **preview feature in Java 21**. **VERIFY** the status on the JDK you target before relying on it.

Measured: overview read p99 about 27 to 30 ms at about 52 requests a second, alongside 1,000 and 2,000 events a second, light and unsaturated. (not measured: degraded-part rate, and which store served the reads)."

> Beats (both): pick, cost, failure (what the user sees: full buffer → lag; slow dependency → one part marked degraded, still 200), measure.

**If they push:** "Why one flusher thread?" — "A single writer per batch keeps the retry-and-isolate logic simple and the order deterministic. The throughput limit is Postgres per batch, not threads. I'd measure before adding writers: three listener threads already feed it."

**If they push:** "A thread-safe batch writer, on a whiteboard — what do you say?" — "Bounded queue, one flusher, flush on N or T, offer with timeout for backpressure, drain on close. Then the three things missing from the textbook version: retries and a dead-letter path, metrics, and the open-check-versus-close race — fix it with a lock or a poison pill. I fixed it with a read-write lock."

Code: `services/sink-writer/src/main/java/com/streampulse/sink/application/batch/BatchWriter.java`, `.../lifecycle/BatchWriterLifecycle.java`, `.../adapter/in/kafka/AggregateBatchListener.java`; `services/query-api/src/main/java/com/streampulse/query/application/TitleOverviewService.java`, `services/query-api/src/main/java/com/streampulse/query/application/BoundedExecutor.java`. Tests: `BatchWriterTest` (`flushesWhenBatchSizeIsReached`, `fullQueueRejectsWithinTheOfferTimeout`, `closeDrainsEverythingAccepted`, `acceptedImpliesWrittenUnderConcurrentSubmitAndClose`, `closeWaitsForASubmitThatIsAlreadyPastTheOpenCheck`, `poisonRecordIsIsolatedToTheDeadLetterSinkWhileOthersAreWritten`, `outageFailsFuturesWithoutDeadLettering`), `BatchWriterLifecycleTest.lifecycleStopsAfterTheListenerContainer`; `TitleOverviewServiceTest` (`slowCallIsDegradedAndTheBudgetHolds`, `callsRunInParallel`, `allCallsFailingStillAnswers`, `workNeverRunsOnTheCommonPool`, `saturatedExecutorDegradesInsteadOfBlocking`).

---

## 15:00 — 16:00 | Health: SLIs, SLOs and burn rate (R2)

"'Health' means **user-facing SLIs**, not CPU. Five.

**One. Ingest success rate.** Share of batches that get 202. I wrote a dashboard panel for the 5xx ratio.
**Two. p99 ingest latency.** From the HTTP server histogram on `/v1/events:batch`.
**Three. End-to-end freshness.** `streampulse.sink.freshness`, window end to durable write.
**Four. Consumer lag.** On the aggregate topic, and on the raw topic for the processor. Because I acknowledge only after the durable write, lag measures **work not yet stored**, which is exactly what I want it to mean.
**Five. DLQ rate.** Rejected events by reason from the processor, dead letters from the sink.

I'd add a sixth I **have not built**: **data completeness**, events received versus heartbeats expected.

**SLOs, both targets:** ingest availability of 99.9 percent, and 99 percent of windows durable within 30 seconds of window end. Keep an **error budget**, and alert on **burn rate** — how fast the budget is going — instead of static thresholds, so a slow leak and a fast fire both page, at different urgency. **[DEPTH] Honest note:** I wrote the metrics, recording rules, multiwindow burn-rate alert rules and four dashboards. **I never ran or wired them:** `make up-obs` does not load them, the rules were never checked with promtool, and the runbook does not exist. RED for services, USE for resources: that is how I'd organise the panels.

**Probes:** liveness answers 'am I broken beyond recovery?' and contains **only** the liveness state — never a dependency. Put Redis in it and one Redis blip restarts every pod at once. I reproduced exactly that by mutation. Readiness answers 'can I take traffic?'.

Measured, ingest alone, one laptop, one run: with the default quota, 429 on 33.4 percent at 3,000 events a second. With the quota lifted, 503 on 11.8 percent at 45,000. I did not prove the 503 mechanism; the breaker is ruled out and the 64-permit bound is the likely cause."

> Beats: pick (user-facing SLIs), cost (SLO needs an error budget process), failure (alert storm → burn rate), measure (it is the measurement).

**If they push:** "Why is freshness better than lag?" — "Lag counts records. Freshness counts what the user feels: how old is the number on screen. A system can have zero lag and a stalled window, and only freshness shows it."

Code: `docker/observability/grafana/dashboards/streampulse.json` (also `streampulse-red.json`, `streampulse-jvm.json`, `streampulse-slo.json`; panels: ingest accepted/s, ingest 5xx and p99, consumer lag, freshness p99, dead-letter rate, batch-writer queue depth), `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/MicrometerIngestMetrics.java`, `services/stream-processor/src/main/java/com/streampulse/processor/config/MicrometerProcessorMetrics.java`. Tests: `HealthProbesIT.livenessGroupHasNoDependencies`, `HealthProbesIT.readinessGoesOutOfServiceOnDrainButLivenessStaysUp`, `StreamsStateHealthIndicatorTest.runningAndRebalancingAreUp`.

---

## 16:00 — 17:00 | The consumer-lag playbook (M4)

"'Lag keeps growing during a live match.' **Diagnose before scaling.**

**Step one: which lag?** Raw topic lag means the stream processor is behind. Aggregate topic lag means the sink is behind.
**Step two: all partitions or one?** One partition lagging is a **hot key**, and more consumers will not help. Kafka's lag metric I show is the **max over partitions**, so I would also look per partition.
**Step three: for the sink, look at the batch-writer queue depth.** Near its 2,000 capacity means **Postgres is slow** — check flush latency. Near empty with lag growing means the consumers are starved or rebalancing.
**Step four: rebalance storms.** I use the cooperative sticky assignor, so a rebalance moves only the partitions that must move.

**Then act, in this order:** fix the hot key; batch the sink write (already done); scale consumers **up to the partition count and no further**. **Consumers beyond the partition count do nothing** — the extra ones sit idle. My raw topic has 12 partitions; my aggregate topic has 6 and the sink runs 3 consumer threads, so there is exactly 2x headroom there, then a wall. Past that, I'd shed or sample low-value events — heartbeats, not errors — and the principle again: telemetry may be sampled; billing never.

**[DEPTH] Honest note:** I did **not** build autoscaling on lag (KEDA). That is a Kubernetes-phase item. I'd say 'autoscale on lag, not CPU' as the design, and not claim it is built.

Measured: (not measured: peak consumer lag on telemetry.agg.v1 during the load run and time to drain after load stops)."

> Beats: pick (diagnose first), cost (partition ceiling), failure (what the user sees: dashboards go stale, not wrong), measure (lag per partition, queue depth, freshness).

**If they push:** "Why does stale beat wrong?" — "A stale dashboard looks stale — I can show the age. A wrong number looks right. That is why the sink acknowledges only after the write: a lagging pipeline is slow, never lossy."

Code: `services/sink-writer/src/main/resources/application.yml` (`concurrency: 3`, `CooperativeStickyAssignor`, `max-poll-records: 500`), `docker/compose.yml` (partition counts), `services/sink-writer/src/main/java/com/streampulse/sink/config/MicrometerSinkMetrics.java` (`streampulse.sink.batch.queue.depth`).

---

## 17:00 — 18:15 | Failure modes and what the user sees (R5)

"The rule: **telemetry must never break playback.** The player SDK sends events fire-and-forget on a background path. Every failure below degrades the dashboard, never the stream. Table in words:

**Redis idempotency store down.** Ingest **fails open**: publishes anyway, counts it, returns `degraded: true`, logs at WARN not ERROR. User sees nothing. Dashboards may over-count for the outage.

**Kafka slow.** A **timeout** cuts the call at 5 seconds; a **circuit breaker** opens and fails fast; clients get **503 with Retry-After** equal to the breaker's own open duration, so the two cannot drift. The wrapping order is **`Retry( CircuitBreaker( TimeLimiter( publish ) ) )`** — the timeout is inside so the breaker counts timeouts; retry is outside so the breaker counts every attempt. I proved both orders by mutation. Retry skips timeouts, an open breaker, and a rejection of that batch.

**Ingest saturated.** Semaphore full for 250 milliseconds: **503 plus Retry-After**. The client buffers and retries with jitter.

**Postgres down.** The sink retries forever with exponential backoff, never dead-letters healthy records, the buffer fills, the listener stops polling, **lag rises**. Dashboards go stale. Nothing is lost; it is replayed from Kafka.

**Redis projection down.** Postgres is current; the live feed and latest hash lag. The history endpoint still works.

**Query API dependency slow.** The overview answers **200** with that part empty and named in `degraded`.

**Processor crash.** State restores from the changelog; `exactly_once_v2` prevents a double publish. **[DEPTH] Honest note, and it is a failure.** I ran `kill -9` on the processor mid-window. There were no duplicates. But on-time events were dropped as expired-window records: 1,482 of 4,000 in one run and 2,554 of 8,000 in another, each equal to the processor's own WARN count. My likely mechanism is that catching up a multi-partition backlog advanced stream time past the 5 second grace. That is not proven. It is not fixed. It is invisible to the service metrics. Each fix changes a documented guarantee: a larger grace, emitting revisions, or draining the backlog first, so it needs an ADR. One laptop, one broker, one crash type.

**Bad bytes or invalid event.** Dead-lettered with a reason; the stream continues.

**Region loss.** Not built. I'd say multi-region ingest with a **stated data-loss budget**."

> Beats: pick (degraded modes decided in advance), cost (stale or inflated numbers), failure (the list), measure (breaker state in Actuator, DLQ rate, lag).

**If they push:** "Why 503 and not 500 on a broker failure?" — "Nothing is wrong with the batch. The same bytes will succeed when the dependency recovers. A 500 tells the client its request is malformed, and it may stop retrying — and that telemetry is gone."

Code: `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/resilience/ResilientEventPublisher.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/config/IngestConfig.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/application/IngestService.java`, `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/in/rest/IngestExceptionHandler.java`. Tests: `ResilientEventPublisherTest` nested classes — `timeoutsAreCountedByTheBreaker`, `eachAttemptIsCountedByTheBreaker`, `openBreakerIsNotRetried`, `timeoutsAreNotRetried`, `retryAfterMatchesTheConfiguredWait`; `IngestServiceTest` nested `FailOpen` (`publishesAnywayAndReportsDegraded`); `AggregateSinkTest.projectionFailureDoesNotFailTheBatch`; `AggregateBatchListenerTest.storeFailureMeansNoAck`. ADR: `docs/adr/0007-fail-open-on-idempotency-store-failure.md`, `docs/adr/0009-circuit-breaker-and-timeout-on-the-publisher.md`.

---

## 18:15 — 19:15 | What I would do at 10x and 100x

"The honest answer is that some of my choices are demo-scale. Here is where each one breaks.

**1. The Redis idempotency claim at the front door.** One `SET NX EX` per event id, **sequentially, one network round trip per id** — up to 500 per batch. With a 24-hour TTL, at the prompt's 1 million events a second, that is on the order of **86 billion keys** — arithmetic from the assumption, not a measurement. It does not survive. The fix: pipeline the commands, shorten the TTL to minutes (retries happen in seconds), and for heartbeats **drop it entirely**, because the stream processor already dedups within window plus grace and the window drops anything later. Keep the claim for event types that cannot tolerate duplicates.

**2. The hot key.** One live match in one region is one aggregation key, so one stream task, and one `WindowAccumulator` whose set of session ids grows with every viewer and is **copied on every event**. The fix has two parts: **salt the key** — `tenant:title:region:salt` — aggregate partials in parallel, then merge in a second stage; and replace the exact set with a **HyperLogLog sketch**, which merges naturally.

**3. The dedup state.** About 30 million ids at 1 million events a second, in a persistent store with a changelog. At 10x that is the biggest state in the system. Options: shorter retention, or dedup on a smaller key space.

**4. Partitions and brokers.** 12 raw partitions and **one broker** is a laptop. At 1 GB/s I'd size partitions and brokers from a load test, run MSK or Kafka with replication factor 3, and set the minimum in-sync replicas. Then `acks=all` finally means something.

**5. The sink.** At 2,000 records a second it is trivial. At 100x, I'd batch bigger, partition the table by time, and keep Redis as the only thing dashboards read for 'now'.

**6. The live feed.** One Redis stream shared by all tenants with a ten-thousand entry cap, and every query instance reads the whole thing. Next step: **a stream per tenant** or per tenant shard.

**7. Raw archive.** Half built. A compactor merges small archive objects into one per tenant and hour, and an S3 bucket exists in Terraform, both only tested against a local simulator. Nothing writes raw events to the archive yet. I'd add a Kafka Connect sink or Firehose to feed it, off the hot path.

Measured baseline, one laptop, loadgen on the same machine: ingest alone ran clean at 40,000 events a second with the quota lifted, and shed 503s from 45,000. With the default quota it stops at 3,000. At 1,500 events a second through the full pipeline, p99 was 68.7 ms. I would not quote 45,000 as capacity."

> Beats: assumption (1M/s → 86B keys, 30M ids), pick (pipeline, salt, sketch), cost (two-stage aggregation complexity, sketch error), failure, measure.

**If they push:** "Is salting safe for distinct counts?" — "Only if I salt by a hash of `sessionId`. Each session then lives in exactly one salt, so the partial distinct counts add. A random salt would count the same viewer twice. With a HyperLogLog sketch I merge the partials instead."

Code: `services/ingest-gateway/src/main/java/com/streampulse/ingest/adapter/out/redis/RedisIdempotencyStore.java` (sequential `setIfAbsent` per id), `services/stream-processor/src/main/java/com/streampulse/processor/domain/WindowAccumulator.java` (set copy per `add`), `services/stream-processor/src/main/java/com/streampulse/processor/adapter/streams/TelemetryTopology.java` (`dedupRange`, retention), `services/sink-writer/src/main/java/com/streampulse/sink/adapter/out/redis/RedisHotProjection.java` (`STREAM_MAX_LENGTH`).

---

## 19:15 — 20:00 | Honest gaps, and close

"Three decisions I'd defend hardest.

**One. At-least-once plus idempotent writes, not 'exactly-once'.** Exactly-once stops at the edge of Kafka. I put the correctness in the database write and proved it with a test that crashes between the write and the ack.

**Two. Window plus grace as a deliberate freshness cost.** One final answer per window, 15 seconds of floor, stated up front.

**Three. Telemetry never breaks playback.** Fail open, degrade per part, back off with Retry-After, and keep the dashboards stale-but-honest.

Here is what is **not** done, so you hear it from me:

- **Every number is a target, not a measurement,** except those I measured in the Phase 3 and Phase 9 runs: one laptop, one broker.
- **Open defect: a processor crash drops on-time events.** After `kill -9`, 1,482 of 4,000 and 2,554 of 8,000 events were dropped as expired-window records, with no duplicates. Likely cause, not proven: backlog catch-up pushing stream time past the 5 second grace. Not fixed, invisible to service metrics, and the fix needs an ADR.
- **Single Kafka broker.** `acks=all` is configured; it has not been shown to protect against a lost replica.
- **No authentication on the query API.** Any client can read any tenant.
- **Alert and burn-rate rules are written but never run or wired.** Metrics and dashboards exist.
- **No autoscaling on lag, no Kubernetes manifests.** Only a Dockerfile.
- **No hot-key salting.** One hot title is one task.
- **Exact distinct sets, not HyperLogLog.**
- **Redis is rebuildable in principle; nothing rebuilds it yet.**
- **No outbox in the sink,** because it writes Postgres first and Redis is derived. The control plane does have one, with Debezium.
- **No test sends a real SIGTERM,** and the sink was never killed.
- **Nothing writes the raw archive.** The S3 bucket and compactor exist; the compactor runs only in tests.

Where I'd go with more time: fix the crash defect with an ADR, then salting plus a sketch, then wire the alert rules and add lag-based autoscaling."

**If they push:** "What did the tests teach you?" — "Three things. A randomised concurrency test gave me false confidence until I forced the interleaving. Nine of ten tests still passed with the decorator order wrong, so I mutate the code to prove a test can fail. And a green suite did not mean the app ran: running it found a missing system property the tests had and `bootRun` did not."

---

## Numbers I will quote

| Number | Value | Kind | Where it comes from |
|---|---|---|---|
| Concurrent viewers at peak | 10 M | **assumption** (prompt) | Question bank SD1 |
| Heartbeat interval | 1 per 10 s | **assumption** | Question bank SD1 |
| Events per second at peak | 1 M/s | **assumption** (10 M ÷ 10 s) | arithmetic |
| Bytes per event, throughput | ~1 KB, ~1 GB/s | **assumption**, before compression | Question bank SD1 |
| Series count, aggregate rate | 10,000 series, ~2,000 records/s | **assumption** (1,000 titles × 10 regions × 2 metrics ÷ 10 s) | arithmetic |
| Dedup state at peak | ~30 M ids | **assumption** (1 M/s × 30 s retention) | `TelemetryTopology.Config.dedupRange()` × 2 |
| Redis claim keys at 24 h TTL | ~86 B | **assumption** (1 M/s × 86,400 s) | `idempotency-ttl: 24h` in ingest `application.yml` |
| Window / grace / freshness floor | 10 s / 5 s / 15 s from window start | configured target (the observed 5.2 to 5.4 s is below, in the freshness row) | stream-processor `application.yml` |
| Max batch / in-flight / permit wait | 500 / 64 / 250 ms | configured target | ingest `application.yml` |
| Raw / aggregate partitions | 12 / 6 | configured (local compose) | `docker/compose.yml` |
| Batch writer | 200 records, 100 ms, buffer 2,000, offer 1 s | configured target | sink-writer `application.yml` |
| Overview budget / coalesce / heartbeat | 300 ms / 250 ms / 15 s | configured target | query-api `application.yml` |
| Sustained throughput, Phase 3 | 1,500 events/s for 90 s, 0 rejected (offered rate, not a ceiling) | measured, one run | Phase 3 Day 5 |
| Ingest-only ceiling, quota lifted | clean at 40,000 events/s; 503s from 45,000 (11.8% errors, p99 595.1 ms at that step); 503 mechanism not proven | measured, one run, loadgen on the same laptop | Phase 9 T9-11, Run B |
| Default quota as a ceiling | 429s from 3,000 events/s (33.4% refused); last clean 2,500 | measured, one run | Phase 9 T9-11, Run A |
| Ingest JVM while refusing 105,800 events | heap 45.5 to 313 MiB, threads 34 to 35 | measured, one 16 s overload | Phase 9 T9-11 |
| p99 ingest latency | 68.7 ms at 1,500/s (p50 50.0, p95 57.0); 76.5 ms at 1,000/s; 70.9 and 59.0 ms at 2,000/s | measured, full pipeline, noise about 12 ms | Phase 3 Day 5, Phase 9 T9-13 |
| Freshness | 5.2 to 5.4 s per window after window end, in Postgres, at 1,000 and 2,000 events/s; loadgen's own histogram unreliable back-to-back | measured | Phase 9 T9-13 |
| Overview read latency | p50 about 24 ms, p99 27 to 30 ms at about 52 req/s, light and unsaturated | measured | Phase 9 T9-13 |
| End-to-end accounting, happy path | 2,000 and 30,000 events: every closed window exact; 400 and 6,000 re-sent duplicates not double-counted | measured | Phase 9 T9-12 |
| Processor kill -9, outage 40 to 50 s | 1,482 of 4,000 and 2,554 of 8,000 on-time events dropped as expired-window records; no duplicates; open defect | measured | Phase 9 T9-34 |
| Flush latency / queue depth | (not measured: batch flush latency p99 and peak queue depth under the load run) | not measured | `streampulse.sink.batch.flush`, `streampulse.sink.batch.queue.depth` |
| Peak lag and drain time | (not measured: peak consumer lag on telemetry.agg.v1 during the load run and time to drain after load stops) | not measured | consumer lag panel |
| Overview degraded-part rate | (not measured: degraded-part rate; which store served the reads was not checked) | not measured | load run |
| SSE streams per instance | (not measured: concurrent SSE streams per query-api instance and CPU/heap at that count) | not measured | load run |
| lz4 compression ratio | (not measured: lz4 compression ratio on realistic event payloads) | not measured | load run |

---

## Delivery notes

- **Draw as you talk.** Left to right: clients → ingest → Kafka → Streams → sink → Postgres/Redis → query/SSE. Do not talk for three minutes with a blank board.
- **Numbers before boxes.** Say 1 M/s before you draw ingest.
- **Never say 'exactly-once' without saying where.** The sentence is 'Kafka to Kafka, with transactions'.
- **Every choice gets a cost.** 'I picked X. The cost is Y. I'd watch Z.'
- **If you don't know a default:** 'I'd check the current docs for the exact value — the mechanism is …'. That is a strong answer.
- **I for my work, we for the team's.** This repo is mine; say 'I'.
- **Honest notes are not apologies.** Say them flat, then say what you'd do.
