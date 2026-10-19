/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import com.streampulse.controlplane.application.AlertRuleService;
import com.streampulse.controlplane.application.ServiceNodeService;
import com.streampulse.controlplane.application.TenantService;
import com.streampulse.controlplane.application.TitleService;
import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertSeverity;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.Comparison;
import com.streampulse.controlplane.domain.ConfigChangeOutbox;
import com.streampulse.controlplane.domain.Operation;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleKind;
import com.streampulse.controlplane.support.PostgresIT;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** REQ-CP-16..22: the business change and its announcement commit, or roll back, together. */
class OutboxAtomicityIT extends PostgresIT {

    private static final TenantId ACME = TenantId.of("acme");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AvroBinaryCodec<ConfigChange> CODEC = AvroBinaryCodec.of(ConfigChange.class);

    @Autowired
    private TenantService tenants;

    @Autowired
    private TitleService titles;

    @Autowired
    private AlertRuleService rules;

    @Autowired
    private ServiceNodeService nodes;

    @Autowired
    private ConfigChangeOutbox outbox;

    @Autowired
    private PlatformTransactionManager txManager;

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from control_plane." + table, Long.class);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList(
                "select id, aggregate_type, aggregate_id, operation, payload from control_plane.outbox order by created_at");
    }

    private static Tenant acme() {
        return Tenant.draft("acme", "Acme", null, null);
    }

    @Test
    @DisplayName("a change that rolls back after its announcement was written leaves NO outbox row")
    void rolledBackChangeLeavesNoOutboxRow() {
        var tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    tenants.create(acme());
                    assertThat(count("outbox"))
                            .as("the row is really there, inside the transaction: the test is not vacuous")
                            .isEqualTo(1);
                    throw new IllegalStateException("abandon the unit of work");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("tenant")).isZero();
        assertThat(count("outbox")).isZero();
    }

    @Test
    @DisplayName("a committed change leaves exactly one outbox row, carrying the contract record")
    void committedChangeLeavesExactlyOneRow() throws Exception {
        tenants.create(acme());

        assertThat(count("tenant")).isEqualTo(1);
        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row).containsEntry("aggregate_type", "TENANT").containsEntry("aggregate_id", "tenant/acme");

        ConfigChange decoded = CODEC.decode((byte[]) row.get("payload"));
        assertThat(decoded.getChangeId().toString())
                .as("the change id IS the outbox row id, so a consumer can deduplicate a replay")
                .isEqualTo(row.get("id").toString());
        assertThat(decoded.getEntityType()).isEqualTo(EntityType.TENANT);
        assertThat(decoded.getOperation()).isEqualTo(ChangeOperation.CREATED);
        assertThat(decoded.getEntityId().toString()).isEqualTo("tenant/acme");
    }

    @Test
    @DisplayName("an announcement outside a transaction is refused instead of committing on its own")
    void outboxRefusesToWriteOutsideATransaction() {
        var event = ChangeEvent.of(
                UUID.randomUUID(), Operation.CREATED, new Tenant(ACME, "Acme", null, null, 0), Instant.now());

        assertThatThrownBy(() -> outbox.append(event)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("outbox")).isZero();
    }

    @Test
    @DisplayName("when the outbox insert fails, the business change rolls back with it")
    void failingOutboxRollsTheBusinessChangeBack() {
        jdbc.execute(
                "ALTER TABLE control_plane.outbox ADD CONSTRAINT refuse_boom CHECK (aggregate_id <> 'tenant/boom')");
        try {
            assertThatThrownBy(() -> tenants.create(Tenant.draft("boom", "Boom", null, null)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

            assertThat(count("tenant")).as("no tenant without its announcement").isZero();
            assertThat(count("outbox")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE control_plane.outbox DROP CONSTRAINT refuse_boom");
        }
    }

    @Test
    @DisplayName("an update writes a FULL snapshot with the version the database assigned")
    void updateWritesAFullSnapshotWithTheNewVersion() throws Exception {
        tenants.create(acme());
        tenants.update(ACME, "Acme Inc", 500L, null);

        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(2);
        ConfigChange updated = CODEC.decode((byte[]) rows.get(1).get("payload"));
        assertThat(updated.getOperation()).isEqualTo(ChangeOperation.UPDATED);
        assertThat(updated.getVersion()).isEqualTo(1);

        Map<String, Object> snapshot = JSON.readValue(updated.getPayload().toString(), new TypeReference<>() {});
        assertThat(snapshot)
                .containsEntry("displayName", "Acme Inc")
                .containsEntry("monthlyEventQuota", 500)
                .containsEntry("version", 1)
                .containsKeys("tenantId", "parentTenantId");
    }

    @Test
    @DisplayName("a delete writes a row with a NULL payload: the tombstone")
    void deleteWritesATombstoneRow() {
        tenants.create(acme());
        tenants.delete(ACME);

        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("operation", "DELETED").containsEntry("aggregate_id", "tenant/acme");
        assertThat(rows.get(1).get("payload")).isNull();
        assertThat(rows.get(0).get("payload")).isNotNull();
    }

    @Test
    @DisplayName("tenants, titles, rules and service nodes each announce create, update and delete exactly once")
    void everyKindAnnouncesEveryOperation() {
        tenants.create(acme());
        titles.create(new Title("t1", ACME, "Title", TitleKind.VOD, 0));
        titles.update("t1", "Renamed", TitleKind.LIVE);
        titles.delete("t1");
        rules.create(new AlertRule(
                "r1", ACME, "error_rate", Comparison.GT, 0.1, AlertSeverity.INFO, Category.CDN, Set.of(), 0));
        rules.update("r1", "error_rate", Comparison.GTE, 0.2, AlertSeverity.INFO, Category.CDN, Set.of());
        rules.delete("r1");
        nodes.create(new ServiceNode("n1", ACME, "Edge", Category.CDN, Set.of(), 0));
        nodes.update("n1", "Edge 2", Category.CDN, Set.of());
        nodes.delete("n1");

        for (String type : List.of("TITLE", "ALERT_RULE", "SERVICE_NODE")) {
            List<String> operations = jdbc.queryForList(
                    "select operation from control_plane.outbox where aggregate_type = ? order by created_at",
                    String.class,
                    type);
            assertThat(operations).as(type).containsExactly("CREATED", "UPDATED", "DELETED");
        }
        assertThat(count("outbox")).isEqualTo(1 + 9);
    }
}
