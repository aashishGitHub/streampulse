/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.time.Instant;
import java.util.Objects;

/** One final window of one series. */
public record Observation(
        String tenantId,
        String titleId,
        String region,
        String metric,
        Instant windowStart,
        Instant windowEnd,
        double value) {

    public Observation {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(windowStart, "windowStart");
        Objects.requireNonNull(windowEnd, "windowEnd");
        if (!windowEnd.isAfter(windowStart)) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite, was " + value);
        }
    }

    public Series series() {
        return new Series(tenantId, titleId, region, metric);
    }
}
