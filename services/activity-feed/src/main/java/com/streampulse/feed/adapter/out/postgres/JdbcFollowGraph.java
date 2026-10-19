/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.out.postgres;

import com.streampulse.feed.domain.FollowGraph;
import com.streampulse.feed.domain.Followee;
import com.streampulse.feed.domain.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The follow graph in the {@code activity_feed} schema (V1__follow_graph.sql). The counter row is
 * changed in the same transaction as the follow row and only when that row really changed, so a
 * repeated or racing request cannot move it.
 *
 * <p>Both writes touch the follow row first and the counter second, always in that order, so two
 * transactions cannot deadlock on the pair.
 */
public final class JdbcFollowGraph implements FollowGraph {

    static final String INSERT_FOLLOW =
            """
            INSERT INTO activity_feed.follows (follower_id, followee_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    static final String INCREMENT =
            """
            INSERT INTO activity_feed.follower_counts (user_id, followers) VALUES (?, 1)
            ON CONFLICT (user_id) DO UPDATE SET followers = follower_counts.followers + 1
            """;

    static final String DELETE_FOLLOW = "DELETE FROM activity_feed.follows WHERE follower_id = ? AND followee_id = ?";

    static final String DECREMENT =
            "UPDATE activity_feed.follower_counts SET followers = followers - 1 WHERE user_id = ?";

    static final String COUNT = "SELECT followers FROM activity_feed.follower_counts WHERE user_id = ?";

    static final String FOLLOWERS_AFTER =
            """
            SELECT follower_id FROM activity_feed.follows
            WHERE followee_id = ? AND follower_id > ?
            ORDER BY follower_id
            LIMIT ?
            """;

    static final String FOLLOWEES =
            """
            SELECT f.followee_id, COALESCE(c.followers, 0) AS followers
            FROM activity_feed.follows f
            LEFT JOIN activity_feed.follower_counts c ON c.user_id = f.followee_id
            WHERE f.follower_id = ?
            ORDER BY f.followee_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcFollowGraph(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public boolean follow(UserId follower, UserId followee) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            int inserted = jdbc.update(
                    INSERT_FOLLOW,
                    follower.value(),
                    followee.value(),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            if (inserted == 1) {
                jdbc.update(INCREMENT, followee.value());
            }
            return inserted == 1;
        }));
    }

    @Override
    public boolean unfollow(UserId follower, UserId followee) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            int deleted = jdbc.update(DELETE_FOLLOW, follower.value(), followee.value());
            if (deleted == 1) {
                jdbc.update(DECREMENT, followee.value());
            }
            return deleted == 1;
        }));
    }

    @Override
    public long followerCount(UserId user) {
        return jdbc.query(COUNT, rs -> rs.next() ? rs.getLong(1) : 0L, user.value());
    }

    @Override
    public List<UserId> followersAfter(UserId author, Optional<UserId> after, int limit) {
        // Ids are never empty, so every id sorts after "" and the first page needs no separate query.
        return jdbc.query(
                FOLLOWERS_AFTER,
                (rs, row) -> new UserId(rs.getString(1)),
                author.value(),
                after.map(UserId::value).orElse(""),
                limit);
    }

    @Override
    public List<Followee> followeesOf(UserId reader, int max) {
        return jdbc.query(
                FOLLOWEES, (rs, row) -> new Followee(new UserId(rs.getString(1)), rs.getLong(2)), reader.value(), max);
    }
}
