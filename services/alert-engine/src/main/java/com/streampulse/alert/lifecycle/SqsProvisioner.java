/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.lifecycle;

import com.streampulse.alert.adapter.out.sqs.SqsAlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Creates the alert queue at start-up. Failure is logged, not fatal: the queue is then created on
 * first use, and an SQS outage must not stop alerts reaching Kafka.
 */
public final class SqsProvisioner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsProvisioner.class);

    private final SqsAlertChannel channel;
    private volatile boolean running;

    public SqsProvisioner(SqsAlertChannel channel) {
        this.channel = channel;
    }

    @Override
    public void start() {
        try {
            log.info("alert queue ready at {}", channel.ensureQueue());
        } catch (SdkException e) {
            log.warn("could not create the alert queue at start-up; will retry on first alert: {}", e.toString());
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
