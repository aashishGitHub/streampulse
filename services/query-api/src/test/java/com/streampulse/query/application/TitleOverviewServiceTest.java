/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.query.domain.TitleOverview;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TitleOverviewServiceTest {

    private static final Duration BUDGET = Duration.ofMillis(300);
    private static final Duration FAST = Duration.ofMillis(50);
    private static final Duration MEDIUM = Duration.ofMillis(100);
    private static final Duration SLOW = Duration.ofSeconds(1);

    private ThreadPoolExecutor executor = BoundedExecutor.create("blocking", 8, 16);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private TitleOverviewService service(
            FakePorts.Latest latest, FakePorts.History history, FakePorts.Catalog catalog) {
        return new TitleOverviewService(
                latest, history, catalog, executor, Clock.systemUTC(), BUDGET, Duration.ofMinutes(15));
    }

    private static TitleOverview await(java.util.concurrent.CompletableFuture<TitleOverview> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a call slower than the budget is degraded and the answer still arrives at the budget")
    void slowCallIsDegradedAndTheBudgetHolds() throws Exception {
        var service = service(
                new FakePorts.Latest(FAST, false),
                new FakePorts.History(MEDIUM, false),
                new FakePorts.Catalog(SLOW, false));

        long started = System.nanoTime();
        TitleOverview overview = await(service.overview("t1", "title-1"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(overview.degraded()).containsExactly("metadata");
        assertThat(overview.metadata()).isNull();
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(250)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("the three calls run concurrently, not one after another")
    void callsRunInParallel() throws Exception {
        Duration each = Duration.ofMillis(200);
        var service = service(
                new FakePorts.Latest(each, false),
                new FakePorts.History(each, false),
                new FakePorts.Catalog(each, false));

        long started = System.nanoTime();
        TitleOverview overview = await(service.overview("t1", "title-1"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(overview.degraded()).isEmpty();
        assertThat(overview.metadata()).isNotNull();
        assertThat(elapsed).isLessThan(Duration.ofMillis(450));
    }

    @Test
    @DisplayName("every call failing still answers, with everything degraded")
    void allCallsFailingStillAnswers() throws Exception {
        var service = service(
                new FakePorts.Latest(Duration.ZERO, true),
                new FakePorts.History(Duration.ZERO, true),
                new FakePorts.Catalog(Duration.ZERO, true));

        TitleOverview overview = await(service.overview("t1", "title-1"));

        assertThat(overview.degraded()).containsExactly("latest", "history", "metadata");
        assertThat(overview.latest()).isEmpty();
        assertThat(overview.history()).isEmpty();
        assertThat(overview.metadata()).isNull();
    }

    @Test
    @DisplayName("the work runs on the named executor, never the common pool")
    void workNeverRunsOnTheCommonPool() throws Exception {
        var latest = new FakePorts.Latest(Duration.ZERO, false);
        var history = new FakePorts.History(Duration.ZERO, false);
        var catalog = new FakePorts.Catalog(Duration.ZERO, false);

        await(service(latest, history, catalog).overview("t1", "title-1"));

        assertThat(latest.threads).hasSize(1).allMatch(name -> name.startsWith("blocking-"));
        assertThat(history.threads).hasSize(1).allMatch(name -> name.startsWith("blocking-"));
        assertThat(catalog.threads).hasSize(1).allMatch(name -> name.startsWith("blocking-"));
        assertThat(latest.threads).noneMatch(name -> name.contains("commonPool"));
    }

    @Test
    @DisplayName("a saturated executor degrades the request at once instead of waiting for capacity")
    void saturatedExecutorDegradesInsteadOfBlocking() throws Exception {
        executor.shutdownNow();
        executor = BoundedExecutor.create("blocking", 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable hold = () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        executor.execute(hold);
        executor.execute(hold);
        var latest = new FakePorts.Latest(Duration.ZERO, false);
        var service = service(
                latest, new FakePorts.History(Duration.ZERO, false), new FakePorts.Catalog(Duration.ZERO, false));

        long started = System.nanoTime();
        TitleOverview overview = await(service.overview("t1", "title-1"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        release.countDown();

        assertThat(overview.degraded()).containsExactly("latest", "history", "metadata");
        assertThat(elapsed).isLessThan(Duration.ofMillis(100));
        assertThat(latest.threads).isEmpty();
    }
}
