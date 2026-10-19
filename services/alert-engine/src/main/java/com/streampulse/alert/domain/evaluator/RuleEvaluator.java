/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import com.streampulse.alert.domain.Condition;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Verdict;
import java.time.Instant;
import java.util.Optional;

/**
 * Strategy for one kind of {@link Condition}. Stateless: everything it may remember about a
 * series arrives as the {@code previous} observation, so the engine owns state and idempotence.
 */
public interface RuleEvaluator<C extends Condition> {

    /** Called with a window later than any already evaluated for this rule and series. */
    Verdict onObservation(C condition, Optional<Observation> previous, Observation current);

    /** Called periodically with the last window seen, for conditions that depend on silence. */
    default Verdict onTick(C condition, Observation last, Instant now) {
        return Verdict.NO_DECISION;
    }
}
