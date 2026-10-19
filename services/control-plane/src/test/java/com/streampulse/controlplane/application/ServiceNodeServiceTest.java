/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.RuleViolationException;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.support.Fakes;
import com.streampulse.platform.domain.TenantId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceNodeServiceTest {

    private static final TenantId ACME = TenantId.of("acme");

    private final Fakes.InMemoryTenants tenants = new Fakes.InMemoryTenants();
    private final Fakes.InMemoryNodes store = new Fakes.InMemoryNodes();
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox();
    private final ServiceNodeService service = new ServiceNodeService(store, tenants, Fakes.recorder(outbox));

    @BeforeEach
    void tenantsExist() {
        tenants.insert(Tenant.draft("acme", "Acme", null, null));
        tenants.insert(Tenant.draft("other", "Other", null, null));
    }

    private static ServiceNode node(String id, Category category, String... deps) {
        return new ServiceNode(id, ACME, id, category, Set.of(deps), 0);
    }

    @Test
    @DisplayName("a CDN -> packager -> player chain is stored; each node announces once, as an unknown-kind entity key")
    void chain() {
        service.create(node("cdn", Category.CDN));
        service.create(node("packager", Category.PACKAGER, "cdn"));
        service.create(node("player", Category.PLAYER, "packager"));

        assertThat(outbox.events).hasSize(3);
        assertThat(outbox.events.get(2).entityId()).isEqualTo("service-node/acme/player");
        assertThat(outbox.events.get(2).snapshot()).containsEntry("entityKind", "SERVICE_NODE");
    }

    @Test
    @DisplayName("a dependency on a node of another tenant is unknown to this tenant")
    void foreignTenantDependency() {
        service.create(new ServiceNode("theirs", TenantId.of("other"), "t", Category.CDN, Set.of(), 0));

        assertThatThrownBy(() -> service.create(node("mine", Category.PACKAGER, "theirs")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("another tenant");
        assertThat(outbox.events).hasSize(1);
    }

    @Test
    @DisplayName("pointing the CDN at the player closes a loop and is rejected, leaving the store unchanged")
    void updateClosingALoop() {
        service.create(node("cdn", Category.CDN));
        service.create(node("packager", Category.PACKAGER, "cdn"));
        service.create(node("player", Category.PLAYER, "packager"));
        outbox.events.clear();

        assertThatThrownBy(() -> service.update("cdn", "cdn", Category.CDN, Set.of("player")))
                .isInstanceOf(RuleViolationException.class);

        assertThat(store.rows.get("cdn").dependsOn()).isEmpty();
        assertThat(outbox.events).isEmpty();
    }
}
