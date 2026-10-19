/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.mcp.application;

import com.streampulse.mcp.application.port.AlertsPort;
import com.streampulse.mcp.domain.AlertSnapshot;
import com.streampulse.mcp.domain.AlertState;
import com.streampulse.platform.domain.TenantId;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;

public final class ActiveAlertsService {

    private static final Comparator<AlertSnapshot> OLDEST_FIRST =
            Comparator.comparing(AlertSnapshot::firedAt).thenComparing(AlertSnapshot::alertId);

    private final AlertsPort alerts;
    private final Clock clock;
    private final ToolLimits limits;

    public ActiveAlertsService(AlertsPort alerts, Clock clock, ToolLimits limits) {
        this.alerts = alerts;
        this.clock = clock;
        this.limits = limits;
    }

    public ActiveAlerts activeAlerts(TenantId tenant) {
        List<AlertSnapshot> own = TenantScope.only(tenant, alerts.activeAlerts(tenant));
        List<AlertSnapshot> firing = of(own, AlertState.FIRING);
        List<AlertSnapshot> suppressed = of(own, AlertState.SUPPRESSED);

        // firing first: the cut must never hide a root cause behind its own symptoms
        List<AlertSnapshot> shownFiring =
                firing.stream().limit(limits.maxAlerts()).toList();
        int room = limits.maxAlerts() - shownFiring.size();
        List<AlertSnapshot> shownSuppressed = suppressed.stream().limit(room).toList();
        int omitted = firing.size() + suppressed.size() - shownFiring.size() - shownSuppressed.size();

        return new ActiveAlerts(
                tenant, clock.instant(), shownFiring, shownSuppressed, firing.size(), suppressed.size(), omitted);
    }

    private static List<AlertSnapshot> of(List<AlertSnapshot> alerts, AlertState state) {
        return alerts.stream()
                .filter(a -> a.state() == state)
                .sorted(OLDEST_FIRST)
                .toList();
    }
}
