/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.AppRepository;
import com.streampulse.controlplane.domain.TenantApp;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JpaAppRepository implements AppRepository {

    private final AppJpaRepository repository;

    public JpaAppRepository(AppJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean exists(String appId) {
        return repository.existsById(appId);
    }

    @Override
    public List<TenantApp> findByTenant(TenantId tenantId) {
        return repository.findByTenantIdOrderByAppId(tenantId.value()).stream()
                .map(AppEntity::toDomain)
                .toList();
    }

    @Override
    public TenantApp insert(TenantApp app) {
        return repository.saveAndFlush(AppEntity.from(app)).toDomain();
    }

    @Override
    public boolean delete(TenantId tenantId, String appId) {
        return repository.deleteByTenantIdAndAppId(tenantId.value(), appId) > 0;
    }
}
