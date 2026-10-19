/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RampRunnerTest {

    private static final int BATCH = 100;

    /** A server that is fine up to {@code capacity} events/s and answers 503 to the rest. */
    private static RunSummary serverWithCapacity(long capacity, long rate, long seconds) {
        long batches = rate * seconds / BATCH;
        long rejected = rate > capacity ? batches * (rate - capacity) / rate : 0;
        long ok = batches - rejected;
        return new RunSummary(
                Duration.ofSeconds(seconds),
                batches,
                batches * BATCH,
                ok * BATCH,
                ok,
                0,
                rejected,
                0,
                0,
                OptionalLong.of(1_000_000),
                OptionalLong.of(2_000_000),
                OptionalLong.of(3_000_000),
                0,
                Duration.ofMillis(5),
                OptionalDouble.empty(),
                "");
    }

    @Test
    void runsEachStepInOrderAtItsRateAndForItsDuration() throws Exception {
        List<String> calls = new ArrayList<>();

        RampRunner.run(new RampPlan(1000, 1000, 3000, 15), (rate, seconds) -> {
            calls.add(rate + "@" + seconds);
            return serverWithCapacity(Long.MAX_VALUE, rate, seconds);
        });

        assertThat(calls).containsExactly("1000@15", "2000@15", "3000@15");
    }

    @Test
    void stopsAfterTheFirstBreachInsteadOfPushingAnAlreadyFailingServerHarder() throws Exception {
        List<Long> rates = new ArrayList<>();

        SaturationReport report = RampRunner.run(new RampPlan(1000, 1000, 5000, 20), (rate, seconds) -> {
            rates.add(rate);
            return serverWithCapacity(2000, rate, seconds);
        });

        assertThat(rates).containsExactly(1000L, 2000L, 3000L);
        assertThat(report.saturatedAtRate()).hasValue(3000);
        assertThat(report.lastCleanRate()).hasValue(2000);
        assertThat(report.steps()).hasSize(3);
    }

    @Test
    void reachingTheCeilingWithoutABreachSaysSo() throws Exception {
        SaturationReport report = RampRunner.run(
                new RampPlan(1000, 1000, 2000, 20), (rate, seconds) -> serverWithCapacity(10_000, rate, seconds));

        assertThat(report.saturatedAtRate()).isEmpty();
        assertThat(report.lastCleanRate()).hasValue(2000);
    }

    @Test
    void anExecutorFailurePropagatesInsteadOfBeingReportedAsASaturationPoint() {
        assertThatThrownBy(() -> RampRunner.run(new RampPlan(1000, 1000, 2000, 20), (rate, seconds) -> {
                    throw new IOException("no token");
                }))
                .isInstanceOf(IOException.class);
    }
}
