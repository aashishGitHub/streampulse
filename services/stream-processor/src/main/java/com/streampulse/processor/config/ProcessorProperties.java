/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated at startup: a bad value must fail the boot, not the first window. */
@Validated
@ConfigurationProperties("streampulse.processor")
public record ProcessorProperties(
        @NotBlank String rawTopic,
        @NotBlank String aggregateTopic,
        @NotBlank String rawDltTopic,
        @NotNull Duration windowSize,
        @NotNull Duration grace,
        @NotNull Duration maxFutureSkew) {

    public ProcessorProperties {
        if (windowSize != null && (windowSize.isZero() || windowSize.isNegative())) {
            throw new IllegalArgumentException("streampulse.processor.window-size must be positive");
        }
        if (grace != null && grace.isNegative()) {
            throw new IllegalArgumentException("streampulse.processor.grace must not be negative");
        }
    }
}
