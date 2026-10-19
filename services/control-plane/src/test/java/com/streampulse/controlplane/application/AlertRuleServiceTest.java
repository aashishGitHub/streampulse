/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.controlplane.domain.AlertRule;
import com.streampulse.controlplane.domain.AlertSeverity;
import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.Comparison;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.RuleViolationException;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.support.Fakes;
import com.streampulse.platform.domain.TenantId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertRuleServiceTest {

    private static final TenantId ACME = TenantId.of("acme");

    private final Fakes.InMemoryTenants tenants = new Fakes.InMemoryTenants();
    private final Fakes.InMemoryRules store = new Fakes.InMemoryRules();
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox();
    private final AlertRuleService service = new AlertRuleService(store, tenants, Fakes.recorder(outbox));

    @BeforeEach
    void tenantExists() {
        tenants.insert(Tenant.draft("acme", "Acme", null, null));
    }

    private static AlertRule rule(String id, Category category, Category... upstream) {
        return new AlertRule(
                id, ACME, "error_rate", Comparison.GT, 0.1, AlertSeverity.WARNING, category, Set.of(upstream), 0);
    }

    @Test
    @DisplayName("a valid rule is stored and announced once")
    void createAnnounces() {
        service.create(rule("r1", Category.PACKAGER, Category.CDN));

        assertThat(store.rows).containsKey("r1");
        assertThat(outbox.events).hasSize(1);
        assertThat(outbox.events.getFirst().entityId()).isEqualTo("r1");
        assertThat(outbox.events.getFirst().snapshot()).containsEntry("dependsOn", java.util.List.of("CDN"));
    }

    @Test
    @DisplayName("a rule that closes a category cycle is rejected: nothing stored, nothing announced")
    void ruleCycleIsRejected() {
        service.create(rule("r1", Category.PLAYER, Category.PACKAGER));
        outbox.events.clear();

        assertThatThrownBy(() -> service.create(rule("r2", Category.PACKAGER, Category.PLAYER)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("cycle");
        assertThat(store.rows).doesNotContainKey("r2");
        assertThat(outbox.events).isEmpty();
    }

    @Test
    @DisplayName("updating a rule is checked against the OTHER rules, not against its own previous edges")
    void updateIgnoresItsOwnPreviousVersion() {
        service.create(rule("r1", Category.PLAYER, Category.PACKAGER));
        service.create(rule("r2", Category.PACKAGER, Category.CDN));

        // r1 flips direction: it no longer depends on PACKAGER. Judged against its own old edge it would
        // look like the old edge and the new edge together; judged against the others it is fine.
        service.update("r1", "error_rate", Comparison.GT, 0.1, AlertSeverity.WARNING, Category.CDN, Set.of());

        assertThat(store.rows.get("r1").category()).isEqualTo(Category.CDN);
    }

    @Test
    @DisplayName("an unknown tenant, a duplicate id and an unknown rule are distinct errors")
    void referenceErrors() {
        assertThatThrownBy(() ->
                        service.create(new AlertRule("r9", TenantId.of("ghost"), "m", null, 1, null, null, null, 0)))
                .isInstanceOf(NotFoundException.class);

        service.create(rule("r1", null));
        assertThatThrownBy(() -> service.create(rule("r1", null))).isInstanceOf(AlreadyExistsException.class);
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("delete announces a tombstone event")
    void deleteIsATombstone() {
        service.create(rule("r1", null));
        outbox.events.clear();

        service.delete("r1");

        assertThat(store.rows).isEmpty();
        assertThat(outbox.events.getFirst().isTombstone()).isTrue();
    }
}
