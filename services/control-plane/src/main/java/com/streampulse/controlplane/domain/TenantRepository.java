/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;

public interface TenantRepository {

    Optional<Tenant> find(TenantId id);

    boolean exists(TenantId id);

    List<Tenant> findAll();

    /** Returns the stored tenant, with the version the store assigned. */
    Tenant insert(Tenant tenant);

    Tenant update(Tenant tenant);

    /** @throws ConflictException when children, apps, titles, rules or nodes still reference it */
    void delete(TenantId id);
}
