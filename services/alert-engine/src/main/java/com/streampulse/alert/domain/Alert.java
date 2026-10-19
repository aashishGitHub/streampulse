/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One incident, as of one moment. Immutable; every transition produces a new value.
 *
 * @param windowStart the window in which the incident began; part of the id
 * @param firedAt when the incident began (first breach), unchanged by suppression and release
 * @param changedAt when it entered its current state
 * @param suppressedBy alert id of the upstream root cause, only while SUPPRESSED
 * @param threshold the rule's limit, when it has one
 */
public record Alert(
        AlertKey key,
        String alertId,
        AlertState state,
        Severity severity,
        String node,
        String ruleName,
        String summary,
        Instant windowStart,
        Instant firedAt,
        Instant changedAt,
        Instant resolvedAt,
        String suppressedBy,
        double observedValue,
        Double threshold) {

    public Alert {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(state, "state");
        if ((state == AlertState.SUPPRESSED) != (suppressedBy != null)) {
            throw new IllegalArgumentException("suppressedBy is set exactly when the state is SUPPRESSED");
        }
        if ((state == AlertState.RESOLVED) != (resolvedAt != null)) {
            throw new IllegalArgumentException("resolvedAt is set exactly when the state is RESOLVED");
        }
    }

    public String tenantId() {
        return key.tenantId();
    }

    public boolean isActive() {
        return state != AlertState.RESOLVED;
    }

    public Alert firing(Instant now) {
        return new Alert(
                key,
                alertId,
                AlertState.FIRING,
                severity,
                node,
                ruleName,
                summary,
                windowStart,
                firedAt,
                now,
                null,
                null,
                observedValue,
                threshold);
    }

    public Alert suppressed(String rootAlertId, Instant now) {
        return new Alert(
                key,
                alertId,
                AlertState.SUPPRESSED,
                severity,
                node,
                ruleName,
                summary,
                windowStart,
                firedAt,
                now,
                null,
                rootAlertId,
                observedValue,
                threshold);
    }

    public Alert resolved(Instant now) {
        return new Alert(
                key,
                alertId,
                AlertState.RESOLVED,
                severity,
                node,
                ruleName,
                summary,
                windowStart,
                firedAt,
                now,
                now,
                null,
                observedValue,
                threshold);
    }
}
