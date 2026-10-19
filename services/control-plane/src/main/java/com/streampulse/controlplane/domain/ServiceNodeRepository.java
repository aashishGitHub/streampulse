/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;

public interface ServiceNodeRepository {

    Optional<ServiceNode> find(String nodeId);

    List<ServiceNode> findAll();

    List<ServiceNode> findByTenant(TenantId tenantId);

    ServiceNode insert(ServiceNode node);

    ServiceNode update(ServiceNode node);

    /** @throws ConflictException when another node still depends on it */
    void delete(String nodeId);
}
