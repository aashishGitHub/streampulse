/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import com.streampulse.alert.domain.Condition.RateOfChange;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Verdict;
import java.util.Optional;

/**
 * Compares a window with the one right before it.
 *
 * <ul>
 *   <li>Only an adjacent predecessor counts ({@code previous.end == current.start}). A silent gap
 *       between two windows is not a rate, so there is no decision.
 *   <li>The change is {@code (current - previous) / |previous|}. From zero to anything non-zero it is
 *       plus or minus infinity, which compares correctly against any fraction; zero to zero is 0.
 *   <li>The fraction is reached, not strictly exceeded: 100 to 150 is a 0.5 rise.
 * </ul>
 */
public final class RateOfChangeEvaluator implements RuleEvaluator<RateOfChange> {

    @Override
    public Verdict onObservation(RateOfChange condition, Optional<Observation> previous, Observation current) {
        if (previous.isEmpty() || !previous.get().windowEnd().equals(current.windowStart())) {
            return Verdict.NO_DECISION;
        }
        double change = relativeChange(previous.get().value(), current.value());
        boolean breaching =
                switch (condition.direction()) {
                    case RISE -> change >= condition.fraction();
                    case FALL -> change <= -condition.fraction();
                    case EITHER -> Math.abs(change) >= condition.fraction();
                };
        return breaching ? Verdict.BREACHING : Verdict.CLEAR;
    }

    static double relativeChange(double previous, double current) {
        if (current == previous) {
            return 0.0;
        }
        return (current - previous) / Math.abs(previous);
    }
}
