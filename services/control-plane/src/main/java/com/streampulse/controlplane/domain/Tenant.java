/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.LinkedHashMap;
import java.util.Map;

public record Tenant(
        TenantId tenantId, String displayName, Long monthlyEventQuota, TenantId parentTenantId, long version)
        implements ConfigEntity {

    public Tenant {
        if (tenantId == null) {
            throw new InvalidInputException("tenantId must not be null");
        }
        Ids.tenantId(tenantId.value());
        Ids.requireText("displayName", displayName, 120);
        if (monthlyEventQuota != null && monthlyEventQuota < 0) {
            throw new InvalidInputException("monthlyEventQuota must not be negative");
        }
    }

    public static Tenant draft(String tenantId, String displayName, Long quota, String parentTenantId) {
        return new Tenant(
                Ids.tenantId(tenantId),
                displayName,
                quota,
                parentTenantId == null ? null : Ids.tenantId(parentTenantId),
                0);
    }

    public Tenant withDetails(String newDisplayName, Long newQuota, TenantId newParent) {
        return new Tenant(tenantId, newDisplayName, newQuota, newParent, version);
    }

    @Override
    public ConfigKind configKind() {
        return ConfigKind.TENANT;
    }

    @Override
    public String entityId() {
        return ConfigKind.TENANT.key(tenantId.value());
    }

    @Override
    public String owningTenant() {
        return tenantId.value();
    }

    @Override
    public Map<String, Object> snapshot() {
        var m = new LinkedHashMap<String, Object>();
        m.put("entityKind", configKind().name());
        m.put("tenantId", tenantId.value());
        m.put("displayName", displayName);
        m.put("monthlyEventQuota", monthlyEventQuota);
        m.put("parentTenantId", parentTenantId == null ? null : parentTenantId.value());
        m.put("version", version);
        return m;
    }
}
