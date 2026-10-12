# Hot-store contract (sink-writer writes, query-api reads)

Single source of truth for what is in Postgres and Redis. Change it here first.

## Postgres: durable, idempotent

```sql
CREATE TABLE IF NOT EXISTS metric_window (
    tenant_id     text             NOT NULL,
    title_id      text             NOT NULL DEFAULT '',   -- '' means "no title"
    region        text             NOT NULL,
    metric        text             NOT NULL,              -- CONCURRENT_VIEWERS | ERROR_RATE
    window_start  timestamptz      NOT NULL,
    window_end    timestamptz      NOT NULL,              -- exclusive
    value         double precision NOT NULL,
    sample_count  bigint           NOT NULL,
    computed_at   timestamptz      NOT NULL,
    PRIMARY KEY (tenant_id, title_id, region, metric, window_start)
);
```

Upsert is idempotent and never lets an older computation overwrite a newer one:
`INSERT ... ON CONFLICT (tenant_id,title_id,region,metric,window_start) DO UPDATE SET ... WHERE metric_window.computed_at <= EXCLUDED.computed_at`.

## Redis: derived, rebuildable from Postgres

**Latest value per series** — hash, key `sp:latest:{tenantId}`, field `{titleId}:{region}:{metric}`
(`titleId` is empty when there is none), value a JSON object:
`{"windowStart":<epochMillis>,"windowEnd":<epochMillis>,"value":<double>,"sampleCount":<long>,"computedAt":<epochMillis>}`

**Live feed** — ONE Redis Stream, key `sp:agg:live`, written with `XADD ... MAXLEN ~ 10000`.
Entry fields (all strings): `tenantId, titleId, region, metric, windowStart, windowEnd, value, sampleCount, computedAt`
(times are epoch millis; `titleId` empty when none). The stream entry id is the SSE `id:` that
`Last-Event-ID` resumes from.

Write order in sink-writer: Postgres, then Redis, then ack the Kafka offset. Redis can always be
rebuilt from Postgres, so a Redis failure must not block the ack path beyond the retry policy.

## Metric names (Micrometer; Prometheus converts dots to underscores and adds unit suffixes)

| Name | Type | Owner |
|---|---|---|
| `streampulse.sink.freshness` | Timer, histogram enabled, seconds: now minus window end, at durable write | sink-writer |
| `streampulse.sink.batch.queue.depth` | Gauge | sink-writer |
| `streampulse.sink.batch.flush` | Timer | sink-writer |
| `streampulse.sink.dead.letters` | Counter | sink-writer |
| `streampulse.processor.events.rejected` | Counter, tag `reason` | stream-processor |
| `streampulse.processor.events.duplicates` | Counter | stream-processor |
| `streampulse.ingest.events.published` | Counter | ingest-gateway (exists) |

## Ports

ingest-gateway 8080 (gRPC 9090) · query-api 8082 · stream-processor 8083 · sink-writer 8084 ·
Keycloak 8081 · Kafka 9092 · Postgres 5432 · Redis 6379 · Prometheus 9091 · Grafana 3000.
