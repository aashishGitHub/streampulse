/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application;

import com.streampulse.sink.application.batch.BatchSink;
import com.streampulse.sink.domain.AggregateRecord;
import com.streampulse.sink.domain.MetricProjection;
import com.streampulse.sink.domain.MetricWindow;
import com.streampulse.sink.domain.MetricWindowStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable write first, then the derived projection. Returning normally means the batch is in
 * Postgres; whether Redis kept up is a separate, rebuildable matter. ADR-0013.
 */
public final class AggregateSink implements BatchSink<AggregateRecord> {

    private static final Logger log = LoggerFactory.getLogger(AggregateSink.class);

    private final MetricWindowStore store;
    private final MetricProjection projection;
    private final SinkMetrics metrics;
    private final Clock clock;

    public AggregateSink(MetricWindowStore store, MetricProjection projection, SinkMetrics metrics, Clock clock) {
        this.store = store;
        this.projection = projection;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public void write(List<AggregateRecord> batch) {
        List<MetricWindow> windows = batch.stream().map(AggregateRecord::window).toList();

        store.upsertAll(windows);

        var now = clock.instant();
        windows.forEach(w -> metrics.freshness(Duration.between(w.windowEnd(), now)));

        try {
            projection.project(windows);
        } catch (RuntimeException e) {
            // Swallowed on purpose: Postgres already holds the data, Redis can be rebuilt from it,
            // and failing here would hold back the acknowledgement for a store that is not the
            // source of truth.
            metrics.projectionFailed();
            log.warn("Projection of {} window(s) failed; Postgres is current, Redis is behind", windows.size(), e);
        }
    }
}
