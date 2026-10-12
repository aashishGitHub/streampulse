/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.streams.KafkaStreams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class StreamsStateHealthIndicatorTest {

    private static Status statusFor(KafkaStreams.State state) {
        return new StreamsStateHealthIndicator(() -> state).health().getStatus();
    }

    @Test
    @DisplayName("running and rebalancing are both ready: a rebalance must not pull every pod out at once")
    void runningAndRebalancingAreUp() {
        assertThat(statusFor(KafkaStreams.State.RUNNING)).isEqualTo(Status.UP);
        assertThat(statusFor(KafkaStreams.State.REBALANCING)).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("anything else is not ready")
    void otherStatesAreDown() {
        assertThat(statusFor(KafkaStreams.State.CREATED)).isEqualTo(Status.DOWN);
        assertThat(statusFor(KafkaStreams.State.NOT_RUNNING)).isEqualTo(Status.DOWN);
        assertThat(statusFor(KafkaStreams.State.PENDING_ERROR)).isEqualTo(Status.DOWN);
        assertThat(statusFor(KafkaStreams.State.ERROR)).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("the state name is reported, so an operator sees why")
    void stateIsReported() {
        assertThat(new StreamsStateHealthIndicator(() -> KafkaStreams.State.ERROR)
                        .health()
                        .getDetails())
                .containsEntry("state", "ERROR");
    }
}
