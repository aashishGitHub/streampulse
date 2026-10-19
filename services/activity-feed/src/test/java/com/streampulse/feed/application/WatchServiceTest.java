/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.application;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.title;
import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.feed.domain.WatchStarted;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WatchServiceTest {

    private static final UUID GENERATED = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private final List<WatchStarted> published = new ArrayList<>();
    private WatchPublisher publisher = published::add;

    private WatchService service() {
        return new WatchService(publisher, Clock.fixed(NOW, ZoneOffset.UTC), () -> GENERATED);
    }

    @Test
    @DisplayName("a report with no event id gets one, and is stamped with the clock's time")
    void assignsTheEventIdAndTime() {
        UUID eventId = service().watch(user("alice"), title("dune"), Optional.empty());

        assertThat(eventId).isEqualTo(GENERATED);
        assertThat(published).containsExactly(new WatchStarted(GENERATED, user("alice"), title("dune"), NOW));
    }

    @Test
    @DisplayName("a client-supplied event id is kept, so the client's retry carries the same idempotency key")
    void keepsAClientSuppliedEventId() {
        UUID mine = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        UUID eventId = service().watch(user("alice"), title("dune"), Optional.of(mine));

        assertThat(eventId).isEqualTo(mine);
        assertThat(published.getFirst().eventId()).isEqualTo(mine);
    }

    @Test
    @DisplayName("a publish failure reaches the caller, so the report is never acknowledged")
    void propagatesAPublishFailure() {
        publisher = event -> {
            throw new WatchUnavailableException("broker down", new RuntimeException());
        };

        assertThatThrownBy(() -> service().watch(user("alice"), title("dune"), Optional.empty()))
                .isInstanceOf(WatchUnavailableException.class);
    }
}
