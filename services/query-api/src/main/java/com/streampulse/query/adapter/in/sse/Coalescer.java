/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import com.streampulse.query.domain.MetricUpdate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * Collapses bursts: every {@code window} it emits the LATEST update of each series, ordered by
 * entry id. One instance per client connection stream, so a slow client costs one map entry per
 * series instead of a queue.
 */
public final class Coalescer {

    private final Duration window;

    public Coalescer(Duration window) {
        this.window = window;
    }

    public Flux<MetricUpdate> coalesce(Flux<MetricUpdate> source) {
        return Flux.defer(() -> {
            Map<String, MetricUpdate> pending = new ConcurrentHashMap<>();
            Flux<Long> intake = source.doOnNext(update -> pending.put(update.seriesKey(), update))
                    .ignoreElements()
                    .cast(Long.class)
                    .flux();
            // WHY the map is drained when a tick is PROCESSED, not when it fires: a tick that waits for a slow
            // client queues only a Long, and the first one to be processed takes whatever is newest. The backlog
            // therefore holds at most one batch in flight, never one batch per missed window.
            Flux<Long> ticks = Flux.interval(window).onBackpressureDrop();
            return Flux.merge(ticks, intake).concatMapIterable(tick -> drain(pending), 1);
        });
    }

    private static List<MetricUpdate> drain(Map<String, MetricUpdate> pending) {
        List<MetricUpdate> latest = new ArrayList<>();
        for (String key : List.copyOf(pending.keySet())) {
            MetricUpdate update = pending.remove(key);
            if (update != null) {
                latest.add(update);
            }
        }
        latest.sort(Comparator.comparing(MetricUpdate::id));
        return latest;
    }
}
