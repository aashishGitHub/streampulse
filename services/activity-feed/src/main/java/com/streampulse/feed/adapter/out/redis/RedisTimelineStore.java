/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.out.redis;

import com.streampulse.feed.domain.FeedEntry;
import com.streampulse.feed.domain.InvalidFeedRequestException;
import com.streampulse.feed.domain.TimelineStore;
import com.streampulse.feed.domain.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

/**
 * Two sorted sets per user, scored by event time in milliseconds:
 *
 * <pre>
 *   sp:feed:tl:{userId}    the precomputed timeline (fan-out on write lands here)
 *   sp:feed:out:{authorId} the author's own recent entries (fan-out on read pulls from here)
 * </pre>
 *
 * Writing a member that is already there changes nothing, which is the idempotency by event id
 * (the member contains it). A page of followers is one pipelined round trip.
 */
public final class RedisTimelineStore implements TimelineStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTimelineStore.class);

    static final String TIMELINE_PREFIX = "sp:feed:tl:";
    static final String OUTBOX_PREFIX = "sp:feed:out:";

    public record Settings(int timelineMaxLength, Duration timelineTtl, int outboxMaxLength, Duration outboxTtl) {}

    private final StringRedisTemplate redis;
    private final Settings settings;

    public RedisTimelineStore(StringRedisTemplate redis, Settings settings) {
        this.redis = redis;
        this.settings = settings;
    }

    static String timelineKey(UserId user) {
        return TIMELINE_PREFIX + user.value();
    }

    static String outboxKey(UserId author) {
        return OUTBOX_PREFIX + author.value();
    }

    @Override
    public void pushToTimelines(Collection<UserId> followers, FeedEntry entry) {
        if (followers.isEmpty()) {
            return;
        }
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                var ops = (RedisOperations<String, String>) operations;
                for (UserId follower : followers) {
                    write(ops, timelineKey(follower), entry, settings.timelineMaxLength(), settings.timelineTtl());
                }
                return null;
            }
        });
    }

    @Override
    public void appendToOutbox(FeedEntry entry) {
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                var ops = (RedisOperations<String, String>) operations;
                write(ops, outboxKey(entry.author()), entry, settings.outboxMaxLength(), settings.outboxTtl());
                return null;
            }
        });
    }

    /**
     * ZADD, then keep only the newest {@code max} (ranks run oldest-first, so 0..-(max+1) is everything
     * but the top {@code max}), then refresh the expiry. Not atomic across the three, and each is idempotent.
     */
    private static void write(RedisOperations<String, String> ops, String key, FeedEntry entry, int max, Duration ttl) {
        ops.opsForZSet().add(key, entry.member(), entry.score());
        ops.opsForZSet().removeRange(key, 0, -(max + 1L));
        ops.expire(key, ttl.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public List<FeedEntry> timeline(UserId user, Instant asOf, int limit) {
        return parse(newest(timelineKey(user), asOf, limit));
    }

    /** One pipelined round trip for every author, however many a reader follows. */
    @Override
    public List<FeedEntry> outboxes(Collection<UserId> authors, Instant asOf, int perAuthorLimit) {
        if (authors.isEmpty()) {
            return List.of();
        }
        double max = asOf.toEpochMilli();
        List<Object> replies = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                var ops = (RedisOperations<String, String>) operations;
                for (UserId author : authors) {
                    ops.opsForZSet()
                            .reverseRangeByScoreWithScores(
                                    outboxKey(author), Double.NEGATIVE_INFINITY, max, 0, perAuthorLimit);
                }
                return null;
            }
        });
        var out = new ArrayList<FeedEntry>();
        for (Object reply : replies) {
            if (reply == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            var tuples = (Set<TypedTuple<String>>) reply;
            out.addAll(parse(tuples));
        }
        return out;
    }

    private Set<TypedTuple<String>> newest(String key, Instant asOf, int limit) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet()
                .reverseRangeByScoreWithScores(key, Double.NEGATIVE_INFINITY, asOf.toEpochMilli(), 0, limit);
        return tuples == null ? Set.of() : tuples;
    }

    private static List<FeedEntry> parse(Set<TypedTuple<String>> tuples) {
        var entries = new ArrayList<FeedEntry>(tuples.size());
        for (TypedTuple<String> tuple : tuples) {
            try {
                entries.add(FeedEntry.fromMember(
                        Objects.requireNonNull(tuple.getValue()),
                        Objects.requireNonNull(tuple.getScore()).longValue()));
            } catch (InvalidFeedRequestException e) {
                // One bad member must not blind a reader to every other entry in the set.
                log.warn("skipping a malformed timeline member: {}", e.getMessage());
            }
        }
        return entries;
    }
}
