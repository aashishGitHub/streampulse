-- The durable store. Single source of truth: specs/04-sink-writer/hot-store-contract.md.
-- Applied at startup by spring.sql.init.mode=always; IF NOT EXISTS makes that safe to repeat.
-- No Flyway yet: there is one table and no migration history to protect.
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
