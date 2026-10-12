/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ReportFormatterTest {

    private static RunSummary summary(OptionalLong p50, OptionalDouble freshness) {
        return new RunSummary(
                Duration.ofSeconds(10),
                100,
                10_000,
                8_000,
                80,
                15,
                3,
                1,
                1,
                p50,
                p50,
                p50,
                0,
                Duration.ofMillis(120),
                freshness,
                "");
    }

    @Test
    void reportsCountsRatesAndMillisecondPercentiles() {
        String text = ReportFormatter.format(summary(OptionalLong.of(12_500_000), OptionalDouble.of(2.5)));

        assertThat(text)
                .contains("accepted events/s: 800.0")
                .contains("offered events/s: 1000.0")
                .contains("202=80")
                .contains("429=15")
                .contains("503=3")
                .contains("other=1")
                .contains("io-errors=1")
                .contains("p50=12.5 ms")
                .contains("freshness p99: 2.50 s");
    }

    @Test
    void saysUnavailableWhenFreshnessCouldNotBeEstimated() {
        String text = ReportFormatter.format(summary(OptionalLong.empty(), OptionalDouble.empty()));

        assertThat(text).contains("freshness: unavailable").contains("latency: no 202 responses");
    }
}
