/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;

public interface AlertRuleRepository {

    Optional<AlertRule> find(String ruleId);

    List<AlertRule> findAll();

    List<AlertRule> findByTenant(TenantId tenantId);

    AlertRule insert(AlertRule rule);

    AlertRule update(AlertRule rule);

    void delete(String ruleId);
}
