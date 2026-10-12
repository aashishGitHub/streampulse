/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import com.streampulse.query.domain.MetricUpdate;
import com.streampulse.query.domain.MetricUpdates;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class CoalescerTest {

    private static final Duration WINDOW = Duration.ofMillis(250);
    private final Coalescer coalescer = new Coalescer(WINDOW);

    private static MetricUpdate update(String id, String title, String metric, double value) {
        return MetricUpdates.of(id, "t1", title, metric, value);
    }

    @Test
    @DisplayName("a burst collapses to the last value per series, ordered by entry id")
    void burstCollapsesToLastValuePerSeries() {
        Sinks.Many<MetricUpdate> source = Sinks.many().multicast().directBestEffort();
        MetricUpdate a1 = update("1-0", "a", "ERROR_RATE", 0.1);
        MetricUpdate a2 = update("2-0", "a", "ERROR_RATE", 0.2);
        MetricUpdate b1 = update("3-0", "b", "ERROR_RATE", 0.5);
        MetricUpdate a3 = update("4-0", "a", "ERROR_RATE", 0.3);

        StepVerifier.withVirtualTime(() -> coalescer.coalesce(source.asFlux()))
                .expectSubscription()
                .then(() -> {
                    source.tryEmitNext(a1);
                    source.tryEmitNext(a2);
                    source.tryEmitNext(b1);
                    source.tryEmitNext(a3);
                })
                .expectNoEvent(Duration.ofMillis(249))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(b1, a3)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("a window with no updates emits nothing")
    void emptyWindowsEmitNothing() {
        Sinks.Many<MetricUpdate> source = Sinks.many().multicast().directBestEffort();

        StepVerifier.withVirtualTime(() -> coalescer.coalesce(source.asFlux()))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(2))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("each window starts clean: a series seen once is not re-sent")
    void aSeriesIsSentOncePerChange() {
        Sinks.Many<MetricUpdate> source = Sinks.many().multicast().directBestEffort();
        MetricUpdate first = update("1-0", "a", "ERROR_RATE", 0.1);
        MetricUpdate second = update("2-0", "a", "ERROR_RATE", 0.2);

        StepVerifier.withVirtualTime(() -> coalescer.coalesce(source.asFlux()))
                .expectSubscription()
                .then(() -> source.tryEmitNext(first))
                .thenAwait(WINDOW)
                .expectNext(first)
                .expectNoEvent(WINDOW)
                .then(() -> source.tryEmitNext(second))
                .thenAwait(WINDOW)
                .expectNext(second)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("a slow consumer gets the latest value, not a backlog")
    void slowConsumerGetsLatestNotBacklog() {
        Sinks.Many<MetricUpdate> source = Sinks.many().multicast().directBestEffort();
        MetricUpdate first = update("1-0", "a", "ERROR_RATE", 0.1);
        MetricUpdate last = update("10-0", "a", "ERROR_RATE", 1.0);

        StepVerifier.withVirtualTime(() -> coalescer.coalesce(source.asFlux()), 0)
                .expectSubscription()
                .then(() -> source.tryEmitNext(first))
                .thenAwait(WINDOW)
                .then(() -> {
                    for (int i = 2; i < 10; i++) {
                        source.tryEmitNext(update(i + "-0", "a", "ERROR_RATE", i / 10.0));
                    }
                })
                .thenAwait(WINDOW.multipliedBy(8))
                .then(() -> source.tryEmitNext(last))
                .thenAwait(WINDOW.multipliedBy(8))
                .thenRequest(1)
                .expectNext(first)
                .thenRequest(1)
                .thenAwait(WINDOW)
                .expectNext(last)
                .thenCancel()
                .verify();
    }
}
