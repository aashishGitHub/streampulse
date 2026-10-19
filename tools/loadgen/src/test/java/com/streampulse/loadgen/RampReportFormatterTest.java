/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class RampReportFormatterTest {

    private static StepResult step(long rate, long batches, long r503) {
        long ok = batches - r503;
        return new StepResult(
                rate,
                new RunSummary(
                        Duration.ofSeconds(20),
                        batches,
                        batches * 100,
                        ok * 100,
                        ok,
                        0,
                        r503,
                        0,
                        0,
                        OptionalLong.of(1_000_000),
                        OptionalLong.of(2_000_000),
                        OptionalLong.of(12_500_000),
                        0,
                        Duration.ofMillis(5),
                        OptionalDouble.empty(),
                        ""));
    }

    @Test
    void printsOneRowPerStepWithErrorRateAndTheVerdict() {
        SaturationReport report = SaturationFinder.analyse(List.of(step(1000, 200, 0), step(2000, 400, 80)));

        String text = RampReportFormatter.format(report);

        assertThat(text)
                .contains("1000")
                .contains("2000")
                .contains("20.0%")
                .contains("p99=12.5 ms")
                .contains("saturated at 2000 events/s")
                .contains("last clean rate 1000 events/s")
                .contains("503");
    }

    @Test
    void saysTheCeilingWasReachedWhenNothingBreached() {
        String text = RampReportFormatter.format(SaturationFinder.analyse(List.of(step(1000, 200, 0))));

        assertThat(text).contains("no saturation found").contains("highest rate tried: 1000 events/s");
    }

    @Test
    void warnsThatAClientLimitedStepProvesNothingAboutTheServer() {
        String text = RampReportFormatter.format(SaturationFinder.analyse(List.of(step(1000, 100, 0))));

        assertThat(text).contains("client could not offer").contains("1000");
    }
}
