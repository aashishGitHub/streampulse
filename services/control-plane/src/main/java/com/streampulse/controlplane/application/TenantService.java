/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.RuleViolationException;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.TenantHierarchy;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final ChangeRecorder changes;

    public TenantService(TenantRepository tenants, ChangeRecorder changes) {
        this.tenants = tenants;
        this.changes = changes;
    }

    @Transactional
    public Tenant create(Tenant draft) {
        if (tenants.exists(draft.tenantId())) {
            throw new AlreadyExistsException("tenant", draft.tenantId().value());
        }
        requireParentExists(draft.parentTenantId());
        TenantHierarchy.assertNoCycle(draft.tenantId(), draft.parentTenantId(), this::parentOf);

        Tenant stored = tenants.insert(draft);
        changes.created(stored);
        return stored;
    }

    @Transactional(readOnly = true)
    public Tenant get(TenantId id) {
        return tenants.find(id).orElseThrow(() -> new NotFoundException("tenant", id.value()));
    }

    @Transactional(readOnly = true)
    public List<Tenant> list() {
        return tenants.findAll();
    }

    @Transactional
    public Tenant update(TenantId id, String displayName, Long monthlyEventQuota, TenantId parentTenantId) {
        Tenant current = tenants.find(id).orElseThrow(() -> new NotFoundException("tenant", id.value()));
        requireParentExists(parentTenantId);
        TenantHierarchy.assertNoCycle(id, parentTenantId, this::parentOf);

        Tenant stored = tenants.update(current.withDetails(displayName, monthlyEventQuota, parentTenantId));
        changes.updated(stored);
        return stored;
    }

    @Transactional
    public void delete(TenantId id) {
        Tenant current = tenants.find(id).orElseThrow(() -> new NotFoundException("tenant", id.value()));
        tenants.delete(id);
        changes.deleted(current);
    }

    private void requireParentExists(TenantId parent) {
        if (parent != null && !tenants.exists(parent)) {
            throw new RuleViolationException("parent tenant '" + parent + "' does not exist");
        }
    }

    private java.util.Optional<TenantId> parentOf(TenantId id) {
        return tenants.find(id).map(Tenant::parentTenantId);
    }
}
