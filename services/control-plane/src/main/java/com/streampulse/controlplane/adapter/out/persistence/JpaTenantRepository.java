/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.ConflictException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class JpaTenantRepository implements TenantRepository {

    private final TenantJpaRepository repository;

    public JpaTenantRepository(TenantJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Tenant> find(TenantId id) {
        return repository.findById(id.value()).map(TenantEntity::toDomain);
    }

    @Override
    public boolean exists(TenantId id) {
        return repository.existsById(id.value());
    }

    @Override
    public List<Tenant> findAll() {
        return repository.findAll().stream().map(TenantEntity::toDomain).toList();
    }

    @Override
    public Tenant insert(Tenant tenant) {
        return repository.saveAndFlush(TenantEntity.from(tenant)).toDomain();
    }

    @Override
    public Tenant update(Tenant tenant) {
        TenantEntity entity = repository
                .findById(tenant.tenantId().value())
                .orElseThrow(
                        () -> new NotFoundException("tenant", tenant.tenantId().value()));
        entity.apply(tenant);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public void delete(TenantId id) {
        try {
            repository.deleteById(id.value());
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "tenant '" + id + "' is still referenced by children, apps, titles, rules or nodes");
        }
    }
}
