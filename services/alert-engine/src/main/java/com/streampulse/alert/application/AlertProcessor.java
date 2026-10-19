/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import com.streampulse.alert.domain.Alert;
import com.streampulse.alert.domain.AlertEngine;
import com.streampulse.alert.domain.AlertRule;
import com.streampulse.alert.domain.Observation;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The use case: evaluate, record what changed, deliver it. One lock spans the three steps so that
 * two threads (the aggregate listener and the absence sweeper) cannot add changes to the outbox in
 * a different order from the one the engine produced them.
 *
 * <p>{@link #process} drains the outbox even when the observation changed nothing: that is how a
 * redelivered aggregate finishes a delivery that failed the first time.
 */
public class AlertProcessor {

    private final AlertEngine engine;
    private final AlertOutbox outbox;
    private final ReentrantLock lock = new ReentrantLock();

    public AlertProcessor(AlertEngine engine, AlertOutbox outbox) {
        this.engine = engine;
        this.outbox = outbox;
    }

    /** @throws DeliveryException if a channel did not accept every change; state is already updated */
    public void process(Observation observation) {
        run(() -> engine.evaluate(observation));
    }

    public void sweep() {
        run(engine::sweep);
    }

    public void ruleRemoved(AlertRule rule) {
        run(() -> engine.retire(rule));
    }

    public List<Alert> activeAlerts(String tenantId) {
        return engine.activeAlerts(tenantId);
    }

    private void run(java.util.function.Supplier<List<Alert>> step) {
        lock.lock();
        try {
            outbox.add(step.get());
            outbox.drain();
        } finally {
            lock.unlock();
        }
    }
}
