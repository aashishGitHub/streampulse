/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.alert.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated at startup: a bad value must fail the boot, not the first alert. */
@Validated
@ConfigurationProperties("streampulse.alerts")
public record AlertProperties(
        @NotBlank String aggregateTopic,
        @NotBlank String configTopic,
        @NotBlank String alertsTopic,
        @NotNull Duration absenceGrace,
        @NotNull Duration sweepInterval,
        @NotNull Duration deliveryTimeout,
        @NotNull @Valid Sse sse,
        @NotNull @Valid Rulebook rulebook,
        @NotNull List<String> corsAllowedOrigins) {

    /**
     * @param heartbeat idle time before a comment line is sent
     * @param replayBuffer events kept for Last-Event-ID resume, across all tenants
     * @param subscriberBuffer how far a client may lag before its stream is ended
     * @param maxLifetime a stream ends after this long; EventSource reconnects with its last id
     */
    public record Sse(
            @NotNull Duration heartbeat,
            @Positive int replayBuffer,
            @Positive int subscriberBuffer,
            @NotNull Duration maxLifetime) {}

    /** @param enabled false in the no-Docker wiring test, where nothing may touch a broker */
    public record Rulebook(boolean enabled, @NotNull Duration pollTimeout) {}
}
