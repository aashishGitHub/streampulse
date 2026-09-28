/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Circuit-breaker and timeout settings for the event publisher (REQ-IG-21, REQ-RS-02).
 *
 * <h2>Every value here is a trade, not a default worth copying</h2>
 *
 * These are starting points chosen to be <em>explainable</em>, and Phase 9 replaces them with
 * measurements. What matters is knowing which way each dial cuts:
 *
 * <pre>
 *   failureRateThreshold   lower  → trips sooner, protects harder, more false trips
 *   slidingWindowSize      larger → steadier signal, slower to notice a real outage
 *   minimumNumberOfCalls   larger → no tripping on a handful of unlucky calls at 3am
 *                                   when traffic is thin; slower to react in a real one
 *   waitDurationInOpenState longer → gives the dependency room to recover, but every
 *                                   client is refused for that whole time
 *   permittedCalls...      larger → more confident it recovered, more load on something
 *                                   that may still be fragile
 * </pre>
 *
 * <h2>Why minimumNumberOfCalls exists at all</h2>
 *
 * Without it, a window of 20 that has seen 2 calls, both failed, reads as 100% failure and
 * opens the breaker. At 04:00 with almost no traffic, two unlucky calls would take the
 * publisher out for 30 seconds. The minimum is what stops a rate being computed from a
 * sample too small to mean anything.
 */
@ConfigurationProperties(prefix = "streampulse.ingest.publisher-resilience")
@Validated
public record PublisherResilienceProperties(

        /** COUNT_BASED window: the last N calls. Time-based would be the last N seconds. */
        @Min(1) int slidingWindowSize,

        /** No rate is computed until this many calls are in the window. */
        @Min(1) int minimumNumberOfCalls,

        /** Percent of failed calls that opens the breaker. */
        @DecimalMin("1.0") @DecimalMax("100.0") float failureRateThreshold,

        /**
         * Percent of SLOW calls that opens the breaker, even when they all eventually
         * succeed.
         *
         * <p>This is the subtle one. A dependency answering every call in 4 seconds is
         * failing — it is holding a permit and a thread each time — but a failure-rate
         * breaker sees 0% failures and stays closed. Slow is a failure mode in its own right.
         */
        @DecimalMin("1.0") @DecimalMax("100.0") float slowCallRateThreshold,

        /** Above this, a successful call still counts as slow. Must be under callTimeout. */
        @NotNull Duration slowCallDurationThreshold,

        /** How long the breaker refuses everything before letting trial calls through. */
        @NotNull Duration waitDurationInOpenState,

        /** Trial calls allowed in HALF_OPEN. Their failure rate decides open vs closed. */
        @Min(1) int permittedCallsInHalfOpenState,

        /**
         * Hard ceiling on one publish. Without it a hung broker parks the caller forever,
         * and with virtual threads that is quiet: no thread-pool exhaustion to alert on,
         * just an ever-growing pile of blocked threads each still holding an in-flight
         * permit, until ingest stops accepting anything at all.
         */
        @NotNull Duration callTimeout,

        /**
         * Total attempts INCLUDING the first, so 3 means one call and two retries.
         *
         * <p>Keep it small. Every attempt runs while the batch still holds an in-flight
         * permit, so this multiplies capacity consumption during exactly the incident where
         * capacity matters most. Three is enough to ride over a blip; ten is a way of
         * turning a dependency's bad minute into your own bad hour.
         */
        @Min(1) int retryMaxAttempts,

        /** Wait before the first retry. Doubled (times the multiplier) on each attempt. */
        @NotNull Duration retryInitialBackoff,

        /** Growth factor between attempts. 2.0 is the usual doubling. */
        @DecimalMin("1.0") double retryBackoffMultiplier,

        /**
         * Randomisation applied to each wait, as a fraction: 0.5 means each interval is
         * picked from ±50% of its nominal value.
         *
         * <p>Jitter is not a refinement, it is the point. A hundred callers that failed at
         * the same instant will, without it, retry at the same instant — the retry wave is
         * exactly as synchronised as the failure was, and the second spike lands on a
         * dependency that has not recovered from the first. Spreading them is what turns a
         * thundering herd back into ordinary traffic.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") double retryJitterFactor) {}
