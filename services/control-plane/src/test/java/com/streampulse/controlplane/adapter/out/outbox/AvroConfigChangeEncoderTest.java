/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.Operation;
import com.streampulse.controlplane.domain.ServiceNode;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.platform.domain.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvroConfigChangeEncoderTest {

    private static final Instant AT = Instant.parse("2026-10-12T10:00:00.123Z");
    private final AvroConfigChangeEncoder encoder = new AvroConfigChangeEncoder(new ObjectMapper());
    private final AvroBinaryCodec<ConfigChange> codec = AvroBinaryCodec.of(ConfigChange.class);

    private static Map<String, Object> json(ConfigChange decoded) throws Exception {
        return new ObjectMapper().readValue(decoded.getPayload().toString(), new TypeReference<>() {});
    }

    @Test
    @DisplayName("bytes decode to the contract record with every field as specified")
    void roundTrip() throws Exception {
        var id = UUID.randomUUID();
        var tenant = new Tenant(TenantId.of("acme"), "Acme", null, TenantId.of("root"), 3);

        byte[] bytes = encoder.encode(ChangeEvent.of(id, Operation.UPDATED, tenant, AT));

        ConfigChange decoded = codec.decode(bytes);
        assertThat(decoded.getChangeId().toString()).isEqualTo(id.toString());
        assertThat(decoded.getEntityType()).isEqualTo(EntityType.TENANT);
        assertThat(decoded.getEntityId().toString()).isEqualTo("tenant/acme");
        assertThat(decoded.getTenantId().toString()).isEqualTo("acme");
        assertThat(decoded.getOperation()).isEqualTo(ChangeOperation.UPDATED);
        assertThat(decoded.getVersion()).isEqualTo(3);
        assertThat(decoded.getChangedAt()).isEqualTo(AT);
        assertThat(decoded.getChangedBy()).isNull();

        Map<String, Object> payload = json(decoded);
        assertThat(payload)
                .as("a full snapshot, null fields included, not a delta")
                .containsEntry("displayName", "Acme")
                .containsEntry("parentTenantId", "root")
                .containsKey("monthlyEventQuota")
                .containsEntry("version", 3);
        assertThat(payload.get("monthlyEventQuota")).isNull();
    }

    @Test
    @DisplayName("a delete has no bytes at all: the relay turns null into a tombstone")
    void deleteIsNull() {
        var event = ChangeEvent.of(
                UUID.randomUUID(), Operation.DELETED, new Tenant(TenantId.of("acme"), "Acme", null, null, 4), AT);

        assertThat(encoder.encode(event)).isNull();
    }

    @Test
    @DisplayName(
            "a service node has no entity type in the contract: ENTITY_UNKNOWN, with the real kind in the snapshot")
    void serviceNodeIsUnknownToTheContract() throws Exception {
        var node = new ServiceNode("cdn-1", TenantId.of("acme"), "Edge", Category.CDN, Set.of(), 0);

        ConfigChange decoded =
                codec.decode(encoder.encode(ChangeEvent.of(UUID.randomUUID(), Operation.CREATED, node, AT)));

        assertThat(decoded.getEntityType()).isEqualTo(EntityType.ENTITY_UNKNOWN);
        assertThat(decoded.getEntityId().toString()).isEqualTo("service-node/acme/cdn-1");
        assertThat(json(decoded)).containsEntry("entityKind", "SERVICE_NODE");
    }

    @Test
    @DisplayName("the snapshot JSON is stable: keys keep the order the entity wrote them in")
    void stableKeyOrder() {
        var tenant = new Tenant(TenantId.of("acme"), "Acme", 1L, null, 0);

        ConfigChange decoded =
                codec.decode(encoder.encode(ChangeEvent.of(UUID.randomUUID(), Operation.CREATED, tenant, AT)));

        assertThat(decoded.getPayload().toString())
                .isEqualTo("{\"entityKind\":\"TENANT\",\"tenantId\":\"acme\",\"displayName\":\"Acme\","
                        + "\"monthlyEventQuota\":1,\"parentTenantId\":null,\"version\":0}");
    }
}
