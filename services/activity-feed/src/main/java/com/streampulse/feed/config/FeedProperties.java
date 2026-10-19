/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.feed.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validated at startup: a bad value must fail the boot, not the first request. */
@Validated
@ConfigurationProperties("streampulse.feed")
public record FeedProperties(
        @NotBlank String watchTopic,
        @NotBlank String watchDltTopic,
        @Positive long celebrityThreshold,
        @NotBlank @Size(min = 32) String cursorSecret,
        @NotNull @DurationMin(millis = 1) Duration publishTimeout,
        @NotNull @DurationMin(millis = 1) Duration dltSendTimeout,
        @Positive int followerPageSize,
        @NotNull @Valid Bounded timeline,
        @NotNull @Valid Bounded outbox,
        @NotNull @Valid Read read,
        @NotNull @Valid Ranking ranking,
        @NotNull @Valid Retry retry) {

    /** A sorted set with a length bound and an expiry. A TTL of zero would delete it as it is written. */
    public record Bounded(@Positive int maxLength, @NotNull @DurationMin(seconds = 1) Duration ttl) {}

    public record Read(
            @Positive int timelineWindow,
            @Positive int celebrityWindow,
            @Positive int maxCelebrities,
            @Positive int maxFollowees) {}

    public record Ranking(@NotNull @DurationMin(seconds = 1) Duration halfLife) {}

    /** How long the listener waits between redeliveries while a store is down. Never gives up. */
    public record Retry(
            @NotNull @DurationMin(millis = 1) Duration initialInterval,
            @Positive double multiplier,
            @NotNull @DurationMin(millis = 1) Duration maxInterval) {}
}
