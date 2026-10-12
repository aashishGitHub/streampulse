/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.config;

import com.streampulse.processor.domain.ProcessorMetrics;
import com.streampulse.processor.domain.RejectionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;

public class MicrometerProcessorMetrics implements ProcessorMetrics {

    private final Map<RejectionReason, Counter> rejected = new EnumMap<>(RejectionReason.class);
    private final Counter duplicates;

    public MicrometerProcessorMetrics(MeterRegistry registry) {
        // Registered up front for every reason: a counter that does not exist until the first
        // rejection cannot be graphed as zero, and "zero rejections" is what a healthy
        // pipeline looks like.
        for (RejectionReason reason : RejectionReason.values()) {
            rejected.put(
                    reason,
                    Counter.builder("streampulse.processor.events.rejected")
                            .description("Input records sent to the dead-letter topic, by reason")
                            .tag("reason", reason.name())
                            .baseUnit("events")
                            .register(registry));
        }
        this.duplicates = Counter.builder("streampulse.processor.events.duplicates")
                .description("Events dropped because their id was already seen")
                .baseUnit("events")
                .register(registry);
    }

    @Override
    public void rejected(RejectionReason reason) {
        rejected.get(reason).increment();
    }

    @Override
    public void duplicateDropped() {
        duplicates.increment();
    }
}
