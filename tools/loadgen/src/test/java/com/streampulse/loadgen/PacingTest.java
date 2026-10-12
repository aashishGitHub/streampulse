/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PacingTest {

    @Test
    void intervalIsBatchSizeOverRate() {
        assertThat(Pacing.intervalNanos(2000, 100))
                .isEqualTo(Duration.ofMillis(50).toNanos());
        assertThat(Pacing.intervalNanos(100, 100))
                .isEqualTo(Duration.ofSeconds(1).toNanos());
        assertThat(Pacing.intervalNanos(50, 100))
                .isEqualTo(Duration.ofSeconds(2).toNanos());
    }

    @Test
    void intervalRejectsNonPositiveInputs() {
        assertThatThrownBy(() -> Pacing.intervalNanos(0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pacing.intervalNanos(100, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxBatchesIsTheTargetEventsRoundedUpToWholeBatches() {
        assertThat(Pacing.maxBatches(2000, 60, 100)).isEqualTo(1200);
        assertThat(Pacing.maxBatches(1050, 1, 100)).isEqualTo(11);
        assertThat(Pacing.maxBatches(1, 1, 500)).isEqualTo(1);
    }

    @Test
    void nextDueIsTheLaterOfScheduleAndPause() {
        assertThat(Pacing.nextDue(100, 500)).isEqualTo(500);
        assertThat(Pacing.nextDue(900, 500)).isEqualTo(900);
    }

    @Test
    void retryAfterParsesIntegerSeconds() {
        assertThat(Pacing.retryAfterNanos("3", Duration.ofSeconds(1), Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofSeconds(3).toNanos());
    }

    @Test
    void retryAfterFallsBackWhenMissingOrNotASecondCount() {
        long fallback = Duration.ofSeconds(1).toNanos();

        assertThat(Pacing.retryAfterNanos(null, Duration.ofSeconds(1), Duration.ofSeconds(30)))
                .isEqualTo(fallback);
        assertThat(Pacing.retryAfterNanos(
                        "Wed, 21 Oct 2026 07:28:00 GMT", Duration.ofSeconds(1), Duration.ofSeconds(30)))
                .isEqualTo(fallback);
        assertThat(Pacing.retryAfterNanos("-4", Duration.ofSeconds(1), Duration.ofSeconds(30)))
                .isEqualTo(fallback);
    }

    @Test
    void retryAfterIsCapped() {
        assertThat(Pacing.retryAfterNanos("3600", Duration.ofSeconds(1), Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofSeconds(30).toNanos());
    }
}
