/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.lifecycle;

import com.streampulse.sink.application.batch.BatchWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

/**
 * Closes the batch writer AFTER the Kafka listener container has stopped.
 *
 * <p>SmartLifecycle beans stop in descending phase order. The container stops first, so no new
 * poll arrives; this bean then closes the writer, which flushes what the last poll left in
 * the queue. Closing the writer first would reject the records the container is still handing it.
 *
 * <pre>
 *   phase DEFAULT_PHASE         Kafka listener containers   ← stops first
 *   phase DEFAULT_PHASE - 1000  THIS bean: drain + flush     ← then this
 *   (bean destruction)          Hikari, Lettuce             ← connections last
 * </pre>
 */
public class BatchWriterLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BatchWriterLifecycle.class);

    static final int PHASE = AbstractMessageListenerContainer.DEFAULT_PHASE - 1000;

    private final BatchWriter<?> writer;
    private volatile boolean running;

    public BatchWriterLifecycle(BatchWriter<?> writer) {
        this.writer = writer;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        log.info("Closing the batch writer ({} record(s) queued)", writer.queueDepth());
        writer.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
