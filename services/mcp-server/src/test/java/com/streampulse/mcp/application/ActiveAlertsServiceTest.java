/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import static com.streampulse.mcp.support.TestData.CLOCK;
import static com.streampulse.mcp.support.TestData.NOW;
import static com.streampulse.mcp.support.TestData.firing;
import static com.streampulse.mcp.support.TestData.suppressed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.AlertState;
import com.streampulse.mcp.domain.Backend;
import com.streampulse.mcp.domain.Severity;
import com.streampulse.mcp.support.FakePorts;
import com.streampulse.platform.domain.TenantId;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActiveAlertsServiceTest {

    private static final TenantId ACME = TenantId.of("acme");

    private final FakePorts.Alerts alerts = new FakePorts.Alerts();

    private ActiveAlertsService service(int maxAlerts) {
        return new ActiveAlertsService(alerts, CLOCK, new ToolLimits(maxAlerts, 100, 20, 200, Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("firing and suppressed alerts are separated; a suppressed alert keeps its root cause id")
    void separatesFiringFromSuppressed() {
        alerts.returns = List.of(
                firing("root", "acme", "title-1:us-east-1:ERROR_RATE", NOW.minusSeconds(300)),
                suppressed("child", "acme", "*:*:CONCURRENT_VIEWERS", NOW.minusSeconds(200), "root"));

        ActiveAlerts result = service(50).activeAlerts(ACME);

        assertThat(result.firing()).extracting(AlertSnapshot::alertId).containsExactly("root");
        assertThat(result.suppressed()).extracting(AlertSnapshot::alertId).containsExactly("child");
        assertThat(result.suppressed().get(0).suppressedBy()).isEqualTo("root");
        assertThat(result.omitted()).isZero();
    }

    @Test
    @DisplayName("the backend is asked for the requested tenant only")
    void asksForTheTenant() {
        service(50).activeAlerts(ACME);
        assertThat(alerts.calls).containsExactly(ACME);
    }

    @Test
    @DisplayName("alerts of other tenants are dropped and are not counted")
    void dropsOtherTenants() {
        alerts.returns = List.of(
                firing("mine", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(10)),
                firing("theirs", "rival", "t:r:ERROR_RATE", NOW.minusSeconds(20)),
                suppressed("theirs-2", "rival", "t:r:ERROR_RATE", NOW.minusSeconds(30), "theirs"));

        ActiveAlerts result = service(50).activeAlerts(ACME);

        assertThat(result.firing()).extracting(AlertSnapshot::alertId).containsExactly("mine");
        assertThat(result.suppressed()).isEmpty();
        assertThat(result.firingTotal()).isEqualTo(1);
        assertThat(result.suppressedTotal()).isZero();
        assertThat(result.omitted()).isZero();
    }

    @Test
    @DisplayName("resolved alerts are not active and are left out")
    void ignoresResolved() {
        AlertSnapshot resolved = new AlertSnapshot(
                "old",
                "acme",
                "rule-1",
                "t:r:ERROR_RATE",
                AlertState.RESOLVED,
                Severity.INFO,
                "s",
                NOW.minusSeconds(900),
                NOW.minusSeconds(60),
                null);
        alerts.returns = List.of(resolved);
        assertThat(service(50).activeAlerts(ACME).firing()).isEmpty();
    }

    @Test
    @DisplayName("over the limit: firing first, the cut is counted")
    void truncates() {
        alerts.returns = List.of(
                firing("f1", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(50)),
                firing("f2", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(40)),
                firing("f3", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(30)),
                firing("f4", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(20)),
                firing("f5", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(10)),
                suppressed("s1", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(5), "f1"),
                suppressed("s2", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(4), "f1"));

        ActiveAlerts result = service(3).activeAlerts(ACME);

        assertThat(result.firing()).hasSize(3);
        assertThat(result.suppressed()).isEmpty();
        assertThat(result.firingTotal()).isEqualTo(5);
        assertThat(result.suppressedTotal()).isEqualTo(2);
        assertThat(result.omitted()).isEqualTo(4);
    }

    @Test
    @DisplayName("oldest first, ties by id")
    void ordering() {
        alerts.returns = List.of(
                firing("b", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(10)),
                firing("a", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(10)),
                firing("c", "acme", "t:r:ERROR_RATE", NOW.minusSeconds(100)));
        assertThat(service(50).activeAlerts(ACME).firing())
                .extracting(AlertSnapshot::alertId)
                .containsExactly("c", "a", "b");
    }

    @Test
    @DisplayName("a backend failure propagates; nothing is fabricated")
    void propagatesFailure() {
        alerts.fails = new BackendFailure(Backend.ALERT_ENGINE, BackendFailure.Category.UNREACHABLE, 0);
        assertThatThrownBy(() -> service(50).activeAlerts(ACME))
                .isInstanceOf(BackendFailure.class)
                .hasMessage("alert-engine unreachable");
    }
}
