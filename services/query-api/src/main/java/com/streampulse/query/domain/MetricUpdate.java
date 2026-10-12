/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

import java.time.Instant;

/** One entry of the live feed. {@code titleId} is empty when the aggregate has no title. */
public record MetricUpdate(
        EntryId id,
        String tenantId,
        String titleId,
        String region,
        String metric,
        Instant windowStart,
        Instant windowEnd,
        double value,
        long sampleCount,
        Instant computedAt) {

    /** A series is one line on a chart: newer windows of the same series replace older ones. */
    public String seriesKey() {
        return tenantId + ":" + titleId + ":" + region + ":" + metric;
    }
}
