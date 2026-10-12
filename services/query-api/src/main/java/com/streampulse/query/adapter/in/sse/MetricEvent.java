/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import com.streampulse.query.domain.MetricUpdate;
import java.time.Instant;

/** The wire shape of one SSE event's data. The entry id travels as the SSE {@code id}, not in here. */
public record MetricEvent(
        String titleId,
        String region,
        String metric,
        Instant windowStart,
        Instant windowEnd,
        double value,
        long sampleCount,
        Instant computedAt) {

    static MetricEvent of(MetricUpdate update) {
        return new MetricEvent(
                update.titleId(),
                update.region(),
                update.metric(),
                update.windowStart(),
                update.windowEnd(),
                update.value(),
                update.sampleCount(),
                update.computedAt());
    }
}
