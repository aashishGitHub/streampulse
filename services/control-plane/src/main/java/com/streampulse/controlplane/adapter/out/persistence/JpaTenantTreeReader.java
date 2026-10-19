/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.TenantApp;
import com.streampulse.controlplane.domain.TenantTreeNode;
import com.streampulse.controlplane.domain.TenantTreeReader;
import com.streampulse.controlplane.domain.Title;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JpaTenantTreeReader implements TenantTreeReader {

    private final TenantJpaRepository repository;

    public JpaTenantTreeReader(TenantJpaRepository repository) {
        this.repository = repository;
    }

    /** Two statements however many tenants there are; the parent/child links are assembled in memory. */
    @Override
    public List<TenantTreeNode> readTree() {
        List<TenantEntity> tenants = repository.findAllWithApps();
        repository.findAllWithTitles();
        return assemble(tenants);
    }

    private static List<TenantTreeNode> assemble(List<TenantEntity> tenants) {
        Map<String, List<TenantEntity>> childrenByParent = new HashMap<>();
        List<TenantEntity> roots = new ArrayList<>();
        for (TenantEntity tenant : tenants) {
            if (tenant.getParentTenantId() == null) {
                roots.add(tenant);
            } else {
                childrenByParent
                        .computeIfAbsent(tenant.getParentTenantId(), k -> new ArrayList<>())
                        .add(tenant);
            }
        }
        return roots.stream()
                .sorted(Comparator.comparing(TenantEntity::getTenantId))
                .map(root -> node(root, childrenByParent))
                .toList();
    }

    private static TenantTreeNode node(TenantEntity entity, Map<String, List<TenantEntity>> childrenByParent) {
        Tenant tenant = entity.toDomain();
        List<TenantApp> apps = entity.getApps().stream()
                .map(AppEntity::toDomain)
                .sorted(Comparator.comparing(TenantApp::appId))
                .toList();
        List<Title> titles = entity.getTitles().stream()
                .map(TitleEntity::toDomain)
                .sorted(Comparator.comparing(Title::titleId))
                .toList();
        List<TenantTreeNode> children = childrenByParent.getOrDefault(entity.getTenantId(), List.of()).stream()
                .sorted(Comparator.comparing(TenantEntity::getTenantId))
                .map(child -> node(child, childrenByParent))
                .toList();
        return new TenantTreeNode(tenant, apps, titles, children);
    }
}
