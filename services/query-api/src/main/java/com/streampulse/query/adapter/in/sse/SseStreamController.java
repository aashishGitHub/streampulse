/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import com.streampulse.query.application.InvalidRequestException;
import com.streampulse.query.domain.EntryId;
import com.streampulse.query.domain.MetricUpdate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

@RestController
public class SseStreamController {

    /** Entries a resuming client can accumulate between subscribing and the end of its replay read. */
    private static final int SEAM_BUFFER = 1024;

    private final LiveFeed feed;
    private final Coalescer coalescer;
    private final Heartbeat heartbeat;

    public SseStreamController(LiveFeed feed, Coalescer coalescer, Heartbeat heartbeat) {
        this.feed = feed;
        this.coalescer = coalescer;
        this.heartbeat = heartbeat;
    }

    @GetMapping(path = "/v1/stream/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<MetricEvent>> stream(
            @RequestParam String tenantId,
            @RequestParam(required = false) List<String> metrics,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        EntryId resumeAfter = parseResumePoint(lastEventId);
        Flux<MetricUpdate> live = feed.live().filter(update -> wanted(update, tenantId, metrics));

        Flux<MetricUpdate> updates =
                resumeAfter == null ? coalescer.coalesce(live) : resume(live, resumeAfter, tenantId, metrics);

        return heartbeat.merge(updates.map(SseStreamController::toEvent));
    }

    /**
     * Replay, then live, with neither a gap nor a duplicate at the seam:
     * the live feed is subscribed BEFORE the range read starts, so anything published during the read is
     * held; and live entries at or below the last replayed id are dropped, so anything the read already
     * returned is not sent twice.
     */
    private Flux<MetricUpdate> resume(Flux<MetricUpdate> live, EntryId after, String tenantId, List<String> metrics) {
        return Flux.defer(() -> {
            ConnectableFlux<MetricUpdate> held = live.replay(SEAM_BUFFER);
            Disposable subscription = held.connect();
            AtomicReference<EntryId> lastSent = new AtomicReference<>(after);

            Flux<MetricUpdate> replayed = feed.replayAfter(after)
                    .filter(update -> wanted(update, tenantId, metrics))
                    .filter(update -> update.id().compareTo(lastSent.get()) > 0)
                    .doOnNext(update -> lastSent.set(update.id()));
            Flux<MetricUpdate> afterTheSeam =
                    Flux.defer(() -> held.filter(update -> update.id().compareTo(lastSent.get()) > 0));

            return Flux.concat(replayed, coalescer.coalesce(afterTheSeam)).doFinally(signal -> subscription.dispose());
        });
    }

    private static EntryId parseResumePoint(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        try {
            return EntryId.parse(lastEventId);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidRequestException("Last-Event-ID is not a stream entry id: " + lastEventId);
        }
    }

    private static boolean wanted(MetricUpdate update, String tenantId, List<String> metrics) {
        return update.tenantId().equals(tenantId)
                && (metrics == null || metrics.isEmpty() || metrics.contains(update.metric()));
    }

    private static ServerSentEvent<MetricEvent> toEvent(MetricUpdate update) {
        return ServerSentEvent.builder(MetricEvent.of(update))
                .id(update.id().toString())
                .event("metric")
                .build();
    }
}
