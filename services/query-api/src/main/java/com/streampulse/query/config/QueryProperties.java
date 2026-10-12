/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.query.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated at startup: a bad value must fail the boot, not the first request. */
@Validated
@ConfigurationProperties("streampulse.query")
public record QueryProperties(
        @Valid @NotNull Overview overview,
        @Valid @NotNull Blocking blocking,
        @Valid @NotNull Stream stream,
        @NotBlank String latestKeyPrefix,
        @NotNull Duration callTimeout) {

    public QueryProperties {
        requirePositive("call-timeout", callTimeout);
    }

    /** {@code budget} is a target until Phase 9 measures it. */
    public record Overview(
            @NotNull Duration budget,
            @NotNull Duration historyLookback,
            @Min(1) int maxHistoryRows,
            @NotNull Duration catalogLatency) {

        public Overview {
            requirePositive("overview.budget", budget);
            requirePositive("overview.history-lookback", historyLookback);
        }
    }

    /** The capacity reserved for blocking calls; must not exceed the JDBC pool, or the pool becomes the limit. */
    public record Blocking(@Min(1) int size, @Min(1) int queueCapacity) {}

    public record Stream(
            @NotNull Duration heartbeat,
            @NotNull Duration coalesceWindow,
            @Min(1) int replayLimit,
            @NotBlank String key,
            boolean autoStart) {

        public Stream {
            requirePositive("stream.heartbeat", heartbeat);
            requirePositive("stream.coalesce-window", coalesceWindow);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("streampulse.query." + name + " must be positive");
        }
    }
}
