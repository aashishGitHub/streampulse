/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import com.streampulse.alert.domain.Alert;
import java.time.Instant;

/**
 * The JSON shape of an alert, shared by the REST list, the SSE stream and the queue message, and
 * pinned by api/openapi/alerts-v1.yaml. State and severity are strings so the wire contract is
 * these exact names, not whatever the domain enum is called next year.
 */
public record AlertView(
        String alertId,
        String tenantId,
        String ruleId,
        String series,
        String state,
        String severity,
        String summary,
        Instant firedAt,
        Instant resolvedAt,
        String suppressedBy) {

    public static AlertView from(Alert alert) {
        return new AlertView(
                alert.alertId(),
                alert.tenantId(),
                alert.key().ruleId(),
                alert.key().series().label(),
                alert.state().name(),
                alert.severity().name(),
                alert.summary(),
                alert.firedAt(),
                alert.resolvedAt(),
                alert.suppressedBy());
    }
}
