/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.feed.domain.FanOutStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerFeedMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerFeedMetrics metrics = new MicrometerFeedMetrics(registry);

    @Test
    @DisplayName("every meter exists before the first event, so a dashboard sees zero rather than 'no data'")
    void metersAreRegisteredEagerly() {
        assertThat(registry.get("streampulse.feed.fanout.writes").counter().count())
                .isZero();
        assertThat(registry.get("streampulse.feed.read.merges").counter().count())
                .isZero();
        assertThat(registry.get("streampulse.feed.read.latency").timer().count())
                .isZero();
        assertThat(registry.get("streampulse.feed.watch.deadlettered").counter().count())
                .isZero();
        assertThat(registry.get("streampulse.feed.fanout.events")
                        .tag("strategy", "on_write")
                        .counter()
                        .count())
                .isZero();
        assertThat(registry.get("streampulse.feed.fanout.events")
                        .tag("strategy", "on_read")
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    @DisplayName("a fan-out counts its timeline writes and its strategy")
    void fanOutIsCounted() {
        metrics.fanOut(FanOutStrategy.ON_WRITE, 120);
        metrics.fanOut(FanOutStrategy.ON_WRITE, 30);
        metrics.fanOut(FanOutStrategy.ON_READ, 0);

        assertThat(registry.get("streampulse.feed.fanout.writes").counter().count())
                .isEqualTo(150);
        assertThat(registry.get("streampulse.feed.fanout.events")
                        .tag("strategy", "on_write")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(registry.get("streampulse.feed.fanout.events")
                        .tag("strategy", "on_read")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("celebrity outboxes merged on read are counted, and feed latency is timed")
    void readsAreMeasured() {
        metrics.celebrityOutboxesMerged(3);
        metrics.celebrityOutboxesMerged(0);
        metrics.feedRead(Duration.ofMillis(12));

        assertThat(registry.get("streampulse.feed.read.merges").counter().count())
                .isEqualTo(3);
        assertThat(registry.get("streampulse.feed.read.latency").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("streampulse.feed.read.latency")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(12);
    }

    @Test
    @DisplayName("a dead letter is counted")
    void deadLettersAreCounted() {
        metrics.deadLettered();

        assertThat(registry.get("streampulse.feed.watch.deadlettered").counter().count())
                .isEqualTo(1);
    }
}
