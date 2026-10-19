/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every read joins the upstream categories in the same statement: one query per call, not 1 + N. */
public interface AlertRuleJpaRepository extends JpaRepository<AlertRuleEntity, String> {

    @Override
    @EntityGraph(attributePaths = "dependsOn")
    List<AlertRuleEntity> findAll();

    @Override
    @EntityGraph(attributePaths = "dependsOn")
    Optional<AlertRuleEntity> findById(String ruleId);

    @EntityGraph(attributePaths = "dependsOn")
    List<AlertRuleEntity> findByTenantIdOrderByRuleId(String tenantId);
}
