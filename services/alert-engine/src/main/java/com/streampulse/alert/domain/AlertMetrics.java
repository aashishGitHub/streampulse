/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

/** Port for counters. The domain reports facts; the wiring decides names and tags. */
public interface AlertMetrics {

    AlertMetrics NONE = new AlertMetrics() {};

    default void evaluated(String conditionKind, EvaluationOutcome outcome) {}

    /** An alert entered FIRING, whether newly opened or released from suppression. */
    default void fired(String tenantId, Severity severity) {}

    default void suppressed(String tenantId, Severity severity) {}

    default void resolved(String tenantId, Severity severity) {}

    /** An input record was dropped. {@code source} is "aggregate" or "config"; {@code reason} is a short code. */
    default void rejected(String source, String reason) {}

    default void ruleApplied(String result) {}

    default void delivered(String channel) {}

    default void deliveryFailed(String channel) {}
}
