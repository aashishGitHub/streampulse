/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import static com.streampulse.alert.support.Fixtures.WINDOW;
import static com.streampulse.alert.support.Fixtures.obs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.Condition.Absence;
import com.streampulse.alert.domain.Observation;
import com.streampulse.alert.domain.Verdict;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AbsenceEvaluatorTest {

    private static final Duration GRACE = Duration.ofSeconds(5);
    private final AbsenceEvaluator evaluator = new AbsenceEvaluator(GRACE);
    private final Observation last = obs("CONCURRENT_VIEWERS", 3, 42);

    @Test
    @DisplayName("arriving data is the opposite of absence")
    void dataIsClear() {
        assertThat(evaluator.onObservation(new Absence(3), Optional.empty(), last))
                .isEqualTo(Verdict.CLEAR);
    }

    @Test
    @DisplayName("before N windows plus the settling grace have passed there is no decision")
    void notYetOverdue() {
        var overdueAt = last.windowEnd().plus(WINDOW.multipliedBy(3)).plus(GRACE);

        assertThat(evaluator.onTick(new Absence(3), last, overdueAt.minusMillis(1)))
                .isEqualTo(Verdict.NO_DECISION);
    }

    @Test
    @DisplayName("from the moment N windows plus grace have passed it is breaching")
    void overdue() {
        var overdueAt = last.windowEnd().plus(WINDOW.multipliedBy(3)).plus(GRACE);

        assertThat(evaluator.onTick(new Absence(3), last, overdueAt)).isEqualTo(Verdict.BREACHING);
        assertThat(evaluator.onTick(new Absence(3), last, overdueAt.plusSeconds(600)))
                .isEqualTo(Verdict.BREACHING);
    }

    @Test
    @DisplayName("the allowance scales with the window length of the series")
    void scalesWithWindow() {
        Observation minute = new Observation(
                "t", null, null, "M", last.windowStart(), last.windowStart().plusSeconds(60), 1);

        assertThat(evaluator.onTick(new Absence(2), minute, minute.windowEnd().plusSeconds(124)))
                .isEqualTo(Verdict.NO_DECISION);
        assertThat(evaluator.onTick(new Absence(2), minute, minute.windowEnd().plusSeconds(125)))
                .isEqualTo(Verdict.BREACHING);
    }
}
