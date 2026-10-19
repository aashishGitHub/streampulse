/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import static com.streampulse.alert.support.Fixtures.T0;
import static com.streampulse.alert.support.Fixtures.TENANT;
import static com.streampulse.alert.support.Fixtures.WINDOW;
import static com.streampulse.alert.support.Fixtures.errorRateAbove;
import static com.streampulse.alert.support.Fixtures.obs;
import static com.streampulse.alert.support.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.Condition.RateOfChange;
import com.streampulse.alert.domain.Condition.RateOfChange.Direction;
import com.streampulse.alert.domain.evaluator.Evaluators;
import com.streampulse.alert.support.MutableClock;
import com.streampulse.alert.support.RecordingMetrics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertEngineTest {

    private static final Duration GRACE = Duration.ofSeconds(5);

    private final RuleBook rules = new RuleBook();
    private final MutableClock clock = new MutableClock(T0);
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final AlertEngine engine = new AlertEngine(rules, new Evaluators(GRACE), clock, metrics);

    @BeforeEach
    void seedRule() {
        rules.put(errorRateAbove("r1", 0.05, "CDN"), 1);
    }

    @Test
    @DisplayName("a breach opens one FIRING alert with the shape the outputs need")
    void breachFires() {
        var changes = engine.evaluate(obs("ERROR_RATE", 0, 0.2));

        assertThat(changes).hasSize(1);
        Alert alert = changes.get(0);
        assertThat(alert.state()).isEqualTo(AlertState.FIRING);
        assertThat(alert.tenantId()).isEqualTo(TENANT);
        assertThat(alert.key().ruleId()).isEqualTo("r1");
        assertThat(alert.key().series().label()).isEqualTo("title-1:us-east-1:ERROR_RATE");
        assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alert.firedAt()).isEqualTo(T0);
        assertThat(alert.windowStart()).isEqualTo(T0);
        assertThat(alert.observedValue()).isEqualTo(0.2);
        assertThat(alert.threshold()).isEqualTo(0.05);
        assertThat(alert.summary()).contains("title-1:us-east-1:ERROR_RATE").contains("0.2");
        assertThat(engine.activeAlerts(TENANT)).containsExactly(alert);
    }

    @Test
    @DisplayName("a repeated breach while firing does not notify again")
    void repeatedBreachDoesNotRefire() {
        engine.evaluate(obs("ERROR_RATE", 0, 0.2));

        assertThat(engine.evaluate(obs("ERROR_RATE", 1, 0.3))).isEmpty();
        assertThat(engine.evaluate(obs("ERROR_RATE", 2, 0.9))).isEmpty();
        assertThat(engine.activeAlerts(TENANT)).hasSize(1);
    }

    @Test
    @DisplayName("a clear window resolves, keeps the incident id, and forgets the alert")
    void clearResolves() {
        Alert fired = engine.evaluate(obs("ERROR_RATE", 0, 0.2)).get(0);
        clock.advance(Duration.ofSeconds(20));

        var changes = engine.evaluate(obs("ERROR_RATE", 1, 0.01));

        assertThat(changes).hasSize(1);
        Alert resolved = changes.get(0);
        assertThat(resolved.state()).isEqualTo(AlertState.RESOLVED);
        assertThat(resolved.alertId()).isEqualTo(fired.alertId());
        assertThat(resolved.resolvedAt()).isEqualTo(T0.plusSeconds(20));
        assertThat(resolved.firedAt()).isEqualTo(T0);
        assertThat(engine.activeAlerts(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("a clear window with nothing active changes nothing")
    void clearWithNothingActive() {
        assertThat(engine.evaluate(obs("ERROR_RATE", 0, 0.01))).isEmpty();
    }

    @Test
    @DisplayName("a breach after a resolve is a new incident with a new id")
    void refireIsANewIncident() {
        Alert first = engine.evaluate(obs("ERROR_RATE", 0, 0.2)).get(0);
        engine.evaluate(obs("ERROR_RATE", 1, 0.01));

        Alert second = engine.evaluate(obs("ERROR_RATE", 2, 0.4)).get(0);

        assertThat(second.state()).isEqualTo(AlertState.FIRING);
        assertThat(second.alertId()).isNotEqualTo(first.alertId());
    }

    @Test
    @DisplayName("a redelivered aggregate changes nothing: no second alert, no second state change")
    void duplicateAggregateChangesNothing() {
        var window = obs("ERROR_RATE", 0, 0.2);
        engine.evaluate(window);

        assertThat(engine.evaluate(window)).isEmpty();
        assertThat(engine.activeAlerts(TENANT)).hasSize(1);
        assertThat(metrics.events).contains("evaluated:threshold:DUPLICATE");
        assertThat(metrics.events.stream().filter(e -> e.startsWith("fired")).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate of a CLEAR window after a re-breach cannot undo it")
    void duplicateOfOldClearDoesNotResolveNewBreach() {
        engine.evaluate(obs("ERROR_RATE", 0, 0.2));
        engine.evaluate(obs("ERROR_RATE", 1, 0.01));
        engine.evaluate(obs("ERROR_RATE", 2, 0.4));

        assertThat(engine.evaluate(obs("ERROR_RATE", 1, 0.01))).isEmpty();
        assertThat(engine.activeAlerts(TENANT)).hasSize(1);
        assertThat(metrics.events).contains("evaluated:threshold:STALE");
    }

    @Test
    @DisplayName("a window older than the latest one is ignored, not re-evaluated")
    void olderWindowIsIgnored() {
        engine.evaluate(obs("ERROR_RATE", 5, 0.01));

        assertThat(engine.evaluate(obs("ERROR_RATE", 3, 0.9))).isEmpty();
        assertThat(engine.activeAlerts(TENANT)).isEmpty();
    }

    @Test
    @DisplayName("the incident id is a pure function of tenant, rule, series and starting window")
    void incidentIdIsDeterministic() {
        var other = new AlertEngine(rules, new Evaluators(GRACE), new MutableClock(T0.plusSeconds(999)), metrics);

        String a = engine.evaluate(obs("ERROR_RATE", 0, 0.2)).get(0).alertId();
        String b = other.evaluate(obs("ERROR_RATE", 0, 0.2)).get(0).alertId();
        engine.evaluate(obs("ERROR_RATE", 1, 0.0));
        String laterIncident = engine.evaluate(obs("ERROR_RATE", 7, 0.2)).get(0).alertId();

        assertThat(a).isEqualTo(b);
        assertThat(laterIncident).isNotEqualTo(a);
    }

    @Test
    @DisplayName("each series has its own alert: two titles breaching are two incidents with distinct ids")
    void seriesAreIndependent() {
        var t1 = new Observation(TENANT, "title-1", "us-east-1", "ERROR_RATE", T0, T0.plus(WINDOW), 0.5);
        var t2 = new Observation(TENANT, "title-2", "us-east-1", "ERROR_RATE", T0, T0.plus(WINDOW), 0.5);

        engine.evaluate(t1);
        engine.evaluate(t2);

        assertThat(engine.activeAlerts(TENANT))
                .extracting(Alert::alertId)
                .doesNotHaveDuplicates()
                .hasSize(2);
        engine.evaluate(new Observation(
                TENANT, "title-1", "us-east-1", "ERROR_RATE", T0.plus(WINDOW), T0.plus(WINDOW.multipliedBy(2)), 0.0));
        assertThat(engine.activeAlerts(TENANT)).hasSize(1);
    }

    @Test
    @DisplayName("every rule that matches an observation is evaluated")
    void severalRulesMatch() {
        rules.put(errorRateAbove("r2", 0.5, "PLAYER"), 1);

        var changes = engine.evaluate(obs("ERROR_RATE", 0, 0.9));

        assertThat(changes).extracting(a -> a.key().ruleId()).containsExactlyInAnyOrder("r1", "r2");
    }

    @Test
    @DisplayName("rate of change fires on the second adjacent window and resolves when the change stops")
    void rateOfChange() {
        rules.put(rule("drop", TENANT, "CONCURRENT_VIEWERS", new RateOfChange(Direction.FALL, 0.4), "PLAYER"), 1);

        assertThat(engine.evaluate(obs("CONCURRENT_VIEWERS", 0, 1000))).isEmpty();
        var fired = engine.evaluate(obs("CONCURRENT_VIEWERS", 1, 500));
        assertThat(fired).extracting(Alert::state).containsExactly(AlertState.FIRING);

        var resolved = engine.evaluate(obs("CONCURRENT_VIEWERS", 2, 480));
        assertThat(resolved).extracting(Alert::state).containsExactly(AlertState.RESOLVED);
    }

    @Test
    @DisplayName("absence fires from the clock, deduplicates on later sweeps, and resolves when data returns")
    void absence() {
        rules.put(rule("silent", TENANT, "CONCURRENT_VIEWERS", new Condition.Absence(2), "INGEST"), 1);
        engine.evaluate(obs("CONCURRENT_VIEWERS", 0, 10));

        clock.set(T0.plus(WINDOW).plus(WINDOW.multipliedBy(2)).plus(GRACE).minusMillis(1));
        assertThat(engine.sweep()).isEmpty();

        clock.advance(Duration.ofMillis(1));
        var fired = engine.sweep();
        assertThat(fired).extracting(Alert::state).containsExactly(AlertState.FIRING);
        assertThat(fired.get(0).windowStart()).isEqualTo(T0.plus(WINDOW));

        clock.advance(Duration.ofMinutes(5));
        assertThat(engine.sweep()).as("still absent, still the same incident").isEmpty();

        var resolved = engine.evaluate(obs("CONCURRENT_VIEWERS", 40, 12));
        assertThat(resolved).extracting(Alert::state).containsExactly(AlertState.RESOLVED);
        assertThat(resolved.get(0).alertId()).isEqualTo(fired.get(0).alertId());
    }

    @Test
    @DisplayName("sweeping never touches threshold rules")
    void sweepIgnoresOtherKinds() {
        engine.evaluate(obs("ERROR_RATE", 0, 0.01));
        clock.advance(Duration.ofDays(1));

        assertThat(engine.sweep()).isEmpty();
    }

    @Test
    @DisplayName("retiring a rule resolves its alerts and forgets its cursors, so a new rule starts clean")
    void retireResolvesAndForgets() {
        engine.evaluate(obs("ERROR_RATE", 0, 0.2));
        AlertRule removed = rules.remove("r1").orElseThrow();

        List<Alert> changes = engine.retire(removed);

        assertThat(changes).extracting(Alert::state).containsExactly(AlertState.RESOLVED);
        assertThat(engine.activeAlerts(TENANT)).isEmpty();

        rules.put(errorRateAbove("r1", 0.05, "CDN"), 2);
        assertThat(engine.evaluate(obs("ERROR_RATE", 0, 0.2)))
                .as("window 0 is accepted again because the cursor was dropped")
                .hasSize(1);
    }

    @Test
    @DisplayName("counters: evaluations, fired and resolved are reported with their tags")
    void metricsAreReported() {
        engine.evaluate(obs("ERROR_RATE", 0, 0.2));
        engine.evaluate(obs("ERROR_RATE", 1, 0.0));

        assertThat(metrics.events)
                .containsSubsequence(
                        "evaluated:threshold:BREACHING",
                        "fired:" + TENANT + ":CRITICAL",
                        "evaluated:threshold:CLEAR",
                        "resolved:" + TENANT + ":CRITICAL");
    }
}
