/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.application;

import static com.streampulse.alert.support.Alerts.firing;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.application.AlertFeed.Entry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertFeedTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    private static List<Long> drain(AlertFeed.Subscription subscription) throws InterruptedException {
        List<Long> seen = new ArrayList<>();
        for (Entry e = subscription.next(SHORT); e != null; e = subscription.next(SHORT)) {
            seen.add(e.sequence());
        }
        return seen;
    }

    @Test
    @DisplayName("sequence numbers are 1, 2, 3 ... and strictly increasing")
    void sequenceIsMonotonic() throws InterruptedException {
        var feed = new AlertFeed(10, 10);
        var sub = feed.subscribe("t", OptionalLong.empty());

        long a = feed.publish(firing("t", "a"));
        long b = feed.publish(firing("t", "b"));
        long c = feed.publish(firing("other", "c"));

        assertThat(List.of(a, b, c)).containsExactly(1L, 2L, 3L);
        assertThat(drain(sub))
                .as("tenant t sees only its own, ids keep the gaps")
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("a new subscriber without a last id gets live events only")
    void freshSubscriberGetsNoHistory() throws InterruptedException {
        var feed = new AlertFeed(10, 10);
        feed.publish(firing("t", "a"));

        assertThat(drain(feed.subscribe("t", OptionalLong.empty()))).isEmpty();
    }

    @Test
    @DisplayName("reconnecting with a last id replays what came after it, in order, then continues live")
    void replayThenLive() throws InterruptedException {
        var feed = new AlertFeed(10, 10);
        for (String id : List.of("a", "b", "c", "d")) {
            feed.publish(firing("t", id));
        }

        var sub = feed.subscribe("t", OptionalLong.of(2));
        feed.publish(firing("t", "e"));

        assertThat(drain(sub)).containsExactly(3L, 4L, 5L);
    }

    @Test
    @DisplayName("replay is bounded: events older than the buffer are gone, the rest are replayed")
    void replayIsBounded() throws InterruptedException {
        var feed = new AlertFeed(3, 10);
        for (int i = 0; i < 6; i++) {
            feed.publish(firing("t", "a" + i));
        }

        assertThat(drain(feed.subscribe("t", OptionalLong.of(0)))).containsExactly(4L, 5L, 6L);
    }

    @Test
    @DisplayName("replay and live never leave a gap or a duplicate, even with publishing racing the subscribe")
    void replayThenLiveHasNoGapOrDuplicate() throws Exception {
        int total = 2000;
        var feed = new AlertFeed(total, total);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < total; i++) {
                    feed.publish(firing("t", "a" + i));
                }
                return null;
            });
            var subscribed = pool.submit(() -> {
                start.await();
                TimeUnit.MILLISECONDS.sleep(1);
                return feed.subscribe("t", OptionalLong.of(0));
            });
            start.countDown();
            var sub = subscribed.get();
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            List<Long> seen = drain(sub);

            assertThat(seen).isNotEmpty();
            assertThat(seen).isSorted().doesNotHaveDuplicates();
            assertThat(seen.get(0)).isEqualTo(1L);
            assertThat(seen.get(seen.size() - 1)).isEqualTo((long) total);
            assertThat(seen).hasSize(total);
        }
    }

    @Test
    @DisplayName("a subscriber that falls too far behind is ended; others and the publisher are unaffected")
    void fullQueueClosesOnlyThatSubscriber() throws InterruptedException {
        var feed = new AlertFeed(2, 2);
        var slow = feed.subscribe("t", OptionalLong.empty());
        var quick = feed.subscribe("t", OptionalLong.empty());

        List<Long> quickSeen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            feed.publish(firing("t", "a" + i));
            quickSeen.add(quick.next(SHORT).sequence());
        }

        assertThat(slow.isEnded()).isTrue();
        assertThat(slow.overflowed()).isTrue();
        assertThat(quick.isEnded()).isFalse();
        assertThat(quickSeen).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(feed.subscriberCount()).isEqualTo(1);
        assertThat(slow.next(SHORT)).isNull();
    }

    @Test
    @DisplayName("an overflowed subscriber can resume by id and loses nothing the buffer still holds")
    void overflowedSubscriberResumes() throws InterruptedException {
        var feed = new AlertFeed(8, 2);
        var slow = feed.subscribe("t", OptionalLong.empty());
        for (int i = 0; i < 20; i++) {
            feed.publish(firing("t", "a" + i));
        }
        Entry lastSent = slow.next(SHORT);
        assertThat(lastSent).isNull();
        assertThat(slow.overflowed()).isTrue();

        assertThat(drain(feed.subscribe("t", OptionalLong.of(12))))
                .containsExactly(13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
    }

    @Test
    @DisplayName("closing wakes a waiting reader and ends the subscription")
    void closeWakesReader() throws Exception {
        var feed = new AlertFeed(10, 10);
        var sub = feed.subscribe("t", OptionalLong.empty());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var reader = pool.submit(() -> sub.next(Duration.ofSeconds(30)));
            TimeUnit.MILLISECONDS.sleep(50);

            sub.close();

            assertThat(reader.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(sub.isEnded()).isTrue();
            assertThat(feed.subscriberCount()).isZero();
        }
    }

    @Test
    @DisplayName("closeAll ends every subscription")
    void closeAll() {
        var feed = new AlertFeed(10, 10);
        var a = feed.subscribe("t", OptionalLong.empty());
        var b = feed.subscribe("u", OptionalLong.empty());

        feed.closeAll();

        assertThat(a.isEnded()).isTrue();
        assertThat(b.isEnded()).isTrue();
    }

    @Test
    @DisplayName("an idle poll returns null without ending the subscription: that is the heartbeat tick")
    void idlePollIsNotAnEnd() throws InterruptedException {
        var feed = new AlertFeed(10, 10);
        var sub = feed.subscribe("t", OptionalLong.empty());

        assertThat(sub.next(SHORT)).isNull();
        assertThat(sub.isEnded()).isFalse();
    }
}
