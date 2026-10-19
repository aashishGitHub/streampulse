/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.adapter.out.postgres;

import static com.streampulse.feed.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.feed.domain.Followee;
import com.streampulse.feed.domain.UserId;
import com.streampulse.feed.support.ItContainers;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcFollowGraphIT {

    private static JdbcTemplate jdbc;
    private static JdbcFollowGraph graph;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        var postgres = ItContainers.postgres();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("activity_feed")
                .defaultSchema("activity_feed")
                .createSchemas(true)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        graph = new JdbcFollowGraph(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), Clock.systemUTC());
    }

    private static UserId unique(String prefix) {
        return user(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    @DisplayName("a repeated follow creates one row and counts once")
    void repeatedFollowCountsOnce() {
        UserId a = unique("a");
        UserId b = unique("b");

        assertThat(graph.follow(a, b)).isTrue();
        assertThat(graph.follow(a, b)).isFalse();

        assertThat(graph.followerCount(b)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM activity_feed.follows WHERE followee_id = ?", Integer.class, b.value()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unfollow removes the row and decrements once; unfollowing a stranger changes nothing")
    void unfollowRemovesOnce() {
        UserId a = unique("a");
        UserId b = unique("b");
        graph.follow(a, b);

        assertThat(graph.unfollow(a, b)).isTrue();
        assertThat(graph.unfollow(a, b)).isFalse();
        assertThat(graph.unfollow(unique("x"), b)).isFalse();

        assertThat(graph.followerCount(b)).isZero();
    }

    @Test
    @DisplayName("an author nobody follows has a count of zero")
    void unknownAuthorHasNoFollowers() {
        assertThat(graph.followerCount(unique("nobody"))).isZero();
    }

    @Test
    @DisplayName("the database itself refuses a self-follow and a negative count")
    void constraintsHoldWithoutTheApplication() {
        UserId a = unique("a");

        assertThatThrownBy(() ->
                        jdbc.update("INSERT INTO activity_feed.follows VALUES (?, ?, now())", a.value(), a.value()))
                .hasMessageContaining("follows_check");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO activity_feed.follower_counts VALUES (?, -1)", a.value()))
                .hasMessageContaining("followers");
    }

    @Test
    @DisplayName("the same follow requested by many threads at once is recorded exactly once")
    void concurrentIdenticalFollowsCountOnce() throws Exception {
        UserId a = unique("a");
        UserId b = unique("b");

        List<Boolean> results = concurrently(16, i -> graph.follow(a, b));

        assertThat(results).filteredOn(r -> r).hasSize(1);
        assertThat(graph.followerCount(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("many distinct followers arriving at once are all counted: the counter row is serialised, not lost")
    void concurrentDistinctFollowsAreAllCounted() throws Exception {
        UserId star = unique("star");

        concurrently(32, i -> graph.follow(unique("f" + i), star));

        assertThat(graph.followerCount(star)).isEqualTo(32);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM activity_feed.follows WHERE followee_id = ?", Long.class, star.value()))
                .isEqualTo(32L);
    }

    @Test
    @DisplayName("followers are paged by keyset in id order, and the last page is short")
    void followersAfterPagesByKeyset() {
        UserId author = unique("author");
        for (char c = 'a'; c <= 'g'; c++) {
            graph.follow(user("f" + author.value() + "-" + c), author);
        }

        var all = new ArrayList<UserId>();
        Optional<UserId> after = Optional.empty();
        List<Integer> pageSizes = new ArrayList<>();
        while (true) {
            List<UserId> page = graph.followersAfter(author, after, 3);
            pageSizes.add(page.size());
            all.addAll(page);
            if (page.size() < 3) {
                break;
            }
            after = Optional.of(page.getLast());
        }

        assertThat(pageSizes).containsExactly(3, 3, 1);
        assertThat(all).hasSize(7).doesNotHaveDuplicates();
        assertThat(all).extracting(UserId::value).isSorted();
    }

    @Test
    @DisplayName("followeesOf returns whom the reader follows with each one's follower count, up to the bound")
    void followeesOfReturnsTheCounts() {
        UserId reader = unique("reader");
        UserId popular = unique("popular");
        UserId quiet = unique("quiet");
        graph.follow(reader, popular);
        graph.follow(reader, quiet);
        graph.follow(unique("x"), popular);
        graph.follow(unique("y"), popular);

        List<Followee> followees = graph.followeesOf(reader, 10);

        assertThat(followees).containsExactlyInAnyOrder(new Followee(popular, 3), new Followee(quiet, 1));
        assertThat(graph.followeesOf(reader, 1)).hasSize(1);
        assertThat(graph.followeesOf(unique("none"), 10)).isEmpty();
    }

    @Test
    @DisplayName("the two read queries are served by an index, not a scan: 'who follows X' by the followee index")
    void queriesUseTheirIndexes() {
        String followersPlan = plan(
                "EXPLAIN SELECT follower_id FROM activity_feed.follows WHERE followee_id = 'x' AND follower_id > '' "
                        + "ORDER BY follower_id LIMIT 500");
        String followeesPlan = plan("EXPLAIN SELECT followee_id FROM activity_feed.follows WHERE follower_id = 'x'");

        assertThat(followersPlan).contains("follows_by_followee");
        assertThat(followeesPlan).contains("follows_pkey");
    }

    /** Planner statistics on a near-empty table prefer a scan; turn that off so the plan shows which index exists to serve. */
    private static String plan(String explain) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                var rows = new ArrayList<String>();
                try (var rs = statement.executeQuery(explain)) {
                    while (rs.next()) {
                        rows.add(rs.getString(1));
                    }
                }
                return String.join("\n", rows);
            }
        });
    }

    private static <T> List<T> concurrently(int threads, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(index);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
