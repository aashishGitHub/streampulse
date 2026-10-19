/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.controlplane.adapter.out.persistence.AlertRuleEntity;
import com.streampulse.controlplane.application.AlertRuleService;
import com.streampulse.controlplane.support.PostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The N+1 lab on alert rules (REQ-CP-12): a lazy element collection read once per row. */
class AlertRuleQueryCountIT extends PostgresIT {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AlertRuleService rules;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private jakarta.persistence.EntityManagerFactory emf;

    private Statistics stats;

    @BeforeEach
    void statistics() {
        stats = emf.unwrap(SessionFactory.class).getStatistics();
    }

    private void seed(int ruleCount) {
        jdbc.update("insert into control_plane.tenant (tenant_id, display_name) values ('acme', 'Acme')");
        for (int i = 0; i < ruleCount; i++) {
            jdbc.update(
                    "insert into control_plane.alert_rule (rule_id, tenant_id, metric, comparator, threshold, severity, category)"
                            + " values (?, 'acme', 'error_rate', 'GT', 0.1, 'WARNING', 'PLAYER')",
                    "rule-" + i);
            jdbc.update(
                    "insert into control_plane.alert_rule_dependency (rule_id, upstream_category) values (?, 'PACKAGER')",
                    "rule-" + i);
        }
    }

    @Test
    @DisplayName("REPRODUCTION: a plain query then reading each rule's dependencies costs 1 + N statements")
    void naiveListIsOnePlusN() {
        int n = 6;
        seed(n);
        stats.clear();

        new TransactionTemplate(txManager)
                .executeWithoutResult(status -> em.createQuery("select r from AlertRuleEntity r", AlertRuleEntity.class)
                        .getResultList()
                        .forEach(rule -> rule.getDependsOn().size()));

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1 + (long) n);
    }

    @Test
    @DisplayName(
            "FIX: listing rules through the service is ONE statement for 4 rules and for 40, dependencies included")
    void listCostsOneStatementWhateverTheRuleCount() {
        seed(4);
        stats.clear();
        var small = rules.list(null);
        long smallCost = stats.getPrepareStatementCount();

        for (int i = 4; i < 40; i++) {
            jdbc.update(
                    "insert into control_plane.alert_rule (rule_id, tenant_id, metric, comparator, threshold, severity, category)"
                            + " values (?, 'acme', 'error_rate', 'GT', 0.1, 'WARNING', 'PLAYER')",
                    "rule-" + i);
            jdbc.update(
                    "insert into control_plane.alert_rule_dependency (rule_id, upstream_category) values (?, 'PACKAGER')",
                    "rule-" + i);
        }
        stats.clear();
        var large = rules.list(null);
        long largeCost = stats.getPrepareStatementCount();

        assertThat(smallCost).isEqualTo(1);
        assertThat(largeCost).isEqualTo(1);
        assertThat(small).hasSize(4);
        assertThat(large).hasSize(40).allSatisfy(rule -> assertThat(rule.dependsOn())
                .hasSize(1));
    }
}
