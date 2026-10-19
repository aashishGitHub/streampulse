/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RampPlanTest {

    @Test
    void stepsFromStartInEqualIncrementsUpToTheCeiling() {
        assertThat(new RampPlan(1000, 1000, 3000, 20).rates()).containsExactly(1000L, 2000L, 3000L);
    }

    @Test
    void aCeilingOffTheGridIsStillTestedAsTheLastStep() {
        assertThat(new RampPlan(1000, 1000, 2500, 20).rates()).containsExactly(1000L, 2000L, 2500L);
    }

    @Test
    void aCeilingEqualToTheStartIsASingleStep() {
        assertThat(new RampPlan(500, 100, 500, 20).rates()).containsExactly(500L);
    }

    @Test
    void rejectsACeilingBelowTheStart() {
        assertThatThrownBy(() -> new RampPlan(2000, 500, 1000, 20))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("ramp-to");
    }

    @Test
    void rejectsANonPositiveStepOrStepDuration() {
        assertThatThrownBy(() -> new RampPlan(1000, 0, 2000, 20))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("ramp-step");
        assertThatThrownBy(() -> new RampPlan(1000, 500, 2000, 0))
                .isInstanceOf(ArgumentException.class)
                .hasMessageContaining("step-duration");
    }
}
