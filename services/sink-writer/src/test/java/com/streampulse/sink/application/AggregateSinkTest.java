/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.sink.domain.AggregateRecord;
import com.streampulse.sink.domain.MetricProjection;
import com.streampulse.sink.domain.MetricWindow;
import com.streampulse.sink.domain.MetricWindowStore;
import com.streampulse.sink.support.Aggregates;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregateSinkTest {

    private static final Instant NOW = Aggregates.WINDOW_START.plusSeconds(25);

    private final List<String> events = new ArrayList<>();
    private final List<Duration> freshness = new ArrayList<>();
    private int projectionFailures;
    private RuntimeException storeFailure;
    private RuntimeException projectionFailure;

    private final MetricWindowStore store = windows -> {
        if (storeFailure != null) {
            throw storeFailure;
        }
        events.add("store:" + windows.size());
    };
    private final MetricProjection projection = windows -> {
        events.add("project:" + windows.size());
        if (projectionFailure != null) {
            throw projectionFailure;
        }
    };
    private final SinkMetrics metrics = new SinkMetrics() {
        @Override
        public void freshness(Duration lag) {
            events.add("freshness");
            freshness.add(lag);
        }

        @Override
        public void projectionFailed() {
            projectionFailures++;
        }
    };
    private final AggregateSink sink = new AggregateSink(store, projection, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    private static AggregateRecord record(String title) {
        MetricWindow w = Aggregates.window("t", title, "r", Aggregates.WINDOW_START, 1);
        return new AggregateRecord("k-" + title, new byte[0], w);
    }

    @Test
    @DisplayName("the durable store is written first, freshness is recorded at that moment, then the projection")
    void storesThenRecordsFreshnessThenProjects() throws Exception {
        sink.write(List.of(record("a"), record("b")));

        assertThat(events).containsExactly("store:2", "freshness", "freshness", "project:2");
        // window end is start + 10 s, now is start + 25 s
        assertThat(freshness).containsOnly(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("when the durable store fails nothing is projected or reported fresh, and the failure propagates")
    void storeFailureStopsEverythingElse() {
        storeFailure = new IllegalStateException("postgres down");

        assertThatThrownBy(() -> sink.write(List.of(record("a")))).isSameAs(storeFailure);

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a projection failure is counted and swallowed: Postgres already holds the data")
    void projectionFailureDoesNotFailTheBatch() throws Exception {
        projectionFailure = new IllegalStateException("redis down", new IOException());

        sink.write(List.of(record("a")));

        assertThat(projectionFailures).isEqualTo(1);
        assertThat(freshness).hasSize(1);
    }
}
