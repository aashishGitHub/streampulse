/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertRuleRepository;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaAlertRuleRepository implements AlertRuleRepository {

    private final AlertRuleJpaRepository repository;

    public JpaAlertRuleRepository(AlertRuleJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AlertRule> find(String ruleId) {
        return repository.findById(ruleId).map(AlertRuleEntity::toDomain);
    }

    @Override
    public List<AlertRule> findAll() {
        return repository.findAll().stream().map(AlertRuleEntity::toDomain).toList();
    }

    @Override
    public List<AlertRule> findByTenant(TenantId tenantId) {
        return repository.findByTenantIdOrderByRuleId(tenantId.value()).stream()
                .map(AlertRuleEntity::toDomain)
                .toList();
    }

    @Override
    public AlertRule insert(AlertRule rule) {
        return repository.saveAndFlush(AlertRuleEntity.from(rule)).toDomain();
    }

    @Override
    public AlertRule update(AlertRule rule) {
        AlertRuleEntity entity = repository
                .findById(rule.ruleId())
                .orElseThrow(() -> new NotFoundException("alert rule", rule.ruleId()));
        entity.apply(rule);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public void delete(String ruleId) {
        repository.deleteById(ruleId);
        repository.flush();
    }
}
