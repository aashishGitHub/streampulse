/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streampulse.contracts.codec.AvroBinaryCodec;
import com.streampulse.contracts.v1.WatchEvent;
import com.streampulse.feed.adapter.in.web.FeedController;
import com.streampulse.feed.adapter.in.web.FollowController;
import com.streampulse.feed.adapter.in.web.WatchController;
import com.streampulse.feed.domain.FanOutStrategy;
import com.streampulse.feed.support.ItContainers;
import com.streampulse.feed.support.KafkaReads;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole service against real Kafka, Postgres and Redis, driven only through its HTTP API.
 * The celebrity threshold is 5 here, so "a celebrity" is five followers, and the assertions about
 * which Redis keys exist are the proof of which strategy ran.
 *
 * <p>What it does not prove: a broker, Postgres or Redis failing mid-fan-out, or the JVM being
 * killed. The injected-failure paths are unit-tested; the idempotency that makes a real crash
 * safe is what the duplicate test exercises.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "streampulse.feed.celebrity-threshold=5",
            "streampulse.feed.retry.initial-interval=100ms",
            "streampulse.feed.retry.max-interval=500ms"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WatchFeedIT {

    private static final int THRESHOLD = 5;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> {
            try {
                return ItContainers.kafka().getBootstrapServers();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        registry.add("spring.datasource.url", () -> ItContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> ItContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> ItContainers.postgres().getPassword());
        registry.add("spring.data.redis.host", () -> ItContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> ItContainers.redis().getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private KafkaTemplate<String, byte[]> kafka;

    private static String uid(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void follow(String follower, String followee) {
        var response = http.postForEntity(
                "/v1/users/{id}/follow?followerId={f}",
                null,
                FollowController.FollowResponse.class,
                followee,
                follower);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void unfollow(String follower, String followee) {
        var response = http.exchange(
                "/v1/users/{id}/follow?followerId={f}",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                FollowController.FollowResponse.class,
                followee,
                follower);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID watch(String user, String title, UUID eventId) {
        String body = "{\"userId\":\"" + user + "\",\"titleId\":\"" + title + "\""
                + (eventId == null ? "" : ",\"eventId\":\"" + eventId + "\"") + "}";
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = http.postForEntity(
                "/v1/watching", new HttpEntity<>(body, headers), WatchController.WatchAccepted.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().eventId();
    }

    private FeedController.FeedResponse feed(String user, String cursor, int limit) {
        String url = "/v1/feed?userId={u}&limit={l}" + (cursor == null ? "" : "&cursor={c}");
        var response = cursor == null
                ? http.getForEntity(url, FeedController.FeedResponse.class, user, limit)
                : http.getForEntity(url, FeedController.FeedResponse.class, user, limit, cursor);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> authors(FeedController.FeedResponse page) {
        return page.items().stream().map(FeedController.Item::authorId).toList();
    }

    private static String timelineKey(String user) {
        return "sp:feed:tl:" + user;
    }

    private static String outboxKey(String user) {
        return "sp:feed:out:" + user;
    }

    private void awaitFeedContains(String reader, String author) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(authors(feed(reader, null, 100)))
                .contains(author));
    }

    @Test
    @DisplayName("a normal author's watch lands in every follower's timeline and feed")
    void aNormalAuthorsWatchReachesEveryFollowersFeed() {
        String author = uid("alice");
        List<String> followers = List.of(uid("f"), uid("f"), uid("f"));
        followers.forEach(f -> follow(f, author));
        assertThat(http.getForObject("/v1/users/{id}/followers", FollowController.FollowersResponse.class, author)
                        .strategy())
                .isEqualTo(FanOutStrategy.ON_WRITE);

        watch(author, "dune", null);

        for (String follower : followers) {
            awaitFeedContains(follower, author);
            FeedController.Item item = feed(follower, null, 10).items().getFirst();
            assertThat(item.titleId()).isEqualTo("dune");
            assertThat(redis.hasKey(timelineKey(follower)))
                    .as("fan-out on write wrote %s", follower)
                    .isTrue();
        }
        assertThat(redis.hasKey(outboxKey(author)))
                .as("the outbox is always written")
                .isTrue();
        assertThat(feed(author, null, 10).items())
                .as("an author does not see their own watch")
                .isEmpty();
    }

    @Test
    @DisplayName("the same event delivered twice leaves one entry per follower")
    void theSameEventTwiceLeavesOneEntryPerFollower() throws Exception {
        String author = uid("alice");
        List<String> followers = List.of(uid("f"), uid("f"));
        followers.forEach(f -> follow(f, author));
        UUID eventId = UUID.randomUUID();

        watch(author, "dune", eventId);
        watch(author, "dune", eventId); // the client's retry: same idempotency key, a second Kafka record
        byte[] again = AvroBinaryCodec.of(WatchEvent.class)
                .encode(WatchEvent.newBuilder()
                        .setEventId(eventId.toString())
                        .setUserId(author)
                        .setTitleId("dune")
                        .setOccurredAt(Instant.now())
                        .build());
        kafka.send("activity.watch.v1", author, again).get(); // and a broker-level redelivery, with another timestamp
        // Same key, so same partition, so in order: once this sentinel is visible the three above have been processed.
        watch(author, "arrival", null);

        for (String follower : followers) {
            awaitFeedContains(follower, author);
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(() -> assertThat(feed(follower, null, 10).items())
                            .extracting(FeedController.Item::titleId)
                            .contains("arrival", "dune"));
            assertThat(redis.opsForZSet().zCard(timelineKey(follower)))
                    .as("one entry for dune, one for arrival, however often dune was delivered")
                    .isEqualTo(2);
        }
        assertThat(redis.opsForZSet().zCard(outboxKey(author))).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "a celebrity's watch is not written to any follower's timeline, yet appears in their feed via the merge")
    void aCelebrityWatchIsNotWrittenToFollowerTimelinesButAppearsInTheFeed() {
        String star = uid("star");
        List<String> followers = new ArrayList<>();
        for (int i = 0; i < THRESHOLD; i++) {
            followers.add(uid("fan"));
        }
        followers.forEach(f -> follow(f, star));
        assertThat(http.getForObject("/v1/users/{id}/followers", FollowController.FollowersResponse.class, star)
                        .strategy())
                .as("exactly at the threshold is a celebrity")
                .isEqualTo(FanOutStrategy.ON_READ);

        watch(star, "dune", null);

        for (String fan : followers) {
            awaitFeedContains(fan, star);
        }
        assertThat(redis.hasKey(outboxKey(star))).isTrue();
        for (String fan : followers) {
            assertThat(redis.hasKey(timelineKey(fan)))
                    .as("fan-out on write must not have touched %s", fan)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("pages fetched while new entries arrive repeat nothing and skip nothing")
    void pagesAcrossConcurrentInsertsWithoutRepeatOrSkip() {
        String reader = uid("reader");
        List<String> original = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String author = uid("a" + i);
            follow(reader, author);
            watch(author, "title-" + (i % 4), null);
            original.add(author);
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(authors(feed(reader, null, 100)))
                .containsAll(original));

        FeedController.FeedResponse first = feed(reader, null, 5);
        assertThat(first.nextCursor()).isNotNull();
        long afterFirstPage = System.currentTimeMillis();

        // The new entries must be stamped strictly after the first page's snapshot instant.
        await().until(() -> System.currentTimeMillis() > afterFirstPage);
        List<String> late = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String author = uid("late" + i);
            follow(reader, author);
            watch(author, "title-" + (i % 4), null);
            late.add(author);
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(authors(feed(reader, null, 100)))
                .containsAll(late));

        var seen = new ArrayList<>(authors(first));
        String cursor = first.nextCursor();
        while (cursor != null) {
            FeedController.FeedResponse page = feed(reader, cursor, 5);
            seen.addAll(authors(page));
            cursor = page.nextCursor();
        }

        assertThat(seen).as("no entry twice").doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen))
                .as("exactly what existed when the first page was served: nothing skipped, nothing added")
                .isEqualTo(new HashSet<>(original));
        assertThat(authors(feed(reader, null, 100)))
                .as("a fresh fetch sees the arrivals")
                .containsAll(late);
    }

    @Test
    @DisplayName("after an unfollow the author's entry is filtered out of the feed though it is still in the timeline")
    void anUnfollowHidesTheStaleEntry() {
        String reader = uid("reader");
        String kept = uid("kept");
        String dropped = uid("dropped");
        follow(reader, kept);
        follow(reader, dropped);
        watch(kept, "dune", null);
        watch(dropped, "arrival", null);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(authors(feed(reader, null, 10)))
                .containsExactlyInAnyOrder(kept, dropped));

        unfollow(reader, dropped);

        assertThat(redis.opsForZSet().zCard(timelineKey(reader)))
                .as("the stale entry is still stored")
                .isEqualTo(2);
        assertThat(authors(feed(reader, null, 10))).containsExactly(kept);
    }

    @Test
    @DisplayName(
            "an undecodable record is dead-lettered with its key and reason, and the next record is still processed")
    void anUndecodableRecordIsDeadLetteredAndTheNextOneIsStillProcessed() throws Exception {
        String author = uid("alice");
        String follower = uid("f");
        follow(follower, author);

        kafka.send("activity.watch.v1", author, "definitely not avro".getBytes(StandardCharsets.UTF_8))
                .get();
        watch(author, "dune", null);

        awaitFeedContains(follower, author);
        var parked = KafkaReads.readAll(ItContainers.kafka().getBootstrapServers(), ItContainers.WATCH_DLT).stream()
                .filter(r -> author.equals(r.key()))
                .toList();
        assertThat(parked).hasSize(1);
        assertThat(new String(parked.getFirst().value(), StandardCharsets.UTF_8))
                .isEqualTo("definitely not avro");
        assertThat(new String(
                        parked.getFirst().headers().lastHeader("dlt-reason").value(), StandardCharsets.UTF_8))
                .startsWith("cannot decode WatchEvent");
    }

    @Test
    @DisplayName("an invalid report is rejected at the edge with a 400 problem+json")
    void anInvalidReportIsRejectedAtTheEdge() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var response = http.postForEntity(
                "/v1/watching", new HttpEntity<>("{\"userId\":\"a:b\",\"titleId\":\"x\"}", headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
    }
}
