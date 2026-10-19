/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.application.port.MetricsPort;
import com.streampulse.mcp.domain.Backend;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.TitleOverview;
import com.streampulse.platform.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class LiveMetricsService {

    private static final Set<String> KNOWN_PARTS = Set.of("latest", "history", "metadata");

    private static final Comparator<MetricWindow> BY_SERIES = Comparator.comparing(MetricWindow::titleId)
            .thenComparing(MetricWindow::region)
            .thenComparing(MetricWindow::metric);

    private final MetricsPort metrics;
    private final Clock clock;
    private final ToolLimits limits;

    public LiveMetricsService(MetricsPort metrics, Clock clock, ToolLimits limits) {
        this.metrics = metrics;
        this.clock = clock;
        this.limits = limits;
    }

    public LiveMetrics liveMetrics(TenantId tenant, Optional<String> titleId) {
        Instant now = clock.instant();
        return titleId.isPresent() ? forTitle(tenant, titleId.get(), now) : acrossTitles(tenant, now);
    }

    private LiveMetrics forTitle(TenantId tenant, String titleId, Instant now) {
        TitleOverview overview = metrics.titleOverview(tenant, titleId);
        if (!tenant.value().equals(overview.tenantId())) {
            throw new BackendFailure(Backend.QUERY_API, BackendFailure.Category.MALFORMED, 0);
        }
        List<MetricWindow> latest = overview.latest().stream()
                .filter(w -> Metric.LIVE.contains(w.metric()))
                .filter(w -> titleId.equals(w.titleId()))
                .sorted(BY_SERIES)
                .toList();
        List<String> degraded =
                overview.degraded().stream().filter(KNOWN_PARTS::contains).toList();
        return assemble(tenant, Optional.of(titleId), now, latest, degraded);
    }

    private LiveMetrics acrossTitles(TenantId tenant, Instant now) {
        Instant from = now.minus(limits.lookback());
        Map<String, MetricWindow> newest = new LinkedHashMap<>();
        for (Metric metric : EnumSet.copyOf(Metric.LIVE)) {
            for (MetricWindow w : metrics.metricWindows(tenant, metric, null, null, from, now)) {
                newest.merge(
                        w.titleId() + "\u0000" + w.region() + "\u0000" + w.metric(),
                        w,
                        (a, b) -> b.windowStart().isAfter(a.windowStart()) ? b : a);
            }
        }
        List<MetricWindow> latest = new ArrayList<>(newest.values());
        latest.sort(BY_SERIES);
        return assemble(tenant, Optional.empty(), now, latest, List.of());
    }

    private LiveMetrics assemble(
            TenantId tenant, Optional<String> titleId, Instant now, List<MetricWindow> latest, List<String> degraded) {
        List<LiveReading> readings = latest.stream()
                .limit(limits.maxMetricRows())
                .map(w -> reading(w, now))
                .toList();
        return new LiveMetrics(
                tenant, titleId, now, readings, latest.size(), latest.size() - readings.size(), degraded);
    }

    private static LiveReading reading(MetricWindow w, Instant now) {
        boolean hasEnd = w.windowEnd() != null;
        Instant basis = hasEnd ? w.windowEnd() : w.windowStart();
        long age = Math.max(0, Duration.between(basis, now).toSeconds());
        return new LiveReading(w, age, hasEnd ? AgeBasis.WINDOW_END : AgeBasis.WINDOW_START);
    }
}
