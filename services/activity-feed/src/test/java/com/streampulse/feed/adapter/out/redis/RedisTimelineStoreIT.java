/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.out.redis;

import static com.streampulse.feed.support.Fixtures.NOW;
import static com.streampulse.feed.support.Fixtures.entry;
import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.support.ItContainers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisTimelineStoreIT {

    private static final RedisTimelineStore.Settings SETTINGS =
            new RedisTimelineStore.Settings(5, Duration.ofHours(1), 4, Duration.ofHours(2));

    private static StringRedisTemplate redis;
    private static RedisTimelineStore store;

    @BeforeAll
    static void connect() {
        var container = ItContainers.redis();
        var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisTimelineStore(redis, SETTINGS);
    }

    private static UserId unique(String prefix) {
        return user(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    @DisplayName("a push writes each follower's timeline and gives it an expiry")
    void pushWritesEachTimelineAndSetsATtl() {
        List<UserId> followers = List.of(unique("f"), unique("f"), unique("f"));
        FeedEntry entry = entry("alice", "dune", NOW);

        store.pushToTimelines(followers, entry);

        for (UserId f : followers) {
            assertThat(store.timeline(f, NOW, 10)).containsExactly(entry);
            assertThat(redis.getExpire(RedisTimelineStore.timelineKey(f)))
                    .isBetween(1L, SETTINGS.timelineTtl().toSeconds());
        }
    }

    @Test
    @DisplayName("writing the same entry again leaves one entry: the member carries the event id")
    void redeliveryIsIdempotent() {
        UserId follower = unique("f");
        FeedEntry entry = entry("alice", "dune", NOW);

        store.pushToTimelines(List.of(follower), entry);
        store.pushToTimelines(List.of(follower), entry);
        store.appendToOutbox(entry);
        store.appendToOutbox(entry);

        assertThat(redis.opsForZSet().zCard(RedisTimelineStore.timelineKey(follower)))
                .isEqualTo(1);
        assertThat(redis.opsForZSet().zCard(RedisTimelineStore.outboxKey(entry.author())))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a timeline is trimmed to its bound, keeping the newest entries")
    void trimsATimelineToItsBound() {
        UserId follower = unique("f");
        var written = new ArrayList<FeedEntry>();
        for (int i = 0; i < 9; i++) {
            FeedEntry e = entry("author" + i, "t" + i, NOW.minusSeconds(100 - i));
            written.add(e);
            store.pushToTimelines(List.of(follower), e);
        }

        assertThat(redis.opsForZSet().zCard(RedisTimelineStore.timelineKey(follower)))
                .isEqualTo(5);
        assertThat(store.timeline(follower, NOW, 100))
                .containsExactlyElementsOf(written.reversed().subList(0, 5));
    }

    @Test
    @DisplayName("an outbox has its own, separate bound")
    void trimsAnOutboxToItsBound() {
        for (int i = 0; i < 7; i++) {
            store.appendToOutbox(entry("star-bound", "t" + i, NOW.minusSeconds(100 - i)));
        }
        // Distinct authors per entry would not share a key; the same author does.
        assertThat(redis.opsForZSet().zCard(RedisTimelineStore.outboxKey(user("star-bound"))))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a timeline read is newest first and excludes everything newer than the snapshot")
    void timelineIsNewestFirstAndBoundedByTheSnapshot() {
        UserId follower = unique("f");
        FeedEntry old = entry("a", "t1", NOW.minusSeconds(30));
        FeedEntry recent = entry("b", "t2", NOW.minusSeconds(10));
        FeedEntry atSnapshot = entry("c", "t3", NOW);
        FeedEntry afterSnapshot = entry("d", "t4", NOW.plusMillis(1));
        for (FeedEntry e : List.of(old, recent, atSnapshot, afterSnapshot)) {
            store.pushToTimelines(List.of(follower), e);
        }

        assertThat(store.timeline(follower, NOW, 10)).containsExactly(atSnapshot, recent, old);
        assertThat(store.timeline(follower, NOW, 2)).containsExactly(atSnapshot, recent);
    }

    @Test
    @DisplayName("a read of a user with no timeline is empty")
    void unknownTimelineIsEmpty() {
        assertThat(store.timeline(unique("nobody"), NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("outboxes of several authors are read together, each limited, each bounded by the snapshot")
    void outboxesOfSeveralAuthors() {
        UserId s1 = unique("s");
        UserId s2 = unique("s");
        FeedEntry a1 = new FeedEntry(
                UUID.randomUUID(), s1, new com.streampulse.feed.domain.TitleId("t1"), NOW.minusSeconds(5));
        FeedEntry a2 = new FeedEntry(
                UUID.randomUUID(), s1, new com.streampulse.feed.domain.TitleId("t2"), NOW.minusSeconds(50));
        FeedEntry b1 = new FeedEntry(
                UUID.randomUUID(), s2, new com.streampulse.feed.domain.TitleId("t3"), NOW.minusSeconds(7));
        FeedEntry late =
                new FeedEntry(UUID.randomUUID(), s2, new com.streampulse.feed.domain.TitleId("t4"), NOW.plusSeconds(1));
        for (FeedEntry e : List.of(a1, a2, b1, late)) {
            store.appendToOutbox(e);
        }

        assertThat(store.outboxes(List.of(s1, s2), NOW, 10)).containsExactlyInAnyOrder(a1, a2, b1);
        assertThat(store.outboxes(List.of(s1, s2), NOW, 1)).containsExactlyInAnyOrder(a1, b1);
        assertThat(store.outboxes(List.of(), NOW, 10)).isEmpty();
    }

    @Test
    @DisplayName("a member that does not parse is skipped, and the valid entries around it are still returned")
    void skipsAMalformedMember() {
        UserId follower = unique("f");
        FeedEntry good = entry("alice", "dune", NOW.minusSeconds(1));
        store.pushToTimelines(List.of(follower), good);
        redis.opsForZSet().add(RedisTimelineStore.timelineKey(follower), "this-is-not-a-member", NOW.toEpochMilli());

        assertThat(store.timeline(follower, NOW, 10)).containsExactly(good);
    }
}
