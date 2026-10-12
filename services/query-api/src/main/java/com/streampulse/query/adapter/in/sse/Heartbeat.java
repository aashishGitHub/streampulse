/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import java.time.Duration;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/** An SSE comment line carries no data and no id: it only keeps proxies from closing an idle connection. */
public final class Heartbeat {

    private static final String COMMENT = "keep-alive";

    private final Duration period;

    public Heartbeat(Duration period) {
        this.period = period;
    }

    public <T> Flux<ServerSentEvent<T>> merge(Flux<ServerSentEvent<T>> events) {
        Flux<ServerSentEvent<T>> beats = Flux.interval(period)
                .onBackpressureDrop()
                .map(tick -> ServerSentEvent.<T>builder().comment(COMMENT).build());
        return Flux.merge(events, beats);
    }
}
