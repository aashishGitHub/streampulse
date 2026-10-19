/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.support;

import com.streampulse.mcp.application.BackendFailure;
import com.streampulse.mcp.application.port.AlertsPort;
import com.streampulse.mcp.application.port.MetricsPort;
import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.TitleOverview;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class FakePorts {

    private FakePorts() {}

    public static final class Alerts implements AlertsPort {
        public List<AlertSnapshot> returns = List.of();
        public BackendFailure fails;
        public final List<TenantId> calls = new ArrayList<>();

        @Override
        public List<AlertSnapshot> activeAlerts(TenantId tenant) {
            calls.add(tenant);
            if (fails != null) {
                throw fails;
            }
            return returns;
        }
    }

    public record MetricCall(TenantId tenant, Metric metric, String titleId, String region, Instant from, Instant to) {}

    public static final class Metrics implements MetricsPort {
        public Function<MetricCall, List<MetricWindow>> windows = call -> List.of();
        public TitleOverview overview;
        public BackendFailure fails;
        public final List<MetricCall> windowCalls = new ArrayList<>();
        public final List<String> overviewCalls = new ArrayList<>();

        @Override
        public TitleOverview titleOverview(TenantId tenant, String titleId) {
            overviewCalls.add(tenant + "/" + titleId);
            if (fails != null) {
                throw fails;
            }
            return overview;
        }

        @Override
        public List<MetricWindow> metricWindows(
                TenantId tenant, Metric metric, String titleId, String region, Instant from, Instant to) {
            MetricCall call = new MetricCall(tenant, metric, titleId, region, from, to);
            windowCalls.add(call);
            if (fails != null) {
                throw fails;
            }
            return windows.apply(call);
        }
    }
}
