/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.domain.MetricWindow;
import java.util.List;

/** The recent values of an alert's series, or the reason they are missing: never silently absent. */
public sealed interface RecentMetrics {

    record Available(List<MetricWindow> windows, int omitted) implements RecentMetrics {}

    /** {@code reason} is built from fixed words only. */
    record Unavailable(String reason) implements RecentMetrics {}
}
