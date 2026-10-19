/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.controlplane.adapter.in.web.dto.TenantDtos.TenantTreeResponse;
import com.streampulse.controlplane.adapter.out.persistence.TenantJpaRepository;
import com.streampulse.controlplane.application.TenantTreeService;
import com.streampulse.controlplane.domain.TenantTreeNode;
import com.streampulse.controlplane.support.PostgresIT;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The N+1 lab on the tenant tree (REQ-CP-05). First the problem is REPRODUCED and its exact cost
 * asserted, then the production reader is shown to stay flat. Counted with Hibernate Statistics
 * (getPrepareStatementCount: every JDBC statement Hibernate prepares, whatever triggered it).
 */
class TenantTreeQueryCountIT extends PostgresIT {

    private static final int APPS_AND_TITLES_PER_TENANT = 2;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private TenantJpaRepository repository;

    @Autowired
    private TenantTreeService treeService;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private TestRestTemplate http;

    private Statistics stats;

    @BeforeEach
    void statistics() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).isTrue();
    }

    private void seed(int rootTenants) {
        for (int i = 0; i < rootTenants; i++) {
            String id = "tenant-" + i;
            jdbc.update("insert into control_plane.tenant (tenant_id, display_name) values (?, ?)", id, id);
            for (int j = 0; j < APPS_AND_TITLES_PER_TENANT; j++) {
                jdbc.update(
                        "insert into control_plane.app (app_id, tenant_id, name, platform) values (?, ?, 'app', 'WEB')",
                        id + "-app-" + j,
                        id);
                jdbc.update(
                        "insert into control_plane.title (title_id, tenant_id, name, kind) values (?, ?, 'title', 'VOD')",
                        id + "-title-" + j,
                        id);
            }
        }
    }

    @Test
    @DisplayName("REPRODUCTION: loading tenants then touching each lazy collection costs 1 + 2N statements")
    void naiveTraversalIsOnePlusTwoN() {
        int n = 5;
        seed(n);
        stats.clear();

        new TransactionTemplate(txManager)
                .executeWithoutResult(status -> repository.findAll().forEach(tenant -> {
                    tenant.getApps().size();
                    tenant.getTitles().size();
                }));

        assertThat(stats.getPrepareStatementCount())
                .as("1 query for the tenants, then 2 lazy collection loads per tenant")
                .isEqualTo(1 + 2L * n);
    }

    @Test
    @DisplayName("FIX: the tree costs the same two statements for 3 tenants as for 30, and returns all the data")
    void treeCostDoesNotGrowWithTenants() {
        seed(3);
        stats.clear();
        List<TenantTreeNode> small = treeService.tree();
        long smallCost = stats.getPrepareStatementCount();

        emptyEveryTable();
        seed(30);
        stats.clear();
        List<TenantTreeNode> large = treeService.tree();
        long largeCost = stats.getPrepareStatementCount();

        assertThat(smallCost).isLessThanOrEqualTo(3);
        assertThat(largeCost).as("flat in the number of tenants").isEqualTo(smallCost);
        assertThat(small).hasSize(3);
        assertThat(large).hasSize(30);
        assertThat(large).allSatisfy(node -> {
            assertThat(node.apps()).hasSize(APPS_AND_TITLES_PER_TENANT);
            assertThat(node.titles()).hasSize(APPS_AND_TITLES_PER_TENANT);
        });
    }

    @Test
    @DisplayName("FIX: children are assembled from parent_tenant_id in memory, still at most 3 statements")
    void nestedChildrenCostNothingExtra() {
        seed(1);
        for (int i = 0; i < 10; i++) {
            jdbc.update(
                    "insert into control_plane.tenant (tenant_id, display_name, parent_tenant_id) values (?, ?, 'tenant-0')",
                    "child-" + i,
                    "child");
        }
        stats.clear();

        List<TenantTreeNode> tree = treeService.tree();

        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        assertThat(tree).hasSize(1);
        assertThat(tree.getFirst().children()).hasSize(10);
    }

    @Test
    @DisplayName("the whole GET /v1/admin/tenants/tree response costs at most 3 statements")
    void endpointCostsAtMostThreeStatements() {
        seed(12);
        stats.clear();

        var response = http.exchange(
                "/v1/admin/tenants/tree",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<TenantTreeResponse>>() {});

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(12);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}
