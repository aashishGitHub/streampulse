/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.AlertStateMachine.Transition;
import com.streampulse.alert.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertStateMachineTest {

    private static final AlertKey KEY = new AlertKey("r", new Series("t", null, null, "M"));
    private static final Instant NOW = Fixtures.T0;

    private static Alert firing() {
        return new Alert(
                KEY, "id", AlertState.FIRING, Severity.CRITICAL, "N", "r", "s", NOW, NOW, NOW, null, null, 1, null);
    }

    private static Alert suppressed() {
        return firing().suppressed("root", NOW);
    }

    private static Alert root() {
        return new Alert(
                new AlertKey("root", KEY.series()),
                "root",
                AlertState.FIRING,
                Severity.CRITICAL,
                "U",
                "r",
                "s",
                NOW,
                NOW,
                NOW,
                null,
                null,
                1,
                null);
    }

    @Test
    @DisplayName("no alert + breaching, no root: open (the Open transition names no cause)")
    void breachOpens() {
        assertThat(AlertStateMachine.next(Optional.empty(), Verdict.BREACHING, Optional.empty()))
                .isEqualTo(new Transition.Open(Optional.empty()));
    }

    @Test
    @DisplayName("no alert + breaching, root firing upstream: open and carry the cause")
    void breachWithRootOpensSuppressed() {
        assertThat(AlertStateMachine.next(Optional.empty(), Verdict.BREACHING, Optional.of(root())))
                .isEqualTo(new Transition.Open(Optional.of(root())));
    }

    @Test
    @DisplayName("firing + breaching: nothing, which is the dedupe")
    void firingStaysQuiet() {
        assertThat(AlertStateMachine.next(Optional.of(firing()), Verdict.BREACHING, Optional.empty()))
                .isEqualTo(Transition.NONE);
    }

    @Test
    @DisplayName("suppressed + breaching: nothing")
    void suppressedStaysQuiet() {
        assertThat(AlertStateMachine.next(Optional.of(suppressed()), Verdict.BREACHING, Optional.of(root())))
                .isEqualTo(Transition.NONE);
    }

    @Test
    @DisplayName("firing or suppressed + clear: resolve")
    void clearResolves() {
        assertThat(AlertStateMachine.next(Optional.of(firing()), Verdict.CLEAR, Optional.empty()))
                .isEqualTo(new Transition.Resolve());
        assertThat(AlertStateMachine.next(Optional.of(suppressed()), Verdict.CLEAR, Optional.empty()))
                .isEqualTo(new Transition.Resolve());
    }

    @Test
    @DisplayName("no alert + clear: nothing")
    void clearWithNothingActive() {
        assertThat(AlertStateMachine.next(Optional.empty(), Verdict.CLEAR, Optional.empty()))
                .isEqualTo(Transition.NONE);
    }

    @Test
    @DisplayName("no decision never changes anything")
    void noDecision() {
        List<Optional<Alert>> actives = List.of(Optional.empty(), Optional.of(firing()), Optional.of(suppressed()));
        for (Optional<Alert> active : actives) {
            assertThat(AlertStateMachine.next(active, Verdict.NO_DECISION, Optional.of(root())))
                    .isEqualTo(Transition.NONE);
        }
    }

    @Test
    @DisplayName("suppressedBy is set exactly while suppressed")
    void invariantOnAlert() {
        assertThat(suppressed().suppressedBy()).isEqualTo("root");
        assertThat(suppressed().firing(NOW).suppressedBy()).isNull();
        assertThat(suppressed().resolved(NOW).suppressedBy()).isNull();
        assertThat(suppressed().resolved(NOW).resolvedAt()).isEqualTo(NOW);
    }
}
