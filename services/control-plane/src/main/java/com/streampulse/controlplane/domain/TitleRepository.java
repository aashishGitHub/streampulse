/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;

public interface TitleRepository {

    Optional<Title> find(String titleId);

    List<Title> findAll();

    List<Title> findByTenant(TenantId tenantId);

    Title insert(Title title);

    Title update(Title title);

    void delete(String titleId);
}
