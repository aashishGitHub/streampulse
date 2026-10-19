/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.LinkedHashMap;
import java.util.Map;

public record Title(String titleId, TenantId tenantId, String name, TitleKind kind, long version)
        implements ConfigEntity {

    public Title {
        Ids.entityId("titleId", titleId);
        if (tenantId == null) {
            throw new InvalidInputException("tenantId must not be null");
        }
        Ids.requireText("name", name, 200);
        if (kind == null) {
            throw new InvalidInputException("kind must not be null");
        }
    }

    public Title withDetails(String newName, TitleKind newKind) {
        return new Title(titleId, tenantId, newName, newKind, version);
    }

    @Override
    public ConfigKind configKind() {
        return ConfigKind.TITLE;
    }

    @Override
    public String entityId() {
        return ConfigKind.TITLE.key(tenantId.value(), titleId);
    }

    @Override
    public String owningTenant() {
        return tenantId.value();
    }

    @Override
    public Map<String, Object> snapshot() {
        var m = new LinkedHashMap<String, Object>();
        m.put("entityKind", configKind().name());
        m.put("titleId", titleId);
        m.put("tenantId", tenantId.value());
        m.put("name", name);
        m.put("titleKind", kind.name());
        m.put("version", version);
        return m;
    }
}
