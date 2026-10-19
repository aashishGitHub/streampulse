/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.lifecycle;

import com.streampulse.alert.application.AlertProcessor;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Drives time-based rules (absence) on a fixed delay. A failed sweep is logged and retried next tick. */
public final class AbsenceSweeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AbsenceSweeper.class);

    private final AlertProcessor processor;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    public AbsenceSweeper(AlertProcessor processor, Duration interval) {
        this.processor = processor;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("absence-sweeper").daemon().unstarted(r));
        scheduler.scheduleWithFixedDelay(this::sweep, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    // scheduleWithFixedDelay cancels all future runs if one throws, so nothing may escape.
    private void sweep() {
        try {
            processor.sweep();
        } catch (RuntimeException e) {
            log.warn("absence sweep failed, will retry", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
