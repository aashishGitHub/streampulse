/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RampRunner {

    @FunctionalInterface
    public interface StepExecutor {
        RunSummary run(long ratePerSecond, long seconds) throws IOException, InterruptedException;
    }

    private RampRunner() {}

    /** Stops at the first breach: pushing a server that is already failing harder measures nothing new. */
    public static SaturationReport run(RampPlan plan, StepExecutor executor) throws IOException, InterruptedException {
        List<StepResult> results = new ArrayList<>();
        for (long rate : plan.rates()) {
            StepResult result = new StepResult(rate, executor.run(rate, plan.stepSeconds()));
            results.add(result);
            if (SaturationFinder.breaches(result)) {
                break;
            }
        }
        return SaturationFinder.analyse(results);
    }
}
