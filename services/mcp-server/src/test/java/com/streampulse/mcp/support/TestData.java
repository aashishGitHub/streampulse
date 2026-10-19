/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.support;

import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.AlertState;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TestData() {}

    public static AlertSnapshot firing(String id, String tenant, String series, Instant firedAt) {
        return new AlertSnapshot(
                id,
                tenant,
                "rule-1",
                series,
                AlertState.FIRING,
                Severity.CRITICAL,
                "error rate above the limit",
                firedAt,
                null,
                null);
    }

    public static AlertSnapshot suppressed(String id, String tenant, String series, Instant firedAt, String rootId) {
        return new AlertSnapshot(
                id,
                tenant,
                "rule-2",
                series,
                AlertState.SUPPRESSED,
                Severity.WARNING,
                "viewers dropped",
                firedAt,
                null,
                rootId);
    }

    public static AlertSnapshot withSummary(AlertSnapshot a, String summary) {
        return new AlertSnapshot(
                a.alertId(),
                a.tenantId(),
                a.ruleId(),
                a.series(),
                a.state(),
                a.severity(),
                summary,
                a.firedAt(),
                a.resolvedAt(),
                a.suppressedBy());
    }

    public static MetricWindow window(String title, String region, Metric metric, Instant start, double value) {
        return new MetricWindow(title, region, metric, start, start.plusSeconds(60), value, 120);
    }

    public static MetricWindow historyWindow(String title, String region, Metric metric, Instant start, double value) {
        return new MetricWindow(title, region, metric, start, null, value, 120);
    }
}
