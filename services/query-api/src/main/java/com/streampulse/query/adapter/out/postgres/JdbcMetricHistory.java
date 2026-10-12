/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.out.postgres;

import com.streampulse.query.application.HistoryQuery;
import com.streampulse.query.application.MetricHistoryPort;
import com.streampulse.query.domain.MetricPoint;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Blocking JDBC. It must only ever be called from the bounded executor, never from an event-loop thread
 * (BlockingIsolationTest); nothing in this class enforces that, the composition root does.
 */
public final class JdbcMetricHistory implements MetricHistoryPort {

    private final JdbcTemplate jdbc;
    private final int maxRows;

    public JdbcMetricHistory(JdbcTemplate jdbc, int maxRows) {
        this.jdbc = jdbc;
        this.maxRows = maxRows;
    }

    @Override
    public List<MetricPoint> range(HistoryQuery query) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT title_id, region, metric, window_start, value, sample_count
                FROM metric_window
                WHERE tenant_id = ? AND window_start >= ? AND window_start < ?""");
        List<Object> args = new ArrayList<>();
        args.add(query.tenantId());
        args.add(Timestamp.from(query.from()));
        args.add(Timestamp.from(query.to()));
        if (query.metric() != null) {
            sql.append(" AND metric = ?");
            args.add(query.metric());
        }
        if (query.titleId() != null) {
            sql.append(" AND title_id = ?");
            args.add(query.titleId());
        }
        if (query.region() != null) {
            sql.append(" AND region = ?");
            args.add(query.region());
        }
        sql.append(" ORDER BY window_start, region, title_id, metric LIMIT ?");
        args.add(maxRows);

        return jdbc.query(
                sql.toString(),
                (rs, row) -> new MetricPoint(
                        rs.getString("title_id"),
                        rs.getString("region"),
                        rs.getString("metric"),
                        rs.getObject("window_start", OffsetDateTime.class).toInstant(),
                        rs.getDouble("value"),
                        rs.getLong("sample_count")),
                args.toArray());
    }
}
