/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class HeartbeatTest {

    private static final Duration PERIOD = Duration.ofSeconds(15);

    private static ServerSentEvent<String> data(String id) {
        return ServerSentEvent.<String>builder(id).id(id).build();
    }

    @Test
    @DisplayName("an idle stream gets a comment every 15 seconds, and nothing before")
    void idleStreamGetsAComment() {
        StepVerifier.withVirtualTime(() -> new Heartbeat(PERIOD).merge(Flux.<ServerSentEvent<String>>never()))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(14))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(HeartbeatTest::assertIsComment)
                .expectNoEvent(Duration.ofSeconds(14))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(HeartbeatTest::assertIsComment)
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("data keeps flowing between heartbeats")
    void dataAndHeartbeatsInterleave() {
        Sinks.Many<ServerSentEvent<String>> source = Sinks.many().multicast().directBestEffort();

        StepVerifier.withVirtualTime(() -> new Heartbeat(PERIOD).merge(source.asFlux()))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(5))
                .then(() -> source.tryEmitNext(data("1-0")))
                .assertNext(event -> assertThat(event.id()).isEqualTo("1-0"))
                .thenAwait(Duration.ofSeconds(10))
                .assertNext(HeartbeatTest::assertIsComment)
                .then(() -> source.tryEmitNext(data("2-0")))
                .assertNext(event -> assertThat(event.id()).isEqualTo("2-0"))
                .thenCancel()
                .verify();
    }

    private static void assertIsComment(ServerSentEvent<String> event) {
        assertThat(event.comment()).isNotBlank();
        assertThat(event.data()).isNull();
        assertThat(event.id()).isNull();
    }
}
