/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import com.streampulse.alert.domain.AlertMetrics;
import com.streampulse.alert.domain.EvaluationOutcome;
import com.streampulse.alert.domain.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Metric names are what dashboards and alerts are written against. Tags are all bounded sets
 * (rule kinds, outcomes, severities, channels, a handful of tenants); never a rule or alert id.
 */
public class MicrometerAlertMetrics implements AlertMetrics {

    private final MeterRegistry registry;

    public MicrometerAlertMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void evaluated(String conditionKind, EvaluationOutcome outcome) {
        registry.counter(
                        "streampulse.alert.evaluations",
                        "kind",
                        conditionKind,
                        "outcome",
                        outcome.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    @Override
    public void fired(String tenantId, Severity severity) {
        transition("streampulse.alert.fired", tenantId, severity);
    }

    @Override
    public void suppressed(String tenantId, Severity severity) {
        transition("streampulse.alert.suppressed", tenantId, severity);
    }

    @Override
    public void resolved(String tenantId, Severity severity) {
        transition("streampulse.alert.resolved", tenantId, severity);
    }

    @Override
    public void rejected(String source, String reason) {
        registry.counter("streampulse.alert.rejected", "source", source, "reason", reason)
                .increment();
    }

    @Override
    public void ruleApplied(String result) {
        registry.counter("streampulse.alert.rules", "result", result).increment();
    }

    @Override
    public void delivered(String channel) {
        registry.counter("streampulse.alert.deliveries", "channel", channel, "outcome", "ok")
                .increment();
    }

    @Override
    public void deliveryFailed(String channel) {
        registry.counter("streampulse.alert.deliveries", "channel", channel, "outcome", "failed")
                .increment();
    }

    private void transition(String name, String tenantId, Severity severity) {
        registry.counter(name, "tenant", tenantId, "severity", severity.name().toLowerCase(Locale.ROOT))
                .increment();
    }
}
