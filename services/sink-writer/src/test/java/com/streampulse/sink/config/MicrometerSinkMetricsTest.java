/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The metric names are a contract (hot-store-contract.md): dashboards break if they drift. */
class MicrometerSinkMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerSinkMetrics metrics = new MicrometerSinkMetrics(registry);

    @Test
    @DisplayName("freshness is a timer with a histogram, recording window end to durable write")
    void freshnessIsAHistogramTimer() {
        metrics.freshness(Duration.ofSeconds(15));

        var timer = registry.get("streampulse.sink.freshness").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(15.0);
    }

    @Test
    @DisplayName("freshness is exported as histogram buckets, so a percentile can be computed across instances")
    void freshnessExportsBuckets() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MicrometerSinkMetrics(prometheus).freshness(Duration.ofSeconds(15));

        assertThat(prometheus.scrape()).contains("streampulse_sink_freshness_seconds_bucket");
    }

    @Test
    @DisplayName("queue depth is a gauge that follows the supplier")
    void queueDepthGaugeFollowsTheSupplier() {
        var depth = new AtomicInteger(3);
        metrics.bindQueueDepth(depth::get);

        assertThat(registry.get("streampulse.sink.batch.queue.depth").gauge().value())
                .isEqualTo(3);
        depth.set(7);
        assertThat(registry.get("streampulse.sink.batch.queue.depth").gauge().value())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("flush latency is a timer and dead letters are a counter")
    void flushTimerAndDeadLetterCounter() {
        metrics.flushed(200, Duration.ofMillis(40));
        metrics.deadLettered();
        metrics.deadLettered();

        assertThat(registry.get("streampulse.sink.batch.flush").timer().count()).isEqualTo(1);
        assertThat(registry.get("streampulse.sink.dead.letters").counter().count())
                .isEqualTo(2.0);
    }
}
