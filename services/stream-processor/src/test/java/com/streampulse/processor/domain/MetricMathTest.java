/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import static com.streampulse.processor.domain.ObservationFixtures.error;
import static com.streampulse.processor.domain.ObservationFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetricMathTest {

    @Test
    @DisplayName("concurrent viewers is the number of DISTINCT sessions, not events")
    void concurrentViewers() {
        WindowAccumulator acc = WindowAccumulator.empty()
                .add(event("e-1", "s-1"))
                .add(event("e-2", "s-1"))
                .add(event("e-3", "s-2"));

        MetricValue viewers = MetricMath.compute(acc).stream()
                .filter(v -> v.metric() == Metric.CONCURRENT_VIEWERS)
                .findFirst()
                .orElseThrow();

        assertThat(viewers.value()).isEqualTo(2.0);
        assertThat(viewers.sampleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("error rate is error events over all events")
    void errorRate() {
        WindowAccumulator acc = WindowAccumulator.empty()
                .add(event("e-1", "s-1"))
                .add(error("e-2", "s-1"))
                .add(event("e-3", "s-2"))
                .add(event("e-4", "s-3"));

        MetricValue rate = MetricMath.compute(acc).stream()
                .filter(v -> v.metric() == Metric.ERROR_RATE)
                .findFirst()
                .orElseThrow();

        assertThat(rate.value()).isEqualTo(0.25);
        assertThat(rate.sampleCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("every window yields exactly one value per metric")
    void oneValuePerMetric() {
        List<MetricValue> values = MetricMath.compute(WindowAccumulator.empty().add(event("e-1", "s-1")));

        assertThat(values).extracting(MetricValue::metric).containsExactlyInAnyOrder(Metric.values());
    }
}
