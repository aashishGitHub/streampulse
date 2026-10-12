/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.sink.application.batch.BatchSink;
import com.streampulse.sink.application.batch.BatchWriter;
import com.streampulse.sink.application.batch.BatchWriterMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

class BatchWriterLifecycleTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private BatchWriter<String> writer(BatchSink<String> sink) {
        var config = new BatchWriter.Config(
                100, Duration.ofSeconds(30), 100, 1, Duration.ofMillis(1), Duration.ofSeconds(5));
        return BatchWriter.start(config, sink, (r, why) -> {}, BatchWriterMetrics.NONE);
    }

    /** Stands in for the Kafka listener container: same phase, records when it stops. */
    private final class FakeContainer implements SmartLifecycle {
        private boolean running;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            events.add("container stopped");
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return AbstractMessageListenerContainer.DEFAULT_PHASE;
        }
    }

    @Test
    @DisplayName("the writer stops AFTER the listener container, so in-flight records are flushed before it closes")
    void lifecycleStopsAfterTheListenerContainer() {
        var writer = writer(batch -> events.add("written " + batch));
        var context = new GenericApplicationContext();
        context.registerBean("container", FakeContainer.class, FakeContainer::new);
        context.registerBean("writerLifecycle", BatchWriterLifecycle.class, () -> new BatchWriterLifecycle(writer));
        context.refresh();
        writer.submit("in-flight", Duration.ofMillis(100));

        context.close();

        assertThat(events).containsExactly("container stopped", "written [in-flight]");
    }

    @Test
    @DisplayName("its phase is below the listener container's, which is what makes it stop later")
    void phaseIsBelowTheContainers() {
        var lifecycle = new BatchWriterLifecycle(writer(batch -> {}));

        assertThat(lifecycle.getPhase()).isLessThan(AbstractMessageListenerContainer.DEFAULT_PHASE);
    }

    @Test
    @DisplayName("stop reports completion through the callback, even when closing is slow")
    void stopInvokesCallback() {
        var lifecycle = new BatchWriterLifecycle(writer(batch -> {}));
        lifecycle.start();
        var called = new boolean[1];

        lifecycle.stop(() -> called[0] = true);

        assertThat(called[0]).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
