/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.AppPlatform;
import com.streampulse.controlplane.domain.AppRepository;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.TenantApp;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppService {

    private final AppRepository apps;
    private final TenantRepository tenants;

    public AppService(AppRepository apps, TenantRepository tenants) {
        this.apps = apps;
        this.tenants = tenants;
    }

    @Transactional
    public TenantApp create(TenantId tenantId, String appId, String name, AppPlatform platform) {
        requireTenant(tenantId);
        if (apps.exists(appId)) {
            throw new AlreadyExistsException("app", appId);
        }
        return apps.insert(new TenantApp(appId, tenantId, name, platform));
    }

    @Transactional(readOnly = true)
    public List<TenantApp> list(TenantId tenantId) {
        requireTenant(tenantId);
        return apps.findByTenant(tenantId);
    }

    @Transactional
    public void delete(TenantId tenantId, String appId) {
        requireTenant(tenantId);
        if (!apps.delete(tenantId, appId)) {
            throw new NotFoundException("app", appId);
        }
    }

    private void requireTenant(TenantId tenantId) {
        if (!tenants.exists(tenantId)) {
            throw new NotFoundException("tenant", tenantId.value());
        }
    }
}
