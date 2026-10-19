/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.Operation;
import com.streampulse.controlplane.domain.RuleViolationException;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.support.Fakes;
import com.streampulse.platform.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantServiceTest {

    private final Fakes.InMemoryTenants store = new Fakes.InMemoryTenants();
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox();
    private final TenantService service = new TenantService(store, Fakes.recorder(outbox));

    private static TenantId id(String v) {
        return TenantId.of(v);
    }

    @Test
    @DisplayName("creating a tenant stores it and announces exactly one CREATED with a full snapshot")
    void createAnnouncesOnce() {
        service.create(Tenant.draft("acme", "Acme", 10L, null));

        assertThat(store.exists(id("acme"))).isTrue();
        assertThat(outbox.events).hasSize(1);
        var event = outbox.events.getFirst();
        assertThat(event.operation()).isEqualTo(Operation.CREATED);
        assertThat(event.entityId()).isEqualTo("tenant/acme");
        assertThat(event.snapshot()).containsEntry("displayName", "Acme");
        assertThat(event.changedAt()).isEqualTo(Fakes.NOW);
    }

    @Test
    @DisplayName("a duplicate id is refused and announces nothing")
    void duplicateIsRefused() {
        service.create(Tenant.draft("acme", "Acme", null, null));
        outbox.events.clear();

        assertThatThrownBy(() -> service.create(Tenant.draft("acme", "Again", null, null)))
                .isInstanceOf(AlreadyExistsException.class);
        assertThat(outbox.events).isEmpty();
    }

    @Test
    @DisplayName("a parent that does not exist is a rule violation")
    void parentMustExist() {
        assertThatThrownBy(() -> service.create(Tenant.draft("kids", "Kids", null, "ghost")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("ghost");
        assertThat(outbox.events).isEmpty();
    }

    @Test
    @DisplayName("re-parenting a tenant under its own descendant is rejected and nothing is announced")
    void cycleIsRejectedAndNothingIsAnnounced() {
        service.create(Tenant.draft("acme", "Acme", null, null));
        service.create(Tenant.draft("kids", "Kids", null, "acme"));
        outbox.events.clear();

        assertThatThrownBy(() -> service.update(id("acme"), "Acme", null, id("kids")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("own ancestor");
        assertThat(outbox.events).isEmpty();
        assertThat(store.find(id("acme")).orElseThrow().parentTenantId()).isNull();
    }

    @Test
    @DisplayName("update announces UPDATED with the version the store assigned")
    void updateCarriesTheNewVersion() {
        service.create(Tenant.draft("acme", "Acme", null, null));
        outbox.events.clear();

        service.update(id("acme"), "Acme Inc", 5L, null);

        var event = outbox.events.getFirst();
        assertThat(event.operation()).isEqualTo(Operation.UPDATED);
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.snapshot()).containsEntry("displayName", "Acme Inc").containsEntry("monthlyEventQuota", 5L);
    }

    @Test
    @DisplayName("delete announces a tombstone event; an unknown tenant is 'not found' and announces nothing")
    void deleteAnnouncesTombstone() {
        service.create(Tenant.draft("acme", "Acme", null, null));
        outbox.events.clear();

        service.delete(id("acme"));

        assertThat(outbox.events).hasSize(1);
        assertThat(outbox.events.getFirst().isTombstone()).isTrue();

        outbox.events.clear();
        assertThatThrownBy(() -> service.delete(id("acme"))).isInstanceOf(NotFoundException.class);
        assertThat(outbox.events).isEmpty();
    }
}
