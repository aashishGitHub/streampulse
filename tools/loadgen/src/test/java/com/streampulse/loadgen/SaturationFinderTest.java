/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SaturationFinderTest {

    private static final int BATCH = 100;

    /** 20 s step; {@code batches} is also what the target rate asks for, so the client is not the limit. */
    private static StepResult step(long rate, long batches, long r429, long r503, long other, long io) {
        return stepWithWall(rate, Duration.ofSeconds(20), batches, r429, r503, other, io);
    }

    private static StepResult stepWithWall(
            long rate, Duration wall, long batches, long r429, long r503, long other, long io) {
        long ok = batches - r429 - r503 - other - io;
        RunSummary summary = new RunSummary(
                wall,
                batches,
                batches * BATCH,
                ok * BATCH,
                ok,
                r429,
                r503,
                other,
                io,
                OptionalLong.of(1_000_000),
                OptionalLong.of(2_000_000),
                OptionalLong.of(3_000_000),
                0,
                Duration.ofMillis(5),
                OptionalDouble.empty(),
                "");
        return new StepResult(rate, summary);
    }

    @Test
    void noStepOverTheLimitMeansNoSaturationAndTheHighestRateIsClean() {
        SaturationReport report = SaturationFinder.analyse(
                List.of(step(1000, 200, 0, 0, 0, 0), step(2000, 400, 0, 0, 0, 0), step(3000, 600, 0, 0, 0, 0)));

        assertThat(report.saturatedAtRate()).isEmpty();
        assertThat(report.lastCleanRate()).hasValue(3000);
        assertThat(report.cause()).isEmpty();
    }

    @Test
    void reportsTheFirstStepWhoseErrorRateExceedsOnePercentAndTheCleanStepBefore() {
        SaturationReport report = SaturationFinder.analyse(
                List.of(step(1000, 200, 0, 0, 0, 0), step(2000, 400, 0, 20, 0, 0), step(3000, 600, 0, 300, 0, 0)));

        assertThat(report.saturatedAtRate()).hasValue(2000);
        assertThat(report.lastCleanRate()).hasValue(1000);
    }

    @Test
    void exactlyOnePercentIsNotABreachButJustOverIs() {
        assertThat(SaturationFinder.breaches(step(1000, 200, 0, 2, 0, 0))).isFalse();
        assertThat(SaturationFinder.breaches(step(1000, 200, 0, 3, 0, 0))).isTrue();
    }

    @Test
    void aFirstStepThatAlreadyBreachesHasNoCleanRate() {
        SaturationReport report = SaturationFinder.analyse(List.of(step(1000, 200, 0, 100, 0, 0)));

        assertThat(report.saturatedAtRate()).hasValue(1000);
        assertThat(report.lastCleanRate()).isEmpty();
    }

    @Test
    void namesCapacityWhenServiceUnavailableDominatesTheBreach() {
        SaturationReport report = SaturationFinder.analyse(List.of(step(2000, 400, 5, 60, 1, 0)));

        assertThat(report.cause()).hasValue(SaturationReport.Cause.CAPACITY);
    }

    @Test
    void namesQuotaWhenTooManyRequestsDominatesTheBreach() {
        SaturationReport report = SaturationFinder.analyse(List.of(step(2000, 400, 60, 5, 1, 0)));

        assertThat(report.cause()).hasValue(SaturationReport.Cause.QUOTA);
    }

    @Test
    void transportFailuresAndUnexpectedStatusesCountAsErrorsAndAreNamedOther() {
        SaturationReport report = SaturationFinder.analyse(List.of(step(2000, 400, 1, 1, 10, 50)));

        assertThat(report.saturatedAtRate()).hasValue(2000);
        assertThat(report.cause()).hasValue(SaturationReport.Cause.OTHER);
    }

    @Test
    void flagsAStepWhereTheClientCouldNotOfferTheRateItWasAskedFor() {
        // 3000 events/s for 20 s asks for 600 batches; only 300 went out, so the client, not the server, ran out first
        StepResult limited = step(3000, 300, 0, 0, 0, 0);

        assertThat(limited.clientLimited()).isTrue();
        assertThat(SaturationFinder.analyse(List.of(step(1000, 200, 0, 0, 0, 0), limited))
                        .firstClientLimitedRate())
                .hasValue(3000);
    }

    @Test
    void aStepThatOfferedWhatWasAskedIsNotClientLimited() {
        assertThat(step(1000, 200, 0, 0, 0, 0).clientLimited()).isFalse();
    }

    @Test
    void rejectsAnEmptyRun() {
        assertThatThrownBy(() -> SaturationFinder.analyse(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
