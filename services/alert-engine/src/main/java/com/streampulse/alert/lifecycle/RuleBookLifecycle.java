/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.lifecycle;

import com.streampulse.alert.adapter.in.kafka.AggregateListener;
import com.streampulse.alert.adapter.in.kafka.RuleBookLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Starts the rule book loader, and the aggregate listener only once the book has caught up with the
 * config topic. The listener container is declared with auto-startup off for exactly this reason.
 *
 * <p>Phase: below the listener containers', so on shutdown the containers stop first and the loader
 * after them.
 */
public final class RuleBookLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RuleBookLifecycle.class);

    private final RuleBookLoader loader;
    private final KafkaListenerEndpointRegistry registry;
    private volatile boolean running;

    public RuleBookLifecycle(RuleBookLoader loader, KafkaListenerEndpointRegistry registry) {
        this.loader = loader;
        this.registry = registry;
    }

    @Override
    public void start() {
        running = true;
        loader.start(this::startAggregateListener);
    }

    private void startAggregateListener() {
        MessageListenerContainer container = registry.getListenerContainer(AggregateListener.ID);
        if (container == null) {
            throw new IllegalStateException("no listener container " + AggregateListener.ID);
        }
        container.start();
        log.info("aggregate listener started");
    }

    @Override
    public void stop() {
        loader.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE / 2;
    }
}
