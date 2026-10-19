/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.List;

public interface AppRepository {

    boolean exists(String appId);

    List<TenantApp> findByTenant(TenantId tenantId);

    TenantApp insert(TenantApp app);

    /** @return whether a row was removed */
    boolean delete(TenantId tenantId, String appId);
}
