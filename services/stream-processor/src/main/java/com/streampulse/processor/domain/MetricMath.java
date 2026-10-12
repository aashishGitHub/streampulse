/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import java.util.List;

/** Turns what a window accumulated into the metrics published for it. */
public final class MetricMath {

    private MetricMath() {}

    public static List<MetricValue> compute(WindowAccumulator window) {
        // An accumulator exists only once it has seen an event, so events is never zero here
        // and the division cannot be by zero.
        return List.of(
                new MetricValue(Metric.CONCURRENT_VIEWERS, window.sessions().size(), window.events()),
                new MetricValue(Metric.ERROR_RATE, (double) window.errors() / window.events(), window.events()));
    }
}
