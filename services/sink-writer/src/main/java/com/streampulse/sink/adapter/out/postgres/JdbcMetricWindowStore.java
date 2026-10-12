/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.adapter.out.postgres;

import com.streampulse.sink.domain.MetricWindow;
import com.streampulse.sink.domain.MetricWindowStore;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The idempotent upsert from hot-store-contract.md. Replaying a record changes nothing, and a
 * computation older than the stored one never overwrites it.
 */
public final class JdbcMetricWindowStore implements MetricWindowStore {

    // `<=` not `<`: a replay of the very same record must be accepted as a no-op, not refused.
    static final String UPSERT =
            """
            INSERT INTO metric_window
                (tenant_id, title_id, region, metric, window_start, window_end, value, sample_count, computed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, title_id, region, metric, window_start) DO UPDATE SET
                window_end   = EXCLUDED.window_end,
                value        = EXCLUDED.value,
                sample_count = EXCLUDED.sample_count,
                computed_at  = EXCLUDED.computed_at
            WHERE metric_window.computed_at <= EXCLUDED.computed_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcMetricWindowStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void upsertAll(List<MetricWindow> windows) {
        // One transaction: a batch is stored whole or not at all, and commits once instead of per row.
        transaction.executeWithoutResult(status -> jdbc.batchUpdate(UPSERT, windows, windows.size(), (ps, w) -> {
            ps.setString(1, w.tenantId());
            ps.setString(2, w.titleId());
            ps.setString(3, w.region());
            ps.setString(4, w.metric());
            ps.setObject(5, utc(w.windowStart()));
            ps.setObject(6, utc(w.windowEnd()));
            ps.setDouble(7, w.value());
            ps.setLong(8, w.sampleCount());
            ps.setObject(9, utc(w.computedAt()));
        }));
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
