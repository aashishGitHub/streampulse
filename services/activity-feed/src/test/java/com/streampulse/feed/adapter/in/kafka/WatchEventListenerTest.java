/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in.kafka;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.WatchEvent;
import com.streampulse.feed.application.DeadLetterPort;
import com.streampulse.feed.application.DeadLetterUnavailableException;
import com.streampulse.feed.application.FanOutService;
import com.streampulse.feed.domain.FanOutPolicy;
import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.TimelineStore;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.support.InMemoryFollowGraph;
import com.streampulse.feed.support.InMemoryTimelineStore;
import com.streampulse.feed.support.RecordingMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class WatchEventListenerTest {

    private static final AvroBinaryCodec<WatchEvent> CODEC = AvroBinaryCodec.of(WatchEvent.class);

    private final List<String> order = new ArrayList<>();
    private final InMemoryFollowGraph graph = new InMemoryFollowGraph();
    private final RecordingMetrics metrics = new RecordingMetrics();
    private final List<String> deadLetters = new ArrayList<>();
    private DeadLetterPort deadLetterPort = (key, value, reason) -> deadLetters.add(key + "|" + reason);
    private final Acknowledgment ack = () -> order.add("ack");

    /** Records the moment each write becomes durable, so the order against the ack is observable. */
    private final class OrderRecordingStore implements TimelineStore {
        private final InMemoryTimelineStore delegate = new InMemoryTimelineStore();
        private boolean failing;

        @Override
        public void pushToTimelines(Collection<UserId> followers, FeedEntry entry) {
            check();
            delegate.pushToTimelines(followers, entry);
            order.add("push");
        }

        @Override
        public void appendToOutbox(FeedEntry entry) {
            check();
            delegate.appendToOutbox(entry);
            order.add("outbox");
        }

        @Override
        public List<FeedEntry> timeline(UserId user, Instant asOf, int limit) {
            return delegate.timeline(user, asOf, limit);
        }

        @Override
        public List<FeedEntry> outboxes(Collection<UserId> authors, Instant asOf, int perAuthorLimit) {
            return delegate.outboxes(authors, asOf, perAuthorLimit);
        }

        private void check() {
            if (failing) {
                throw new IllegalStateException("redis is down");
            }
        }
    }

    private final OrderRecordingStore store = new OrderRecordingStore();

    private WatchEventListener listener() {
        var fanOut = new FanOutService(graph, store, new FanOutPolicy(100), metrics, 500);
        return new WatchEventListener(fanOut, new WatchEventDecoder(), deadLetterPort, metrics);
    }

    private static ConsumerRecord<String, byte[]> record(long offset, String userId, UUID eventId) {
        byte[] value = CODEC.encode(WatchEvent.newBuilder()
                .setEventId(eventId.toString())
                .setUserId(userId)
                .setTitleId("dune")
                .setOccurredAt(NOW)
                .build());
        return new ConsumerRecord<>("activity.watch.v1", 0, offset, userId, value);
    }

    @Test
    @DisplayName("the offset is acknowledged only after every fan-out in the poll is durable")
    void acksOnlyAfterEveryFanOutIsDurable() {
        graph.follow(user("f1"), user("alice"));

        listener().onBatch(List.of(record(0, "alice", UUID.randomUUID()), record(1, "alice", UUID.randomUUID())), ack);

        assertThat(order).containsExactly("outbox", "push", "outbox", "push", "ack");
    }

    @Test
    @DisplayName("when the fan-out fails the exception propagates and nothing is acknowledged: the poll is redelivered")
    void doesNotAckWhenTheFanOutFails() {
        store.failing = true;

        assertThatThrownBy(() -> listener().onBatch(List.of(record(0, "alice", UUID.randomUUID())), ack))
                .isInstanceOf(IllegalStateException.class);

        assertThat(order).doesNotContain("ack");
        assertThat(deadLetters).as("an outage is not a poison record").isEmpty();
    }

    @Test
    @DisplayName("a rejected record is dead-lettered with its key and reason, and the rest of the poll is processed")
    void deadLettersARejectedRecordAndContinues() {
        var poison = new ConsumerRecord<>("activity.watch.v1", 0, 0L, "alice", "nope".getBytes(StandardCharsets.UTF_8));

        listener().onBatch(List.of(poison, record(1, "alice", UUID.randomUUID())), ack);

        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst()).startsWith("alice|cannot decode");
        assertThat(order).containsExactly("outbox", "ack");
        assertThat(metrics.deadLetters).isEqualTo(1);
    }

    @Test
    @DisplayName("when the dead-letter topic is unavailable nothing is acknowledged: acking would lose the record")
    void doesNotAckWhenTheDeadLetterFails() {
        deadLetterPort = (key, value, reason) -> {
            throw new DeadLetterUnavailableException("dlt down", new RuntimeException());
        };
        var poison = new ConsumerRecord<>("activity.watch.v1", 0, 0L, "alice", "nope".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener().onBatch(List.of(poison), ack))
                .isInstanceOf(DeadLetterUnavailableException.class);

        assertThat(order).doesNotContain("ack");
    }

    @Test
    @DisplayName("an empty poll is acknowledged and does nothing")
    void anEmptyPollIsAcknowledged() {
        listener().onBatch(List.of(), ack);

        assertThat(order).containsExactly("ack");
    }
}
