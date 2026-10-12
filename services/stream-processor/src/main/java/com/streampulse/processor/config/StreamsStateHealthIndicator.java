/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.config;

import java.util.function.Supplier;
import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness only. Rebalancing counts as up: a rebalance is normal and short, and failing
 * readiness during one would remove every pod at once during a rolling deploy.
 *
 * <p>Never part of liveness: a stopped stream thread is fixed by the broker coming back, not
 * by restarting this process, and restarting every pod because the broker blipped turns a
 * blip into an outage.
 */
public class StreamsStateHealthIndicator implements HealthIndicator {

    private final Supplier<KafkaStreams.State> state;

    public StreamsStateHealthIndicator(Supplier<KafkaStreams.State> state) {
        this.state = state;
    }

    @Override
    public Health health() {
        KafkaStreams.State current = state.get();
        Health.Builder builder = current.isRunningOrRebalancing() ? Health.up() : Health.down();
        return builder.withDetail("state", current.name()).build();
    }
}
