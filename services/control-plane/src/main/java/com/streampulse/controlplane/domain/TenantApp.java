/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;

/** An app belongs to a tenant. Not announced: the change-record contract has no kind for it. */
public record TenantApp(String appId, TenantId tenantId, String name, AppPlatform platform) {

    public TenantApp {
        Ids.entityId("appId", appId);
        if (tenantId == null) {
            throw new InvalidInputException("tenantId must not be null");
        }
        Ids.requireText("name", name, 120);
        if (platform == null) {
            throw new InvalidInputException("platform must not be null");
        }
    }
}
