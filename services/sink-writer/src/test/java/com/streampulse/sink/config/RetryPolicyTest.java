/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

class RetryPolicyTest {

    @Test
    @DisplayName("the redelivery backoff grows, is capped, and never returns STOP: an outage is waited out")
    void backoffNeverGivesUp() {
        var retry = new SinkProperties.Retry(Duration.ofMillis(100), 2.0, Duration.ofSeconds(30));
        BackOffExecution execution = SinkConfig.neverGiveUpBackOff(retry).start();

        long previous = 0;
        for (int i = 0; i < 10_000; i++) {
            long next = execution.nextBackOff();
            assertThat(next).as("attempt %d", i).isNotEqualTo(BackOffExecution.STOP);
            assertThat(next).isBetween(previous, 30_000L);
            previous = next;
        }
        assertThat(previous).isEqualTo(30_000L);
    }
}
