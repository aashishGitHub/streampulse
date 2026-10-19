/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.application.port.AlertsPort;
import com.streampulse.mcp.application.port.MetricsPort;
import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.AlertState;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.Series;
import com.streampulse.platform.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * alert-engine has no get-by-id, so this lists the tenant's active alerts and finds the one asked for. The
 * root cause is looked up in that same tenant-filtered list, so it can never be another tenant's alert.
 */
public final class ExplainAlertService {

    private final AlertsPort alerts;
    private final MetricsPort metrics;
    private final Clock clock;
    private final ToolLimits limits;

    public ExplainAlertService(AlertsPort alerts, MetricsPort metrics, Clock clock, ToolLimits limits) {
        this.alerts = alerts;
        this.metrics = metrics;
        this.clock = clock;
        this.limits = limits;
    }

    public Explanation explain(TenantId tenant, String alertId) {
        Instant now = clock.instant();
        List<AlertSnapshot> own = TenantScope.only(tenant, alerts.activeAlerts(tenant));
        Optional<AlertSnapshot> alert = own.stream()
                .filter(a -> a.alertId().equals(alertId) && a.state().active())
                .findFirst();
        if (alert.isEmpty()) {
            return new Explanation(
                    tenant,
                    alertId,
                    now,
                    Optional.empty(),
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    new RecentMetrics.Unavailable("alert not found"));
        }
        AlertSnapshot found = alert.get();
        Optional<AlertSnapshot> root = found.state() == AlertState.SUPPRESSED
                ? own.stream()
                        .filter(a -> a.alertId().equals(found.suppressedBy()))
                        .findFirst()
                : Optional.empty();
        Optional<Series> series = Series.parse(found.series());
        long activeFor = Math.max(0, Duration.between(found.firedAt(), now).toSeconds());
        return new Explanation(tenant, alertId, now, alert, activeFor, root, series, recent(tenant, series, now));
    }

    private RecentMetrics recent(TenantId tenant, Optional<Series> series, Instant now) {
        if (series.isEmpty()) {
            return new RecentMetrics.Unavailable("series is not in the form titleId:region:metric");
        }
        Series s = series.get();
        try {
            List<MetricWindow> windows = metrics
                    .metricWindows(tenant, s.metric(), s.titleId(), s.region(), now.minus(limits.lookback()), now)
                    .stream()
                    .sorted(Comparator.comparing(MetricWindow::windowStart).reversed())
                    .toList();
            List<MetricWindow> shown =
                    windows.stream().limit(limits.maxRecentWindows()).toList();
            return new RecentMetrics.Available(shown, windows.size() - shown.size());
        } catch (BackendFailure failure) {
            return new RecentMetrics.Unavailable(failure.getMessage());
        }
    }
}
