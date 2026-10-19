/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application.port;

import com.streampulse.mcp.application.BackendFailure;
import com.streampulse.mcp.domain.Metric;
import com.streampulse.mcp.domain.MetricWindow;
import com.streampulse.mcp.domain.TitleOverview;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.List;

/** Read-only by construction. Both methods throw {@link BackendFailure} when query-api gives no usable answer. */
public interface MetricsPort {

    TitleOverview titleOverview(TenantId tenant, String titleId);

    /** {@code titleId} and {@code region} are optional filters (null = all). Windows have no windowEnd. */
    List<MetricWindow> metricWindows(
            TenantId tenant, Metric metric, String titleId, String region, Instant from, Instant to);
}
