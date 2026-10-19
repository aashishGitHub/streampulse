/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.controlplane.domain.ServiceNodeDependencies;
import com.streampulse.controlplane.domain.ServiceNodeRepository;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceNodeService {

    private final ServiceNodeRepository nodes;
    private final TenantRepository tenants;
    private final ChangeRecorder changes;

    public ServiceNodeService(ServiceNodeRepository nodes, TenantRepository tenants, ChangeRecorder changes) {
        this.nodes = nodes;
        this.tenants = tenants;
        this.changes = changes;
    }

    @Transactional
    public ServiceNode create(ServiceNode draft) {
        if (!tenants.exists(draft.tenantId())) {
            throw new NotFoundException("tenant", draft.tenantId().value());
        }
        if (nodes.find(draft.nodeId()).isPresent()) {
            throw new AlreadyExistsException("service node", draft.nodeId());
        }
        ServiceNodeDependencies.validate(draft, nodes.findByTenant(draft.tenantId()));

        ServiceNode stored = nodes.insert(draft);
        changes.created(stored);
        return stored;
    }

    @Transactional(readOnly = true)
    public ServiceNode get(String nodeId) {
        return nodes.find(nodeId).orElseThrow(() -> new NotFoundException("service node", nodeId));
    }

    @Transactional(readOnly = true)
    public List<ServiceNode> list(TenantId tenantIdOrNull) {
        return tenantIdOrNull == null ? nodes.findAll() : nodes.findByTenant(tenantIdOrNull);
    }

    @Transactional
    public ServiceNode update(String nodeId, String name, Category category, Set<String> dependsOn) {
        ServiceNode current = get(nodeId);
        ServiceNode next = current.withDetails(name, category, dependsOn);
        List<ServiceNode> others = nodes.findByTenant(current.tenantId()).stream()
                .filter(n -> !n.nodeId().equals(nodeId))
                .toList();
        ServiceNodeDependencies.validate(next, others);

        ServiceNode stored = nodes.update(next);
        changes.updated(stored);
        return stored;
    }

    @Transactional
    public void delete(String nodeId) {
        ServiceNode current = get(nodeId);
        nodes.delete(nodeId);
        changes.deleted(current);
    }
}
