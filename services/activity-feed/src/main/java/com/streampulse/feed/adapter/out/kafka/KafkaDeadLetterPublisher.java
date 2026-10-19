/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.out.kafka;

import com.streampulse.feed.application.DeadLetterPort;
import com.streampulse.feed.application.DeadLetterUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/** Parks the original bytes and key, untouched, with the reason in a header (api/asyncapi: activityWatchDlt). */
public final class KafkaDeadLetterPublisher implements DeadLetterPort {

    static final String REASON_HEADER = "dlt-reason";

    private final KafkaTemplate<String, byte[]> template;
    private final String topic;
    private final Duration sendTimeout;

    public KafkaDeadLetterPublisher(KafkaTemplate<String, byte[]> template, String topic, Duration sendTimeout) {
        this.template = template;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void deadLetter(String key, byte[] value, String reason) {
        var out = new ProducerRecord<String, byte[]>(topic, key, value);
        out.headers().add(REASON_HEADER, reason.getBytes(StandardCharsets.UTF_8));
        try {
            // Waits for the broker: a record is only "parked" once the DLT has it.
            template.send(out).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new DeadLetterUnavailableException("could not park a record on " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeadLetterUnavailableException("interrupted while parking a record", e);
        }
    }
}
