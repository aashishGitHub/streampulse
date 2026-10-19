/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.out.kafka;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.WatchEvent;
import com.streampulse.feed.application.WatchPublisher;
import com.streampulse.feed.application.WatchUnavailableException;
import com.streampulse.feed.domain.WatchStarted;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/** Key = userId (api/asyncapi: activityWatch), so one user's events stay in order on one partition. */
public final class KafkaWatchPublisher implements WatchPublisher {

    private final KafkaTemplate<String, byte[]> template;
    private final String topic;
    private final Duration timeout;
    private final AvroBinaryCodec<WatchEvent> codec = AvroBinaryCodec.of(WatchEvent.class);

    public KafkaWatchPublisher(KafkaTemplate<String, byte[]> template, String topic, Duration timeout) {
        this.template = template;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(WatchStarted watch) {
        byte[] value = codec.encode(WatchEvent.newBuilder()
                .setEventId(watch.eventId().toString())
                .setUserId(watch.userId().value())
                .setTitleId(watch.titleId().value())
                .setOccurredAt(watch.occurredAt())
                .build());
        try {
            // Waits for the broker (acks=all): 202 means the event is recorded, not merely handed to a buffer.
            template.send(topic, watch.userId().value(), value).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new WatchUnavailableException("could not record the watch event on " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WatchUnavailableException("interrupted while recording the watch event", e);
        }
    }
}
