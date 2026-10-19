/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import com.streampulse.alert.domain.Condition.Threshold;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Verdict;
import java.util.Optional;

public final class ThresholdEvaluator implements RuleEvaluator<Threshold> {

    @Override
    public Verdict onObservation(Threshold condition, Optional<Observation> previous, Observation current) {
        return condition.comparison().holds(current.value(), condition.limit()) ? Verdict.BREACHING : Verdict.CLEAR;
    }
}
