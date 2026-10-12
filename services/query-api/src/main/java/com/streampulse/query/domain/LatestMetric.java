/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

import java.time.Instant;

public record LatestMetric(
        String titleId,
        String region,
        String metric,
        Instant windowStart,
        Instant windowEnd,
        double value,
        long sampleCount,
        Instant computedAt) {}
