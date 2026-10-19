/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One announcement. The snapshot keeps insertion order and may hold null values (an absent quota is
 * part of the full snapshot), which {@code Map.copyOf} would reject.
 *
 * A create or update carries the FULL snapshot; a delete carries none, which the
 * relay turns into a tombstone.
 */
public record ChangeEvent(
        UUID changeId,
        ConfigKind kind,
        String entityId,
        String tenantId,
        Operation operation,
        Map<String, Object> snapshot,
        long version,
        Instant changedAt) {

    public ChangeEvent {
        if (operation == Operation.DELETED) {
            if (snapshot != null) {
                throw new IllegalArgumentException("a DELETED announcement carries no snapshot");
            }
        } else if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException("a " + operation + " announcement needs a full snapshot");
        }
        snapshot = snapshot == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    public static ChangeEvent of(UUID changeId, Operation operation, ConfigEntity entity, Instant at) {
        return new ChangeEvent(
                changeId,
                entity.configKind(),
                entity.entityId(),
                entity.owningTenant(),
                operation,
                operation == Operation.DELETED ? null : entity.snapshot(),
                entity.version(),
                at);
    }

    public boolean isTombstone() {
        return operation == Operation.DELETED;
    }
}
