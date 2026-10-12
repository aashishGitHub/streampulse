/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.sink.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated at startup: a bad value must fail the boot, not the first batch. */
@Validated
@ConfigurationProperties("streampulse.sink")
public record SinkProperties(
        @NotBlank String aggregateTopic,
        @NotBlank String aggregateDltTopic,
        @NotNull @Valid Batch batch,
        @NotNull Duration offerTimeout,
        @NotNull Duration pollBudget,
        @NotNull Duration dltSendTimeout,
        @NotNull @Valid Retry retry) {

    public record Batch(
            @Positive int maxSize,
            @NotNull Duration maxWait,
            @Positive int queueCapacity,
            @Positive int flushAttempts,
            @NotNull Duration retryBackoff,
            @NotNull Duration closeTimeout) {}

    /** How long the listener waits between redeliveries while the store is down. Never gives up. */
    public record Retry(
            @NotNull Duration initialInterval, @Positive double multiplier, @NotNull Duration maxInterval) {}
}
