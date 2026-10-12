/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import com.streampulse.query.domain.LatestMetric;
import com.streampulse.query.domain.MetricPoint;
import com.streampulse.query.domain.TitleMetadata;
import com.streampulse.query.domain.TitleOverview;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Three independent calls, one shared deadline, and a degraded answer instead of a failed one. */
public final class TitleOverviewService {

    private static final Logger LOG = LoggerFactory.getLogger(TitleOverviewService.class);

    private final LatestMetricsPort latestPort;
    private final MetricHistoryPort historyPort;
    private final TitleCatalogPort catalogPort;
    private final Executor blockingExecutor;
    private final Clock clock;
    private final Duration budget;
    private final Duration historyLookback;

    public TitleOverviewService(
            LatestMetricsPort latestPort,
            MetricHistoryPort historyPort,
            TitleCatalogPort catalogPort,
            Executor blockingExecutor,
            Clock clock,
            Duration budget,
            Duration historyLookback) {
        this.latestPort = latestPort;
        this.historyPort = historyPort;
        this.catalogPort = catalogPort;
        this.blockingExecutor = blockingExecutor;
        this.clock = clock;
        this.budget = budget;
        this.historyLookback = historyLookback;
    }

    /** Never fails: a part that cannot answer is replaced by an empty value and named in {@code degraded}. */
    public CompletableFuture<TitleOverview> overview(String tenantId, String titleId) {
        Instant now = clock.instant();
        Instant deadline = now.plus(budget);
        HistoryQuery recent = new HistoryQuery(tenantId, null, titleId, null, now.minus(historyLookback), now);

        CompletableFuture<Part<List<LatestMetric>>> latest =
                guarded("latest", deadline, () -> latestPort.latest(tenantId, titleId), List.of());
        CompletableFuture<Part<List<MetricPoint>>> history =
                guarded("history", deadline, () -> historyPort.range(recent), List.of());
        CompletableFuture<Part<TitleMetadata>> metadata =
                guarded("metadata", deadline, () -> catalogPort.title(tenantId, titleId), null);

        return CompletableFuture.allOf(latest, history, metadata).thenApply(joined -> {
            List<String> degraded = new ArrayList<>();
            Part<List<LatestMetric>> latestPart = latest.join();
            Part<List<MetricPoint>> historyPart = history.join();
            Part<TitleMetadata> metadataPart = metadata.join();
            if (latestPart.degraded()) {
                degraded.add("latest");
            }
            if (historyPart.degraded()) {
                degraded.add("history");
            }
            if (metadataPart.degraded()) {
                degraded.add("metadata");
            }
            return new TitleOverview(
                    tenantId, titleId, metadataPart.value(), latestPart.value(), historyPart.value(), degraded);
        });
    }

    private <T> CompletableFuture<Part<T>> guarded(String part, Instant deadline, Supplier<T> call, T fallback) {
        CompletableFuture<T> running;
        try {
            running = CompletableFuture.supplyAsync(call, blockingExecutor);
        } catch (RejectedExecutionException saturated) {
            LOG.warn("overview part '{}' degraded: blocking executor is full", part);
            return CompletableFuture.completedFuture(new Part<>(fallback, true));
        }
        long remainingNanos =
                Math.max(Duration.between(clock.instant(), deadline).toNanos(), 1L);
        // WHY the executor is bounded: orTimeout completes this future but does NOT cancel the call, which
        // keeps its worker until it returns. An unbounded pool would pile up abandoned calls behind a slow
        // dependency; a bounded one rejects instead, and a rejection degrades the part (REQ-QA-05).
        return running.orTimeout(remainingNanos, TimeUnit.NANOSECONDS)
                .thenApply(value -> new Part<>(value, false))
                .exceptionally(failure -> {
                    LOG.warn("overview part '{}' degraded: {}", part, failure.toString());
                    return new Part<>(fallback, true);
                });
    }

    private record Part<T>(T value, boolean degraded) {}
}
