/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import com.streampulse.alert.domain.Condition;
import com.streampulse.alert.domain.Condition.Absence;
import com.streampulse.alert.domain.Condition.RateOfChange;
import com.streampulse.alert.domain.Condition.Threshold;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Picks the strategy for a condition. A switch over a sealed type rather than a registry map: the
 * compiler fails the build when a new {@link Condition} has no strategy.
 */
public final class Evaluators {

    private final ThresholdEvaluator threshold = new ThresholdEvaluator();
    private final RateOfChangeEvaluator rateOfChange = new RateOfChangeEvaluator();
    private final AbsenceEvaluator absence;

    public Evaluators(Duration absenceGrace) {
        this.absence = new AbsenceEvaluator(absenceGrace);
    }

    public Verdict onObservation(Condition condition, Optional<Observation> previous, Observation current) {
        return switch (condition) {
            case Threshold t -> threshold.onObservation(t, previous, current);
            case RateOfChange r -> rateOfChange.onObservation(r, previous, current);
            case Absence a -> absence.onObservation(a, previous, current);
        };
    }

    public Verdict onTick(Condition condition, Observation last, Instant now) {
        return switch (condition) {
            case Threshold t -> threshold.onTick(t, last, now);
            case RateOfChange r -> rateOfChange.onTick(r, last, now);
            case Absence a -> absence.onTick(a, last, now);
        };
    }
}
