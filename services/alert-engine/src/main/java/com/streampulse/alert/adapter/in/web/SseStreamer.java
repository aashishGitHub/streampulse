/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.adapter.in.web;

import com.streampulse.alert.application.AlertFeed;
import com.streampulse.alert.application.AlertView;
import java.io.IOException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One virtual thread per open stream drains that subscriber's queue into an {@link SseEmitter}.
 * Writing to a socket therefore happens on a thread that belongs to the client, never on the Kafka
 * consumer thread that published the alert.
 *
 * <p>The same loop sends the heartbeat: when nothing arrives within the interval it writes a comment
 * line, which keeps proxies from closing an idle connection and lets the server notice a client that
 * has gone, because the write fails.
 */
public final class SseStreamer {

    private static final Logger log = LoggerFactory.getLogger(SseStreamer.class);

    private final AlertFeed feed;
    private final Executor executor;
    private final Duration heartbeat;
    private final Duration maxLifetime;

    public SseStreamer(AlertFeed feed, Executor executor, Duration heartbeat, Duration maxLifetime) {
        this.feed = feed;
        this.executor = executor;
        this.heartbeat = heartbeat;
        this.maxLifetime = maxLifetime;
    }

    public SseEmitter open(String tenantId, OptionalLong lastEventId) {
        // The lifetime is bounded on purpose: EventSource reconnects with Last-Event-ID, so a
        // periodic end costs the client nothing and releases stuck connections.
        SseEmitter emitter = new SseEmitter(maxLifetime.toMillis());
        AlertFeed.Subscription subscription = feed.subscribe(tenantId, lastEventId);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());

        executor.execute(() -> pump(emitter, subscription));
        return emitter;
    }

    private void pump(SseEmitter emitter, AlertFeed.Subscription subscription) {
        try (subscription) {
            emitter.send(SseEmitter.event().comment("connected"));
            while (!subscription.isEnded()) {
                AlertFeed.Entry entry = subscription.next(heartbeat);
                if (entry != null) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(entry.sequence()))
                            .name("alert")
                            .data(AlertView.from(entry.alert()), MediaType.APPLICATION_JSON));
                } else if (!subscription.isEnded()) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                }
            }
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("stream ended: {}", e.toString());
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        }
    }
}
