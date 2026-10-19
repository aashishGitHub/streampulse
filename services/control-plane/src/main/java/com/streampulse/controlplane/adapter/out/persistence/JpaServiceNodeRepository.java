/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.ConflictException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.controlplane.domain.ServiceNodeRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class JpaServiceNodeRepository implements ServiceNodeRepository {

    private final ServiceNodeJpaRepository repository;

    public JpaServiceNodeRepository(ServiceNodeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ServiceNode> find(String nodeId) {
        return repository.findById(nodeId).map(ServiceNodeEntity::toDomain);
    }

    @Override
    public List<ServiceNode> findAll() {
        return repository.findAll().stream().map(ServiceNodeEntity::toDomain).toList();
    }

    @Override
    public List<ServiceNode> findByTenant(TenantId tenantId) {
        return repository.findByTenantIdOrderByNodeId(tenantId.value()).stream()
                .map(ServiceNodeEntity::toDomain)
                .toList();
    }

    @Override
    public ServiceNode insert(ServiceNode node) {
        return repository.saveAndFlush(ServiceNodeEntity.from(node)).toDomain();
    }

    @Override
    public ServiceNode update(ServiceNode node) {
        ServiceNodeEntity entity = repository
                .findById(node.nodeId())
                .orElseThrow(() -> new NotFoundException("service node", node.nodeId()));
        entity.apply(node);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public void delete(String nodeId) {
        try {
            repository.deleteById(nodeId);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("service node '" + nodeId + "' is still a dependency of another node");
        }
    }
}
