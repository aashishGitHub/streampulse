/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public final class SaturationFinder {

    /** REQ-RL-01. A target for what counts as "saturated"; the measured rate is the deliverable. */
    static final double ERROR_RATE_LIMIT = 0.01;

    private SaturationFinder() {}

    public static boolean breaches(StepResult step) {
        return step.errorRate() > ERROR_RATE_LIMIT;
    }

    public static SaturationReport analyse(List<StepResult> steps) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("no steps to analyse");
        }
        OptionalLong lastClean = OptionalLong.empty();
        OptionalLong saturatedAt = OptionalLong.empty();
        Optional<SaturationReport.Cause> cause = Optional.empty();
        OptionalLong firstClientLimited = OptionalLong.empty();

        for (StepResult step : steps) {
            if (firstClientLimited.isEmpty() && step.clientLimited()) {
                firstClientLimited = OptionalLong.of(step.targetRate());
            }
            if (saturatedAt.isEmpty()) {
                if (breaches(step)) {
                    saturatedAt = OptionalLong.of(step.targetRate());
                    cause = Optional.of(causeOf(step.summary()));
                } else {
                    lastClean = OptionalLong.of(step.targetRate());
                }
            }
        }
        return new SaturationReport(List.copyOf(steps), lastClean, saturatedAt, cause, firstClientLimited);
    }

    private static SaturationReport.Cause causeOf(RunSummary s) {
        long quota = s.status429();
        long capacity = s.status503();
        long other = s.statusOther() + s.transportFailures();
        if (capacity >= quota && capacity >= other) {
            return SaturationReport.Cause.CAPACITY;
        }
        return quota >= other ? SaturationReport.Cause.QUOTA : SaturationReport.Cause.OTHER;
    }
}
