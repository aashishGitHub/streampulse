/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streampulse.alert.domain.EvaluationOutcome;
import com.streampulse.alert.domain.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerAlertMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerAlertMetrics metrics = new MicrometerAlertMetrics(registry);

    @Test
    @DisplayName("evaluations, fired and suppressed are counted under their contract names with bounded tags")
    void names() {
        metrics.evaluated("threshold", EvaluationOutcome.DUPLICATE);
        metrics.evaluated("threshold", EvaluationOutcome.DUPLICATE);
        metrics.fired("streamflix", Severity.CRITICAL);
        metrics.suppressed("streamflix", Severity.WARNING);

        assertThat(registry.get("streampulse.alert.evaluations")
                        .tags("kind", "threshold", "outcome", "duplicate")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(registry.get("streampulse.alert.fired")
                        .tags("tenant", "streamflix", "severity", "critical")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("streampulse.alert.suppressed")
                        .tags("severity", "warning")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
