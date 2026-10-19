/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertSeverity;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.Comparison;
import com.streampulse.platform.domain.TenantId;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "alert_rule")
public class AlertRuleEntity {

    @Id
    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String metric;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Comparison comparator;

    @Column(nullable = false)
    private double threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Version
    private Long version;

    /** Lazy by default for collections. Listing rules and reading this per row is the N+1 the lab reproduces. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "alert_rule_dependency", joinColumns = @JoinColumn(name = "rule_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "upstream_category", nullable = false)
    private Set<Category> dependsOn = new LinkedHashSet<>();

    protected AlertRuleEntity() {}

    static AlertRuleEntity from(AlertRule rule) {
        var e = new AlertRuleEntity();
        e.ruleId = rule.ruleId();
        e.tenantId = rule.tenantId().value();
        e.apply(rule);
        return e;
    }

    void apply(AlertRule rule) {
        this.metric = rule.metric();
        this.comparator = rule.comparison();
        this.threshold = rule.threshold();
        this.severity = rule.severity();
        this.category = rule.category();
        this.dependsOn.clear();
        this.dependsOn.addAll(rule.dependsOn());
    }

    AlertRule toDomain() {
        Set<Category> deps = dependsOn.isEmpty() ? EnumSet.noneOf(Category.class) : EnumSet.copyOf(dependsOn);
        return new AlertRule(
                ruleId,
                TenantId.of(tenantId),
                metric,
                comparator,
                threshold,
                severity,
                category,
                deps,
                version == null ? 0 : version);
    }

    public Set<Category> getDependsOn() {
        return dependsOn;
    }
}
