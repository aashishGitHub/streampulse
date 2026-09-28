/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the gRPC ingest listener (BS-09).
 *
 * <p>Typed and validated for the same reason {@link IngestProperties} is: binding happens at
 * <b>startup</b>, so a nonsense value stops the rollout instead of failing the first call
 * that happens to touch it.
 */
@ConfigurationProperties(prefix = "streampulse.ingest.grpc")
@Validated
public record GrpcProperties(

        /**
         * The listening port. Separate from the HTTP one because it is a different protocol on
         * a different connection — and in Kubernetes a different Service, since gRPC needs L7
         * load balancing and REST does not (REQ-GR-07, ADR-0010).
         *
         * <p>0 means "let the OS pick", which is what the integration tests use so that two
         * test classes running in the same build cannot collide on a fixed port.
         */
        @Min(0) @Max(65535) int port,

        /**
         * How many streamed events are gathered before one call into the use case — and
         * therefore both the ACK interval and the inbound flow-control window.
         *
         * <pre>
         *   smaller → ACKs sooner, so the client frees its buffer sooner;
         *             more Redis round trips and more publishes per event
         *   larger  → better broker batching and less per-batch overhead;
         *             the client holds unacknowledged events longer, and a crash
         *             costs a bigger resend
         * </pre>
         *
         * <p>50 is an explainable starting point, not a measured one. Phase 9 replaces it — and
         * the number it should be measured against is the REST batch size, since the whole
         * ADR-0010 comparison assumes the two protocols do comparable work per call.
         */
        @Min(1) int streamFlushSize,

        /**
         * How long to let in-flight calls finish on SIGTERM before cancelling them.
         *
         * <p>Must stay comfortably under Kubernetes' {@code terminationGracePeriodSeconds}
         * once the drain timeout and the HTTP graceful shutdown are added to it, or the pod is
         * SIGKILLed mid-drain and the whole phase ladder buys nothing.
         */
        @NotNull Duration shutdownTimeout) {}
