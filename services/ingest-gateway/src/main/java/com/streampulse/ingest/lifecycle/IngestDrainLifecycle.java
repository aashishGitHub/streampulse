/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.lifecycle;

import com.streampulse.ingest.application.IngestService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Ordered shutdown, so a deploy loses nothing. BS-01 / REQ-LC-01, REQ-IG-27.
 *
 * <h2>The failure this prevents</h2>
 *
 * <pre>
 *   Without an ordered drain:
 *     SIGTERM → JVM starts exiting → in-flight batches are abandoned mid-publish
 *             → the client got no response, and the events never reached the stream
 *             → on a rolling deploy this happens on EVERY pod, every time
 * </pre>
 *
 * <h2>Why SmartLifecycle and not {@code @PreDestroy}</h2>
 *
 * {@code @PreDestroy} gives no ordering between unrelated beans, no timeout, and no async
 * completion callback. All three are needed here: readiness must flip <b>before</b> the
 * connectors stop, and the drain must be bounded so Kubernetes does not SIGKILL us
 * mid-flush.
 *
 * <p>{@code SmartLifecycle} phases stop in <b>descending</b> order, so a higher phase means
 * "stop earlier":
 *
 * <pre>
 *   phase 300  readiness → REFUSING_TRAFFIC   ← stops FIRST: the load balancer
 *                                                removes us before we refuse anything
 *   phase 200  HTTP connector (Boot's graceful shutdown)
 *   phase 100  THIS bean: wait for in-flight batches to finish
 *   phase   0  clients close
 * </pre>
 *
 * <h2>The race that ordering alone does not fix</h2>
 *
 * Flipping readiness does not instantly stop traffic: the endpoint removal has to propagate
 * through the load balancer, which takes a moment. During that window requests still arrive
 * and must still be served — which is exactly why the HTTP connector stops <em>after</em>
 * readiness, not at the same time. In Kubernetes a {@code preStop} sleep covers the same
 * gap; Phase 11 adds it.
 */
@Component
public class IngestDrainLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestDrainLifecycle.class);

    /** Lower than the HTTP connector's, so this stops AFTER it — nothing new arrives while we drain. */
    private static final int PHASE = 100;

    private final IngestService ingestService;
    private final ApplicationContext context;
    private final int maxInFlight;
    private final Duration drainTimeout;

    private volatile boolean running;

    public IngestDrainLifecycle(
            IngestService ingestService, ApplicationContext context, int maxInFlight, Duration drainTimeout) {
        this.ingestService = ingestService;
        this.context = context;
        this.maxInFlight = maxInFlight;
        this.drainTimeout = drainTimeout;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
        log.info("Ingest drain lifecycle armed (phase {}, timeout {})", PHASE, drainTimeout);
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        try {
            // Tell Kubernetes to stop sending traffic. Readiness and liveness are DIFFERENT
            // questions: readiness says "don't send me work", liveness says "I'm broken,
            // restart me". Flipping liveness here would cause a restart instead of a drain.
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

            drain();
        } finally {
            // ALWAYS signal completion, or the container waits out the whole
            // timeout-per-shutdown-phase for a bean that has already finished.
            callback.run();
        }
    }

    /**
     * Waits until every permit is back, meaning no batch is mid-publish.
     *
     * <p>The permits ARE the in-flight count: {@code IngestService} takes one per batch and
     * releases it in a {@code finally}. When all are available, nothing is in flight.
     */
    private void drain() {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        int inFlight = maxInFlight - ingestService.availablePermits();

        if (inFlight == 0) {
            log.info("Nothing in flight; shutting down immediately");
            return;
        }
        log.info("Draining {} in-flight batch(es), up to {}", inFlight, drainTimeout);

        while (System.nanoTime() < deadline) {
            if (ingestService.availablePermits() == maxInFlight) {
                log.info("Drain complete; all batches finished");
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                // Restore the flag and stop waiting. Swallowing an interrupt during shutdown
                // makes the thread un-cancellable, which is the opposite of what we want.
                Thread.currentThread().interrupt();
                log.warn("Drain interrupted");
                return;
            }
        }

        // Timed out. Log LOUDLY: this is the case where a deploy may actually lose work,
        // and it should be visible rather than silently absorbed.
        int stillRunning = maxInFlight - ingestService.availablePermits();
        log.error(
                "Drain TIMED OUT with {} batch(es) still in flight after {}. "
                        + "Those clients will not receive a 202 and must retry; idempotency makes that safe.",
                stillRunning,
                drainTimeout);
    }
}
