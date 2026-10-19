/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.ArrayList;
import java.util.List;

public record RampPlan(long startRate, long step, long ceiling, long stepSeconds) {

    public static final long DEFAULT_STEP = 500;
    public static final long DEFAULT_STEP_SECONDS = 20;

    public RampPlan {
        if (step < 1) {
            throw new ArgumentException("--ramp-step must be at least 1, got " + step);
        }
        if (stepSeconds < 1) {
            throw new ArgumentException("--step-duration must be at least 1, got " + stepSeconds);
        }
        if (ceiling < startRate) {
            throw new ArgumentException("--ramp-to (" + ceiling + ") must not be below --rate (" + startRate + ")");
        }
    }

    /** The ceiling is always a step, even off the grid: a requested rate that is never tried is a silent lie. */
    public List<Long> rates() {
        List<Long> rates = new ArrayList<>();
        for (long rate = startRate; rate < ceiling; rate += step) {
            rates.add(rate);
        }
        rates.add(ceiling);
        return List.copyOf(rates);
    }
}
