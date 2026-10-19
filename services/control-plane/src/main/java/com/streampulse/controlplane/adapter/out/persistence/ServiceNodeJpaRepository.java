/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceNodeJpaRepository extends JpaRepository<ServiceNodeEntity, String> {

    @Override
    @EntityGraph(attributePaths = "dependsOn")
    List<ServiceNodeEntity> findAll();

    @Override
    @EntityGraph(attributePaths = "dependsOn")
    Optional<ServiceNodeEntity> findById(String nodeId);

    @EntityGraph(attributePaths = "dependsOn")
    List<ServiceNodeEntity> findByTenantIdOrderByNodeId(String tenantId);
}
