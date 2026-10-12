/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Range reads over the durable tier. Blocking JDBC, so it runs on the bounded executor (REQ-QA-06). */
public final class MetricQueryService {

    /** What the sink writes today (hot-store contract). The other contract metrics are not produced yet. */
    private static final Set<String> PRODUCED = Set.of("CONCURRENT_VIEWERS", "ERROR_RATE");

    private final MetricHistoryPort history;
    private final Executor blockingExecutor;

    public MetricQueryService(MetricHistoryPort history, Executor blockingExecutor) {
        this.history = history;
        this.blockingExecutor = blockingExecutor;
    }

    /**
     * @throws java.util.concurrent.RejectedExecutionException when the executor is full; the caller answers 503
     */
    public CompletableFuture<MetricQueryResult> query(
            String tenantId, String metric, String titleId, String region, Instant from, Instant to) {
        if (!PRODUCED.contains(metric)) {
            throw new UnknownMetricException(metric);
        }
        if (!from.isBefore(to)) {
            throw new InvalidRequestException("'from' must be before 'to'");
        }
        HistoryQuery query = new HistoryQuery(tenantId, metric, titleId, region, from, to);
        return CompletableFuture.supplyAsync(
                () -> new MetricQueryResult(history.range(query), MetricQueryResult.STORE), blockingExecutor);
    }
}
