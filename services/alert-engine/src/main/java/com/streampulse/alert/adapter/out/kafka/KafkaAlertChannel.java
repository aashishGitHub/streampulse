/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.out.kafka;

import com.streampulse.alert.application.AlertChannel;
import com.streampulse.alert.application.DeliveryException;
import com.streampulse.alert.domain.Alert;
import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.AlertEvent;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes to {@code alerts.v1} keyed by {@code tenantId:ruleId}. The send is awaited: the
 * aggregate must not be acknowledged until the broker has the alert (the producer is idempotent with
 * {@code acks=all}, so a retried send cannot duplicate within a session).
 */
public final class KafkaAlertChannel implements AlertChannel {

    private final KafkaTemplate<String, byte[]> template;
    private final String topic;
    private final Duration timeout;
    private final AlertEventMapper mapper;
    private final AvroBinaryCodec<AlertEvent> codec = AvroBinaryCodec.of(AlertEvent.class);

    public KafkaAlertChannel(
            KafkaTemplate<String, byte[]> template, String topic, Duration timeout, AlertEventMapper mapper) {
        this.template = template;
        this.topic = topic;
        this.timeout = timeout;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "kafka";
    }

    @Override
    public void deliver(Alert alert) {
        byte[] value = codec.encode(mapper.toAvro(alert));
        try {
            template.send(topic, alert.key().topicKey(), value).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException("interrupted publishing alert " + alert.alertId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeliveryException("could not publish alert " + alert.alertId() + " to " + topic, e);
        }
    }
}
