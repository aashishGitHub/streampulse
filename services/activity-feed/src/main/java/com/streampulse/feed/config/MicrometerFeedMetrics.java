/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.config;

import com.streampulse.feed.application.FeedMetrics;
import com.streampulse.feed.domain.FanOutStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class MicrometerFeedMetrics implements FeedMetrics {

    private final Counter timelineWrites;
    private final Counter merges;
    private final Counter deadLetters;
    private final Timer readLatency;
    private final Map<FanOutStrategy, Counter> events = new EnumMap<>(FanOutStrategy.class);

    /** Everything is registered up front, so a meter reads zero before its first event instead of being absent. */
    public MicrometerFeedMetrics(MeterRegistry registry) {
        this.timelineWrites = Counter.builder("streampulse.feed.fanout.writes")
                .description("Follower timeline entries written by fan-out on write")
                .register(registry);
        this.merges = Counter.builder("streampulse.feed.read.merges")
                .description("Celebrity outboxes merged into a feed on read (fan-out on read)")
                .register(registry);
        this.deadLetters = Counter.builder("streampulse.feed.watch.deadlettered")
                .description("Watch events parked on the dead-letter topic")
                .register(registry);
        this.readLatency = Timer.builder("streampulse.feed.read.latency")
                .description("Time to assemble one page of a feed")
                .publishPercentileHistogram()
                .register(registry);
        for (FanOutStrategy strategy : FanOutStrategy.values()) {
            events.put(
                    strategy,
                    Counter.builder("streampulse.feed.fanout.events")
                            .description("Watch events fanned out, by the strategy chosen for the author")
                            .tag("strategy", strategy.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
    }

    @Override
    public void fanOut(FanOutStrategy strategy, int timelineWrites) {
        events.get(strategy).increment();
        this.timelineWrites.increment(timelineWrites);
    }

    @Override
    public void celebrityOutboxesMerged(int outboxes) {
        merges.increment(outboxes);
    }

    @Override
    public void feedRead(Duration elapsed) {
        readLatency.record(elapsed);
    }

    @Override
    public void deadLettered() {
        deadLetters.increment();
    }
}
