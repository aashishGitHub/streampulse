/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.domain;

import java.time.Instant;

/**
 * One aggregation window of one series. {@code windowEnd} is null when the source does not report it
 * (query-api's range query returns the start only). {@code titleId} and {@code region} may be empty
 * (an aggregate across all titles or regions) and are tenant-influenced labels.
 */
public record MetricWindow(
        String titleId,
        String region,
        Metric metric,
        Instant windowStart,
        Instant windowEnd,
        double value,
        long sampleCount) {}
