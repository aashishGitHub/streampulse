/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.domain;

import java.util.Objects;
import java.util.Set;

/**
 * @param node the stage of the delivery chain this rule watches; the unit of root-cause suppression
 * @param upstream nodes whose failure can explain this rule's failures
 * @param titleId null matches every title
 * @param region null matches every region
 */
public record AlertRule(
        String ruleId,
        String tenantId,
        String name,
        String metric,
        Condition condition,
        Severity severity,
        String node,
        Set<String> upstream,
        String titleId,
        String region) {

    /** A rule with no category takes part in no suppression: no edge reaches or leaves this node. */
    public static final String UNCATEGORISED = "UNCATEGORISED";

    public AlertRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(node, "node");
        upstream = Set.copyOf(upstream);
    }

    public boolean matches(Observation observation) {
        return tenantId.equals(observation.tenantId())
                && metric.equals(observation.metric())
                && (titleId == null || titleId.equals(observation.titleId()))
                && (region == null || region.equals(observation.region()));
    }
}
