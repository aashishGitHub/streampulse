/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.platform.domain.TenantId;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "service_node")
public class ServiceNodeEntity {

    @Id
    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "service_node_dependency", joinColumns = @JoinColumn(name = "node_id"))
    @Column(name = "depends_on_node_id", nullable = false)
    private Set<String> dependsOn = new LinkedHashSet<>();

    protected ServiceNodeEntity() {}

    static ServiceNodeEntity from(ServiceNode node) {
        var e = new ServiceNodeEntity();
        e.nodeId = node.nodeId();
        e.tenantId = node.tenantId().value();
        e.apply(node);
        return e;
    }

    void apply(ServiceNode node) {
        this.name = node.name();
        this.category = node.category();
        this.dependsOn.clear();
        this.dependsOn.addAll(node.dependsOn());
    }

    ServiceNode toDomain() {
        return new ServiceNode(
                nodeId,
                TenantId.of(tenantId),
                name,
                category,
                new LinkedHashSet<>(dependsOn),
                version == null ? 0 : version);
    }
}
