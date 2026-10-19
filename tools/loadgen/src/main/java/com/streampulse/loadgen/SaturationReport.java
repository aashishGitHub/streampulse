/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public record SaturationReport(
        List<StepResult> steps,
        OptionalLong lastCleanRate,
        OptionalLong saturatedAtRate,
        Optional<Cause> cause,
        OptionalLong firstClientLimitedRate) {

    /** QUOTA is the per-tenant limit working as designed; CAPACITY is the service running out. */
    public enum Cause {
        QUOTA,
        CAPACITY,
        OTHER
    }
}
