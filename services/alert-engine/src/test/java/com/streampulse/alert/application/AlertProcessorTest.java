/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import static com.streampulse.alert.support.Fixtures.T0;
import static com.streampulse.alert.support.Fixtures.errorRateAbove;
import static com.streampulse.alert.support.Fixtures.obs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.AlertMetrics;
import com.streampulse.alert.domain.AlertState;
import com.streampulse.alert.domain.RuleBook;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertProcessorTest {

    private final List<Alert> accepted = new ArrayList<>();
    private boolean down;
    private final AlertChannel channel = new AlertChannel() {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public void deliver(Alert alert) {
            if (down) {
                throw new DeliveryException("down", null);
            }
            accepted.add(alert);
        }
    };

    private final RuleBook rules = new RuleBook();
    private final AlertEngine engine =
            new AlertEngine(rules, new Evaluators(Duration.ofSeconds(5)), new MutableClock(T0), AlertMetrics.NONE);
    private final AlertProcessor processor =
            new AlertProcessor(engine, new AlertOutbox(List.of(channel), AlertMetrics.NONE));

    @BeforeEach
    void seedRule() {
        rules.put(errorRateAbove("r1", 0.05, "CDN"), 1);
    }

    @Test
    @DisplayName("a breach is evaluated and delivered")
    void processDelivers() {
        processor.process(obs("ERROR_RATE", 0, 0.2));

        assertThat(accepted).extracting(Alert::state).containsExactly(AlertState.FIRING);
    }

    @Test
    @DisplayName("a delivery failure surfaces, and the redelivered duplicate aggregate finishes the delivery")
    void redeliveryAfterFailureStillDelivers() {
        var window = obs("ERROR_RATE", 0, 0.2);
        down = true;
        assertThatThrownBy(() -> processor.process(window)).isInstanceOf(DeliveryException.class);
        assertThat(accepted).isEmpty();

        down = false;
        processor.process(window);

        assertThat(accepted)
                .as("the engine treats the redelivery as a duplicate, yet the alert is delivered exactly once")
                .hasSize(1);
        processor.process(window);
        assertThat(accepted).hasSize(1);
    }

    @Test
    @DisplayName("removing a rule resolves its alerts and delivers the resolution")
    void ruleRemoved() {
        processor.process(obs("ERROR_RATE", 0, 0.2));

        processor.ruleRemoved(rules.remove("r1").orElseThrow());

        assertThat(accepted).extracting(Alert::state).containsExactly(AlertState.FIRING, AlertState.RESOLVED);
        assertThat(processor.activeAlerts("streamflix")).isEmpty();
    }
}
