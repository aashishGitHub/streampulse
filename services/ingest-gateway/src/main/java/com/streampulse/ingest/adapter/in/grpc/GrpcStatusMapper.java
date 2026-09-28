/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import com.google.protobuf.Duration;
import com.google.rpc.RetryInfo;
import com.streampulse.ingest.domain.BackpressureException;
import com.streampulse.ingest.domain.PublishUnavailableException;
import com.streampulse.ingest.domain.QuotaExceededException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain failure → gRPC status. The gRPC twin of {@code IngestExceptionHandler} (REQ-GR-03).
 *
 * <h2>Why this class exists at all</h2>
 *
 * Because the alternative is that the domain knows about gRPC. {@code IngestService} throws
 * {@link QuotaExceededException}; it must not throw {@code StatusRuntimeException}, or the
 * business rules would be unusable from any other protocol and untestable without gRPC on the
 * classpath. Translating at the boundary is the boundary's whole job — the same argument
 * {@code PublishUnavailableException} makes about not letting Resilience4j's exceptions
 * escape.
 *
 * <h2>The mapping, and why each one is that code</h2>
 *
 * <pre>
 *   domain                          gRPC status           HTTP equivalent   retryable?
 *   ───────────────────────────────────────────────────────────────────────────────────
 *   IllegalArgumentException     → INVALID_ARGUMENT        400              no — fix the input
 *   QuotaExceededException       → RESOURCE_EXHAUSTED      429              yes, after a wait
 *   BackpressureException        → UNAVAILABLE             503              yes, elsewhere
 *   PublishUnavailableException  → UNAVAILABLE             503              yes, after a wait
 *   (auth, in the interceptor)   → UNAUTHENTICATED         401              no
 *   anything else                → INTERNAL                500              no
 * </pre>
 *
 * <p>The distinction that earns its keep is RESOURCE_EXHAUSTED vs UNAVAILABLE, and it is the
 * same one 429 vs 503 makes on the REST side. RESOURCE_EXHAUSTED means <em>your</em> quota is
 * spent — retrying against another instance will not help, because the quota is global.
 * UNAVAILABLE means <em>we</em> are saturated or our dependency is — another instance may
 * well cope, so a retry is reasonable. Collapsing them throws away the one piece of
 * information the client needs to behave correctly.
 *
 * <h2>Why UNAVAILABLE and not INTERNAL for a publish failure</h2>
 *
 * gRPC clients (and service meshes, and the generated retry policies in a service config)
 * treat UNAVAILABLE as retryable and INTERNAL as fatal. Mapping a temporarily unreachable
 * broker to INTERNAL tells every client in the fleet to give up on data that would have
 * succeeded a second later.
 *
 * <h2>Retry info in the trailers</h2>
 *
 * A status code says "not now"; {@code google.rpc.RetryInfo} says <em>when</em>. It is the
 * standard error detail, so a Go or Kotlin client understands it without a line of
 * StreamPulse-specific code — the gRPC analogue of the {@code Retry-After} header.
 *
 * <p>The value is the domain exception's own {@code retryAfter()}, which for a publish
 * failure is the circuit breaker's remaining open window. Deriving it rather than configuring
 * it separately is what stops the two drifting: telling a client to return in 5s while the
 * breaker stays open for 30 buys five guaranteed rejections.
 *
 * <p>The client must still add jitter. Without it every throttled client returns at the same
 * instant and the second spike is worse than the first.
 */
public class GrpcStatusMapper {

    private static final Logger log = LoggerFactory.getLogger(GrpcStatusMapper.class);

    /**
     * A binary metadata key whose marshaller is the RetryInfo protobuf itself.
     *
     * <p>{@code ProtoUtils.keyForProto} derives the key name from the message's full proto
     * name and appends the {@code -bin} suffix gRPC requires for binary headers. Hand-writing
     * that string is how you produce a trailer no client library recognises.
     */
    static final Metadata.Key<RetryInfo> RETRY_INFO_KEY = ProtoUtils.keyForProto(RetryInfo.getDefaultInstance());

    /**
     * @return the exception to pass to {@code StreamObserver.onError}. Never throws.
     */
    public StatusRuntimeException toStatusException(RuntimeException failure) {
        if (failure instanceof StatusRuntimeException alreadyMapped) {
            // Thrown by the adapter itself (an empty batch, a missing tenant). It already
            // carries the right status; re-wrapping it would bury that under INTERNAL.
            return alreadyMapped;
        }

        if (failure instanceof QuotaExceededException quota) {
            // DEBUG: a throttled tenant is the system working, not failing.
            log.debug("gRPC call throttled: {}", quota.getMessage());
            return withRetryInfo(
                    Status.RESOURCE_EXHAUSTED.withDescription("tenant quota exceeded"),
                    quota.retryAfter().toMillis());
        }

        if (failure instanceof BackpressureException backpressure) {
            log.debug("gRPC call rejected for capacity");
            return withRetryInfo(
                    Status.UNAVAILABLE.withDescription("ingest is at capacity"),
                    backpressure.retryAfter().toMillis());
        }

        if (failure instanceof PublishUnavailableException unavailable) {
            // WARN: a degraded dependency is a real signal. The breaker logs the transition
            // once where it happens, so this is the per-call consequence, not the event.
            log.warn("gRPC call failed, event stream unavailable: {}", unavailable.getMessage());
            return withRetryInfo(
                    Status.UNAVAILABLE.withDescription("the event stream is temporarily unavailable"),
                    unavailable.retryAfter().toMillis());
        }

        if (failure instanceof IllegalArgumentException invalid) {
            // The message is safe to return: it comes from EventId/TenantId rejecting a value
            // the caller sent, so it names the caller's own mistake and nothing of ours.
            return Status.INVALID_ARGUMENT.withDescription(invalid.getMessage()).asRuntimeException();
        }

        // ERROR here is right: this one IS unexpected and someone should look at it.
        log.error("gRPC ingest failed", failure);

        // Deliberately generic. The exception message may name internal hosts, streams or
        // topics, and an error response is attacker-visible. Same reasoning as the REST
        // handler's 500.
        return Status.INTERNAL
                .withDescription("the request could not be processed")
                .asRuntimeException();
    }

    private StatusRuntimeException withRetryInfo(Status status, long retryAfterMillis) {
        Metadata trailers = new Metadata();
        trailers.put(
                RETRY_INFO_KEY,
                RetryInfo.newBuilder()
                        .setRetryDelay(Duration.newBuilder()
                                .setSeconds(retryAfterMillis / 1000)
                                // protobuf Duration is seconds + nanos, and the nanos field
                                // must carry the sub-second remainder or a 250ms wait is
                                // reported as 0.
                                .setNanos((int) (retryAfterMillis % 1000) * 1_000_000)
                                .build())
                        .build());
        return status.asRuntimeException(trailers);
    }
}
