/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated configuration.
 *
 * <h2>Why this exists instead of scattered {@code @Value} strings</h2>
 *
 * Binding happens at <b>startup</b>, so a missing or out-of-range value throws before the
 * app is ready. The pod never passes its readiness probe and the rolling update halts.
 *
 * <p>With loose {@code @Value("${...}")} the opposite happens: the pod goes Ready, takes
 * traffic, and fails on the first request that touches that code path. Fail-fast is not a
 * style preference here — it is the difference between a stopped rollout and a partial
 * outage. REQ-LC-03 / REQ-IG-*.
 *
 * <p>A record gives immutability for free, and relaxed binding means
 * {@code STREAMPULSE_INGEST_MAX_BATCH_SIZE=500} as an environment variable maps to
 * {@code maxBatchSize} — which is how this gets configured in Kubernetes.
 */
@ConfigurationProperties(prefix = "streampulse.ingest")
@Validated
public record IngestProperties(
        @NotBlank String rawTopic,
        @Min(1) int maxBatchSize,

        /**
         * How many batches may be in flight at once. THE backpressure bound (REQ-IG-19).
         *
         * <p>Too low and throughput suffers; too high and a spike is absorbed into the heap
         * instead of being refused. Phase 9 sets it from measurement rather than intuition.
         */
        @Min(1) int maxInFlightBatches,

        /** How long to wait for a permit before returning 503. */
        @NotNull Duration permitWait,

        /**
         * Deduplication window. Not optional: without expiry the key set grows forever. A
         * retry arriving after this is treated as new, which is an acceptable trade.
         */
        @NotNull Duration idempotencyTtl,

        /**
         * Whether to publish when the dedup store is unreachable.
         *
         * <p>True here: losing telemetry is worse than duplicating it, and downstream
         * aggregation tolerates duplicates while a gap is unrecoverable. For a payments
         * system the correct value is false. ADR-0007.
         */
        boolean failOpenOnIdempotencyError) {}
