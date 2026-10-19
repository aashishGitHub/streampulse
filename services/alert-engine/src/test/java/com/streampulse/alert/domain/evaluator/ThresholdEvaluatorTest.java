/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain.evaluator;

import static com.streampulse.alert.support.Fixtures.obs;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.Comparison;
import com.streampulse.alert.domain.Condition.Threshold;
import com.streampulse.alert.domain.Verdict;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ThresholdEvaluatorTest {

    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();

    @ParameterizedTest(name = "{0} {1} limit 10 -> {2}")
    @CsvSource({
        "GT, 10.1, BREACHING",
        "GT, 10.0, CLEAR",
        "GTE, 10.0, BREACHING",
        "GTE, 9.9, CLEAR",
        "LT, 9.9, BREACHING",
        "LT, 10.0, CLEAR",
        "LTE, 10.0, BREACHING",
        "LTE, 10.1, CLEAR"
    })
    void comparisons(Comparison comparison, double value, Verdict expected) {
        assertThat(evaluator.onObservation(
                        new Threshold(comparison, 10), Optional.empty(), obs("ERROR_RATE", 0, value)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("the verdict depends only on the current window, whatever came before")
    void ignoresHistory() {
        var condition = new Threshold(Comparison.GT, 0.05);

        assertThat(evaluator.onObservation(
                        condition, Optional.of(obs("ERROR_RATE", 0, 9.0)), obs("ERROR_RATE", 1, 0.01)))
                .isEqualTo(Verdict.CLEAR);
    }

    @Test
    @DisplayName("a threshold evaluator never decides on a tick")
    void noTickVerdict() {
        assertThat(evaluator.onTick(
                        new Threshold(Comparison.GT, 1),
                        obs("ERROR_RATE", 0, 5),
                        obs("X", 9, 1).windowEnd()))
                .isEqualTo(Verdict.NO_DECISION);
    }
}
