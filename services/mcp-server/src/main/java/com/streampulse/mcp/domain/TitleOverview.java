/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import java.util.List;

/** query-api's one-call view of a title; {@code degraded} names parts that FAILED, so empty is "unknown". */
public record TitleOverview(String tenantId, String titleId, List<MetricWindow> latest, List<String> degraded) {

    public TitleOverview {
        latest = List.copyOf(latest);
        degraded = List.copyOf(degraded);
    }
}
