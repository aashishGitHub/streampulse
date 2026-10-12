/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.domain;

import java.time.Instant;

public record MetricPoint(
        String titleId, String region, String metric, Instant windowStart, double value, long sampleCount) {}
