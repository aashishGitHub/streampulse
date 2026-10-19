/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertRuleDependencies;
import com.streampulse.controlplane.domain.AlertRuleRepository;
import com.streampulse.controlplane.domain.AlertSeverity;
import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.Comparison;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertRuleService {

    private final AlertRuleRepository rules;
    private final TenantRepository tenants;
    private final ChangeRecorder changes;

    public AlertRuleService(AlertRuleRepository rules, TenantRepository tenants, ChangeRecorder changes) {
        this.rules = rules;
        this.tenants = tenants;
        this.changes = changes;
    }

    @Transactional
    public AlertRule create(AlertRule draft) {
        if (!tenants.exists(draft.tenantId())) {
            throw new NotFoundException("tenant", draft.tenantId().value());
        }
        if (rules.find(draft.ruleId()).isPresent()) {
            throw new AlreadyExistsException("alert rule", draft.ruleId());
        }
        AlertRuleDependencies.assertAcyclic(draft, rules.findByTenant(draft.tenantId()));

        AlertRule stored = rules.insert(draft);
        changes.created(stored);
        return stored;
    }

    @Transactional(readOnly = true)
    public AlertRule get(String ruleId) {
        return rules.find(ruleId).orElseThrow(() -> new NotFoundException("alert rule", ruleId));
    }

    @Transactional(readOnly = true)
    public List<AlertRule> list(TenantId tenantIdOrNull) {
        return tenantIdOrNull == null ? rules.findAll() : rules.findByTenant(tenantIdOrNull);
    }

    @Transactional
    public AlertRule update(
            String ruleId,
            String metric,
            Comparison comparison,
            double threshold,
            AlertSeverity severity,
            Category category,
            Set<Category> dependsOn) {
        AlertRule current = get(ruleId);
        AlertRule next = current.withDetails(metric, comparison, threshold, severity, category, dependsOn);
        List<AlertRule> others = rules.findByTenant(current.tenantId()).stream()
                .filter(r -> !r.ruleId().equals(ruleId))
                .toList();
        AlertRuleDependencies.assertAcyclic(next, others);

        AlertRule stored = rules.update(next);
        changes.updated(stored);
        return stored;
    }

    @Transactional
    public void delete(String ruleId) {
        AlertRule current = get(ruleId);
        rules.delete(ruleId);
        changes.deleted(current);
    }
}
