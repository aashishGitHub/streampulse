# Phase 5 — Query API: Design

## Flow of control and data

```
GET /v1/titles/{id}/overview?tenantId=          (Netty event loop: accept, route, write; NEVER block)
   │
   ▼
TitleOverviewService.overview()          deadline = clock.now + 300 ms   (computed ONCE)
   │  for each of 3 parts:  supplyAsync(call, blockingExecutor)        bounded, named, REJECTS when full
   │                         .orTimeout(deadline - now) → .exceptionally(fallback, mark degraded)
   ├──▶ LatestMetricsPort  ──▶ Redis hash  sp:latest:{tenant}          (reactive client, block()ed on the executor)
   ├──▶ MetricHistoryPort  ──▶ Postgres metric_window                  (blocking JDBC)
   └──▶ TitleCatalogPort   ──▶ stub, configurable latency              (control plane not built yet)
   │  allOf(...)  →  TitleOverview(..., degraded[])
   ▼
Mono.fromFuture → 200 (always, even when everything degraded)


GET /v1/stream/metrics?tenantId=   [Last-Event-ID]
   │
   │  ONE per instance:  RedisLiveFeed ── XREAD (StreamReceiver) on sp:agg:live ──▶ hot Sink (multicast, best effort)
   │                                                                                    │ drops for a slow subscriber, never blocks the reader
   ▼                                                                                    ▼
SseStreamController ◀──────────────────────────────────────── tenant (and metric) filter per client
   │ resume?  connect replay buffer on the live sink FIRST, then XRANGE [lastId …], drop id <= lastId,
   │          then live filtered to id > last replayed id        (subscribe-before-read: no gap; id filter: no duplicate)
   ▼
Coalescer: every 250 ms emit the LATEST entry per series, ordered by entry id; ticks are dropped while
   │       the client has no demand, so a slow client's pending map is overwritten, never queued
   ▼
merge(Heartbeat every 15 s as an SSE comment)  →  ServerSentEvent(id = stream entry id, event = metric)
```

## Keys

| Thing | Key | Why |
|---|---|---|
| SSE event id | Redis stream entry id `ms-seq` | totally ordered; this is what `Last-Event-ID` resumes from |
| Coalesce key | `tenant:title:region:metric` | one series; newest entry wins |
| Latest hash field | `title:region:metric` | per hot-store contract; parsed from the right (a title id may contain `:`) |

## Why these shapes

- **Reading the stream from an explicit cursor, not `$`.** `$` means "newer than whenever the
  server handles this read", so an entry added between two reads can be skipped (I have not
  proven that `StreamReceiver` re-issues `$` on an idle poll; VERIFY). The feed reads the tail
  id once, follows ids it has actually received, and re-subscribes from that cursor after an
  outage, which is correct either way.
- **`orTimeout` does not cancel the work.** It completes the future; the thread keeps running
  the call. That is why the executor is bounded and rejects (REQ-QA-05/07): without a bound a
  slow dependency leaves a growing pile of abandoned calls behind each timed-out request.
- **Reactive Redis client called from a blocking port.** The overview ports are blocking so one
  executor, one deadline and one fallback rule cover all three. The Redis adapter `block()`s
  with a timeout on the executor thread, which is legal there (not an event-loop thread).
  The streaming path uses the reactive client natively.

## Failure modes

| Failure | Behaviour | Test that proves it |
|---|---|---|
| One call slower than the budget | answer at ~budget, that part degraded, others intact | `slowCallIsDegradedAndTheBudgetHolds` |
| Calls are really concurrent | 3 x 200 ms finishes well under 600 ms | `callsRunInParallel` |
| Every call throws | 200 with empty parts, all three degraded | `allCallsFailingStillAnswers` |
| Work ran on the common pool | never; thread name is the named executor's | `workNeverRunsOnTheCommonPool` |
| Executor saturated | immediate degrade, no waiting | `saturatedExecutorDegradesInsteadOfBlocking` |
| Blocking JDBC on an event loop | never; port runs on the executor | `BlockingIsolationTest.noBlockingPortRunsOnAnEventLoopThread` |
| JDBC range query wrong | ordered, filtered, bounded | `JdbcMetricHistoryIT` |
| Burst of updates | one entry per series per window, latest wins | `CoalescerTest.burstCollapsesToLastValuePerSeries` |
| Slow client | pending overwritten, not queued | `CoalescerTest.slowConsumerGetsLatestNotBacklog` |
| Idle stream | comment every 15 s | `HeartbeatTest` |
| Live entry lands during the replay read | held, then sent once | `SseStreamControllerTest.entryDuringReplayIsNotLost` |
| Other tenant's entry | never delivered, live or replayed | `SseStreamControllerTest.liveOnlyAndTenantFiltered`, `replayIsTenantFiltered`, `SseResumeIT` |
| Latest-hash field with a colon in the title id | matched exactly | `RedisLatestMetricsIT.titleIdMayContainAColon` |
| Entry ids compared as strings | never; numeric order | `EntryIdTest` |
| Reconnect with Last-Event-ID | replay after id, then live, no dup/gap | `SseResumeIT` |
| Malformed Last-Event-ID / missing tenantId | 400 problem+json | `QueryControllersTest` |
| Unknown metric / inverted range | 404 / 400 problem+json | `QueryControllersTest` |
| Redis or Postgres down | feed retries with backoff from its cursor; overview marks that part degraded; probes stay UP | NO dedicated automated test: `QueryApiWiringTest` proves the context boots without either; the degrade path is `allCallsFailingStillAnswers`; the retry loop was only observed by hand (boot with no Redis) |
| Context assembles without Docker | beans and health groups wired | `QueryApiWiringTest` |
| Layering | domain/application free of Spring, Reactor, Redis, JDBC | `QueryArchitectureRulesTest` |

## Packages

`domain` (records) · `application` (services, ports, bounded executor; JDK + SLF4J only) ·
`adapter.in.web` · `adapter.in.sse` (owns the `LiveFeed` port it consumes) · `adapter.out.redis` ·
`adapter.out.postgres` · `adapter.out.catalog` · `config` (the composition root: constructs the
framework-free classes explicitly; no `@Service` on domain or application).

## Decisions

- ADR-0014: WebFlux for this service; Redis Streams for the shared feed.
- ADR-0006 stands for the ingest gateway.
