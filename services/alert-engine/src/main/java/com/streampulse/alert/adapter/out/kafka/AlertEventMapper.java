/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.out.kafka;

import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertState;
import com.streampulse.alert.domain.Severity;
import com.streampulse.contracts.v1.AlertCategory;
import com.streampulse.contracts.v1.AlertEvent;

/** Domain alert to the {@code alerts.v1} contract. */
public final class AlertEventMapper {

    public AlertEvent toAvro(Alert alert) {
        return AlertEvent.newBuilder()
                .setAlertId(alert.alertId())
                .setTenantId(alert.tenantId())
                .setRuleId(alert.key().ruleId())
                .setRuleName(alert.ruleName())
                .setState(state(alert.state()))
                .setSeverity(severity(alert.severity()))
                .setCategory(category(alert.node()))
                .setTitleId(alert.key().series().titleId())
                .setRegion(alert.key().series().region())
                .setObservedValue(alert.observedValue())
                .setThreshold(alert.threshold())
                .setDescription(alert.summary())
                .setWindowStart(alert.windowStart())
                .setRaisedAt(alert.changedAt())
                .setSuppressedBy(alert.suppressedBy())
                .build();
    }

    private static com.streampulse.contracts.v1.AlertState state(AlertState state) {
        return switch (state) {
            case FIRING -> com.streampulse.contracts.v1.AlertState.FIRING;
            case RESOLVED -> com.streampulse.contracts.v1.AlertState.RESOLVED;
            case SUPPRESSED -> com.streampulse.contracts.v1.AlertState.SUPPRESSED;
        };
    }

    private static com.streampulse.contracts.v1.Severity severity(Severity severity) {
        return switch (severity) {
            case INFO -> com.streampulse.contracts.v1.Severity.INFO;
            case WARNING -> com.streampulse.contracts.v1.Severity.WARNING;
            case CRITICAL -> com.streampulse.contracts.v1.Severity.CRITICAL;
        };
    }

    /** A node the contract has no category for is reported as unknown rather than dropped. */
    private static AlertCategory category(String node) {
        try {
            return AlertCategory.valueOf(node);
        } catch (IllegalArgumentException e) {
            return AlertCategory.CATEGORY_UNKNOWN;
        }
    }
}
