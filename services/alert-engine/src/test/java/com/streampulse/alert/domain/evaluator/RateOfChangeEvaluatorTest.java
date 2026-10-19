/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import static com.streampulse.alert.support.Fixtures.obs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.Condition.RateOfChange;
import com.streampulse.alert.domain.Condition.RateOfChange.Direction;
import com.streampulse.alert.domain.Verdict;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateOfChangeEvaluatorTest {

    private static final String M = "CONCURRENT_VIEWERS";
    private final RateOfChangeEvaluator evaluator = new RateOfChangeEvaluator();

    private Verdict evaluate(Direction direction, double fraction, double previous, double current) {
        return evaluator.onObservation(
                new RateOfChange(direction, fraction), Optional.of(obs(M, 4, previous)), obs(M, 5, current));
    }

    @ParameterizedTest(name = "{0} 50%: {1} -> {2} is {3}")
    @CsvSource({
        "RISE, 100, 150, BREACHING",
        "RISE, 100, 149, CLEAR",
        "RISE, 100, 40, CLEAR",
        "FALL, 100, 50, BREACHING",
        "FALL, 100, 51, CLEAR",
        "FALL, 100, 200, CLEAR",
        "EITHER, 100, 50, BREACHING",
        "EITHER, 100, 150, BREACHING",
        "EITHER, 100, 110, CLEAR"
    })
    void directions(Direction direction, double previous, double current, Verdict expected) {
        assertThat(evaluate(direction, 0.5, previous, current)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the change is relative to the magnitude of a negative previous value")
    void negativePrevious() {
        assertThat(evaluate(Direction.RISE, 0.5, -100, -50)).isEqualTo(Verdict.BREACHING);
    }

    @Test
    @DisplayName("from zero to something is an unbounded rise; zero to zero is no change")
    void fromZero() {
        assertThat(evaluate(Direction.RISE, 0.5, 0, 0.001)).isEqualTo(Verdict.BREACHING);
        assertThat(evaluate(Direction.FALL, 0.5, 0, 0.001)).isEqualTo(Verdict.CLEAR);
        assertThat(evaluate(Direction.EITHER, 0.5, 0, 0)).isEqualTo(Verdict.CLEAR);
    }

    @Test
    @DisplayName("with no previous window there is no decision")
    void noPrevious() {
        assertThat(evaluator.onObservation(new RateOfChange(Direction.EITHER, 0.1), Optional.empty(), obs(M, 5, 1)))
                .isEqualTo(Verdict.NO_DECISION);
    }

    @Test
    @DisplayName("a previous window that is not adjacent is not compared: a gap is not a rate")
    void gapIsNotARate() {
        assertThat(evaluator.onObservation(
                        new RateOfChange(Direction.EITHER, 0.1), Optional.of(obs(M, 2, 100)), obs(M, 5, 1)))
                .isEqualTo(Verdict.NO_DECISION);
    }
}
