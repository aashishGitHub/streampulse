/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import com.streampulse.alert.domain.Condition.Absence;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Silence cannot be seen from an arriving record, so this strategy is driven by the clock.
 *
 * <p>After the last window ends at {@code E} with length {@code L}, window {@code k} is overdue at
 * {@code E + k*L + grace}: the grace is how long the aggregator waits for late events before it
 * publishes a final window, so earlier than that a missing window is not yet missing. A series
 * never seen before has no last window, so it cannot be absent; that limitation is deliberate.
 */
public final class AbsenceEvaluator implements RuleEvaluator<Absence> {

    private final Duration grace;

    public AbsenceEvaluator(Duration grace) {
        this.grace = grace;
    }

    @Override
    public Verdict onObservation(Absence condition, Optional<Observation> previous, Observation current) {
        return Verdict.CLEAR;
    }

    @Override
    public Verdict onTick(Absence condition, Observation last, Instant now) {
        Duration windowLength = Duration.between(last.windowStart(), last.windowEnd());
        Instant overdueAt = last.windowEnd()
                .plus(windowLength.multipliedBy(condition.windows()))
                .plus(grace);
        return now.isBefore(overdueAt) ? Verdict.NO_DECISION : Verdict.BREACHING;
    }
}
