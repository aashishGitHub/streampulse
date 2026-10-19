/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.ChangeEvent;
import com.streampulse.controlplane.domain.ChangeIdGenerator;
import com.streampulse.controlplane.domain.ConfigChangeOutbox;
import com.streampulse.controlplane.domain.ConfigEntity;
import com.streampulse.controlplane.domain.Operation;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * The single place a business change becomes an announcement. Services call it inside their own
 * transaction, AFTER the change has been written (so the entity's version is the new one).
 */
@Component
public class ChangeRecorder {

    private final ConfigChangeOutbox outbox;
    private final Clock clock;
    private final ChangeIdGenerator ids;

    public ChangeRecorder(ConfigChangeOutbox outbox, Clock clock, ChangeIdGenerator ids) {
        this.outbox = outbox;
        this.clock = clock;
        this.ids = ids;
    }

    public void created(ConfigEntity entity) {
        record(Operation.CREATED, entity);
    }

    public void updated(ConfigEntity entity) {
        record(Operation.UPDATED, entity);
    }

    public void deleted(ConfigEntity entity) {
        record(Operation.DELETED, entity);
    }

    private void record(Operation operation, ConfigEntity entity) {
        outbox.append(ChangeEvent.of(ids.next(), operation, entity, clock.instant()));
    }
}
