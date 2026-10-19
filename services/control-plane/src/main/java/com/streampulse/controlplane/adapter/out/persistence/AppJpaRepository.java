/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppJpaRepository extends JpaRepository<AppEntity, String> {

    List<AppEntity> findByTenantIdOrderByAppId(String tenantId);

    long deleteByTenantIdAndAppId(String tenantId, String appId);
}
