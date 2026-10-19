/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code category} is this rule's node in the service dependency graph and {@code dependsOn} its
 * upstream nodes. Comparison and severity default to GT and WARNING when the client omits them.
 */
public record AlertRule(
        String ruleId,
        TenantId tenantId,
        String metric,
        Comparison comparison,
        double threshold,
        AlertSeverity severity,
        Category category,
        Set<Category> dependsOn,
        long version)
        implements ConfigEntity {

    /** The alert engine reads {@code kind} as the condition type; this service only authors thresholds. */
    private static final String CONDITION_KIND = "THRESHOLD";

    public AlertRule {
        Ids.entityId("ruleId", ruleId);
        if (tenantId == null) {
            throw new InvalidInputException("tenantId must not be null");
        }
        Ids.requireText("metric", metric, 120);
        comparison = comparison == null ? Comparison.GT : comparison;
        severity = severity == null ? AlertSeverity.WARNING : severity;
        if (!Double.isFinite(threshold)) {
            throw new InvalidInputException("threshold must be a finite number");
        }
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        if (!dependsOn.isEmpty() && category == null) {
            throw new InvalidInputException("a rule that declares dependsOn must name its own category");
        }
    }

    public AlertRule withDetails(
            String newMetric,
            Comparison newComparison,
            double newThreshold,
            AlertSeverity newSeverity,
            Category newCategory,
            Set<Category> newDependsOn) {
        return new AlertRule(
                ruleId,
                tenantId,
                newMetric,
                newComparison,
                newThreshold,
                newSeverity,
                newCategory,
                newDependsOn,
                version);
    }

    @Override
    public ConfigKind configKind() {
        return ConfigKind.ALERT_RULE;
    }

    /**
     * The bare rule id, not a kind-qualified key: the alert engine names a rule by the record key and
     * removes it by the tombstone's key. It cannot collide with another kind's key, because those all
     * start with "kind/" and an id may not contain "/".
     */
    @Override
    public String entityId() {
        return ruleId;
    }

    @Override
    public String owningTenant() {
        return tenantId.value();
    }

    @Override
    public Map<String, Object> snapshot() {
        var m = new LinkedHashMap<String, Object>();
        m.put("entityKind", configKind().name());
        m.put("kind", CONDITION_KIND);
        m.put("ruleId", ruleId);
        m.put("tenantId", tenantId.value());
        m.put("metric", metric);
        m.put("comparator", comparison.name());
        m.put("threshold", threshold);
        m.put("severity", severity.name());
        m.put("category", category == null ? null : category.name());
        m.put("dependsOn", sortedNames(dependsOn));
        m.put("version", version);
        return m;
    }

    private static java.util.List<String> sortedNames(Set<Category> categories) {
        var names = new ArrayList<>(categories.stream().map(Category::name).toList());
        names.sort(Comparator.naturalOrder());
        return names;
    }
}
