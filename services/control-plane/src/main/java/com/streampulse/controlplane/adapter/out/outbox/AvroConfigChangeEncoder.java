/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.ChangeOperation;
import com.streampulse.contracts.v1.ConfigChange;
import com.streampulse.contracts.v1.EntityType;
import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.ConfigKind;
import com.streampulse.controlplane.domain.Operation;
import org.springframework.stereotype.Component;

/** domain {@link ChangeEvent} to the bytes the relay puts on the topic; {@code null} for a tombstone. */
@Component
public class AvroConfigChangeEncoder {

    private final AvroBinaryCodec<ConfigChange> codec = AvroBinaryCodec.of(ConfigChange.class);
    private final ObjectMapper json;

    public AvroConfigChangeEncoder(ObjectMapper json) {
        this.json = json;
    }

    /** @return the raw Avro record, or null: the delete's whole message is the absence of a value */
    public byte[] encode(ChangeEvent event) {
        if (event.isTombstone()) {
            return null;
        }
        return codec.encode(ConfigChange.newBuilder()
                .setChangeId(event.changeId().toString())
                .setEntityType(entityType(event.kind()))
                .setEntityId(event.entityId())
                .setTenantId(event.tenantId())
                .setOperation(operation(event.operation()))
                .setPayload(snapshotJson(event))
                .setVersion(event.version())
                .setChangedAt(event.changedAt())
                .setChangedBy(null)
                .build());
    }

    /** SERVICE_NODE has no symbol in the contract; the snapshot still says what it is. */
    static EntityType entityType(ConfigKind kind) {
        return switch (kind) {
            case TENANT -> EntityType.TENANT;
            case TITLE -> EntityType.TITLE;
            case ALERT_RULE -> EntityType.ALERT_RULE;
            case SERVICE_NODE -> EntityType.ENTITY_UNKNOWN;
        };
    }

    private static ChangeOperation operation(Operation operation) {
        return switch (operation) {
            case CREATED -> ChangeOperation.CREATED;
            case UPDATED -> ChangeOperation.UPDATED;
            case DELETED -> ChangeOperation.DELETED;
        };
    }

    private String snapshotJson(ChangeEvent event) {
        try {
            return json.writeValueAsString(event.snapshot());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("snapshot of " + event.entityId() + " is not serialisable", e);
        }
    }
}
