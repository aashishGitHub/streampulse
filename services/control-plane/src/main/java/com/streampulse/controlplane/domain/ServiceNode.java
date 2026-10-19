/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import com.streampulse.platform.domain.TenantId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** One node of the service dependency graph (CDN -> packager -> player). {@code dependsOn} holds upstream node ids. */
public record ServiceNode(
        String nodeId, TenantId tenantId, String name, Category category, Set<String> dependsOn, long version)
        implements ConfigEntity {

    public ServiceNode {
        Ids.entityId("nodeId", nodeId);
        if (tenantId == null) {
            throw new InvalidInputException("tenantId must not be null");
        }
        Ids.requireText("name", name, 120);
        if (category == null) {
            throw new InvalidInputException("category must not be null");
        }
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        dependsOn.forEach(d -> Ids.entityId("dependsOn", d));
    }

    public ServiceNode withDetails(String newName, Category newCategory, Set<String> newDependsOn) {
        return new ServiceNode(nodeId, tenantId, newName, newCategory, newDependsOn, version);
    }

    @Override
    public ConfigKind configKind() {
        return ConfigKind.SERVICE_NODE;
    }

    @Override
    public String entityId() {
        return ConfigKind.SERVICE_NODE.key(tenantId.value(), nodeId);
    }

    @Override
    public String owningTenant() {
        return tenantId.value();
    }

    @Override
    public Map<String, Object> snapshot() {
        var m = new LinkedHashMap<String, Object>();
        m.put("entityKind", configKind().name());
        m.put("nodeId", nodeId);
        m.put("tenantId", tenantId.value());
        m.put("name", name);
        m.put("category", category.name());
        List<String> deps = List.copyOf(new TreeSet<>(dependsOn));
        m.put("dependsOn", deps);
        m.put("version", version);
        return m;
    }
}
