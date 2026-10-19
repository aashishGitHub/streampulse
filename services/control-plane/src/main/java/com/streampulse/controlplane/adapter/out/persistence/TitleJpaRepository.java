/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TitleJpaRepository extends JpaRepository<TitleEntity, String> {

    List<TitleEntity> findByTenantIdOrderByTitleId(String tenantId);
}
