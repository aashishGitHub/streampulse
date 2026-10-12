/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.query.domain.EntryId;
import com.streampulse.query.domain.MetricUpdate;
import com.streampulse.query.domain.MetricUpdates;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** The resume logic against a fake feed: fast, deterministic, no Docker. SseResumeIT proves it against Redis. */
class SseStreamControllerTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private static MetricUpdate update(String id, String tenant, double value) {
        return MetricUpdates.of(id, tenant, "title-1", "ERROR_RATE", value);
    }

    private static final class FakeFeed implements LiveFeed {
        final Sinks.Many<MetricUpdate> hot = Sinks.many().multicast().directBestEffort();
        Flux<MetricUpdate> replay;

        FakeFeed(Flux<MetricUpdate> replay) {
            this.replay = replay;
        }

        @Override
        public Flux<MetricUpdate> live() {
            return hot.asFlux();
        }

        @Override
        public Flux<MetricUpdate> replayAfter(EntryId after) {
            return replay;
        }
    }

    private static SseStreamController controller(FakeFeed feed) {
        return new SseStreamController(feed, new Coalescer(Duration.ofMillis(20)), new Heartbeat(Duration.ofHours(1)));
    }

    private static String idOf(ServerSentEvent<?> event) {
        return event.id();
    }

    @Test
    @DisplayName("without Last-Event-ID the client gets only its own tenant's live entries, labelled with the entry id")
    void liveOnlyAndTenantFiltered() {
        var feed = new FakeFeed(Flux.empty());

        StepVerifier.create(controller(feed).stream("t1", null, null))
                .then(() -> {
                    feed.hot.tryEmitNext(update("5-0", "t2", 0.9));
                    feed.hot.tryEmitNext(update("6-0", "t1", 0.1));
                })
                .assertNext(event -> {
                    assertThat(idOf(event)).isEqualTo("6-0");
                    assertThat(event.event()).isEqualTo("metric");
                    assertThat(event.data().value()).isEqualTo(0.1);
                })
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    @DisplayName("named metrics narrow the stream")
    void metricsFilter() {
        var feed = new FakeFeed(Flux.empty());

        StepVerifier.create(controller(feed).stream("t1", List.of("CONCURRENT_VIEWERS"), null))
                .then(() -> {
                    feed.hot.tryEmitNext(MetricUpdates.of("1-0", "t1", "title-1", "ERROR_RATE", 0.1));
                    feed.hot.tryEmitNext(MetricUpdates.of("2-0", "t1", "title-1", "CONCURRENT_VIEWERS", 7));
                })
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("2-0"))
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    @DisplayName("resume replays what was missed, then goes live, with no duplicate across the seam")
    void resumeReplaysThenGoesLiveWithoutDuplicates() {
        var feed = new FakeFeed(Flux.just(update("2-0", "t1", 0.2), update("3-0", "t1", 0.3)));

        StepVerifier.create(controller(feed).stream("t1", null, "1-0"))
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("2-0"))
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("3-0"))
                .then(() -> {
                    feed.hot.tryEmitNext(update("3-0", "t1", 0.3));
                    feed.hot.tryEmitNext(update("4-0", "t1", 0.4));
                })
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("4-0"))
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    @DisplayName("an entry that lands while the replay is being read is not lost")
    void entryDuringReplayIsNotLost() {
        var feed = new FakeFeed(Flux.empty());
        feed.replay = Flux.defer(() -> {
            // the live feed publishes 4-0 AFTER the client subscribed but BEFORE the range read returns
            feed.hot.tryEmitNext(update("4-0", "t1", 0.4));
            return Flux.just(update("2-0", "t1", 0.2), update("3-0", "t1", 0.3));
        });
        var subject = controller(feed);

        StepVerifier.create(subject.stream("t1", null, "1-0"))
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("2-0"))
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("3-0"))
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("4-0"))
                .thenCancel()
                .verify(WAIT);
    }

    @Test
    @DisplayName("replay is filtered to the client's tenant")
    void replayIsTenantFiltered() {
        var feed = new FakeFeed(Flux.just(update("2-0", "t2", 0.2), update("3-0", "t1", 0.3)));

        StepVerifier.create(controller(feed).stream("t1", null, "1-0"))
                .assertNext(event -> assertThat(idOf(event)).isEqualTo("3-0"))
                .thenCancel()
                .verify(WAIT);
    }
}
