/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.application;

import com.streampulse.query.domain.LatestMetric;
import com.streampulse.query.domain.MetricPoint;
import com.streampulse.query.domain.TitleMetadata;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Ports with a fixed latency or a fixed failure, recording the thread each call ran on. */
final class FakePorts {

    private FakePorts() {}

    static final class Latest implements LatestMetricsPort {
        final Queue<String> threads = new ConcurrentLinkedQueue<>();
        private final Duration latency;
        private final boolean fail;

        Latest(Duration latency, boolean fail) {
            this.latency = latency;
            this.fail = fail;
        }

        @Override
        public List<LatestMetric> latest(String tenantId, String titleId) {
            threads.add(Thread.currentThread().getName());
            pause(latency);
            if (fail) {
                throw new IllegalStateException("latest store down");
            }
            return List.of();
        }
    }

    static final class History implements MetricHistoryPort {
        final Queue<String> threads = new ConcurrentLinkedQueue<>();
        private final Duration latency;
        private final boolean fail;

        History(Duration latency, boolean fail) {
            this.latency = latency;
            this.fail = fail;
        }

        @Override
        public List<MetricPoint> range(HistoryQuery query) {
            threads.add(Thread.currentThread().getName());
            pause(latency);
            if (fail) {
                throw new IllegalStateException("history store down");
            }
            return List.of();
        }
    }

    static final class Catalog implements TitleCatalogPort {
        final Queue<String> threads = new ConcurrentLinkedQueue<>();
        private final Duration latency;
        private final boolean fail;

        Catalog(Duration latency, boolean fail) {
            this.latency = latency;
            this.fail = fail;
        }

        @Override
        public TitleMetadata title(String tenantId, String titleId) {
            threads.add(Thread.currentThread().getName());
            pause(latency);
            if (fail) {
                throw new IllegalStateException("catalog down");
            }
            return new TitleMetadata(titleId, "A title");
        }
    }

    static void pause(Duration latency) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
