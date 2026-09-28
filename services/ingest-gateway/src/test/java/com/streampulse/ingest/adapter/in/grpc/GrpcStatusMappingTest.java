/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.google.rpc.RetryInfo;
import com.streampulse.contracts.grpc.v1.Batch;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.BackpressureException;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import com.streampulse.ingest.domain.PublishUnavailableException;
import com.streampulse.ingest.domain.QuotaExceededException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Domain failure → gRPC status, asserted across a real call (REQ-GR-03).
 *
 * <h2>Why this goes through a server rather than calling the mapper directly</h2>
 *
 * A unit test of {@code GrpcStatusMapper} would prove it returns the right object. This proves
 * the status and its trailers survive being <b>serialized, sent and parsed back</b> by a
 * generated stub — which is the part that actually breaks. A hand-written trailer key with the
 * wrong name, for instance, compiles, unit-tests green, and arrives at the client as nothing.
 *
 * <h2>The mapping under test</h2>
 *
 * <pre>
 *   QuotaExceededException      → RESOURCE_EXHAUSTED  "your quota"  retry elsewhere: no
 *   BackpressureException       → UNAVAILABLE         "our capacity" retry elsewhere: yes
 *   PublishUnavailableException → UNAVAILABLE         dependency    retry later: yes
 *   IllegalArgumentException    → INVALID_ARGUMENT    your input    retry at all: no
 *   anything else               → INTERNAL            our bug       retry at all: no
 * </pre>
 *
 * The 429-vs-503 distinction the REST handler makes is the same distinction here, in the other
 * protocol's vocabulary. A client that cannot tell them apart either hammers a global quota it
 * cannot beat, or gives up on a retryable failure.
 */
class GrpcStatusMappingTest {

    private InProcessGrpcFixture grpc;

    @AfterEach
    void tearDown() {
        if (grpc != null) {
            grpc.close();
        }
    }

    /** A use case that does nothing but fail in a chosen way. */
    private void useCaseThatThrows(RuntimeException failure) {
        grpc = new InProcessGrpcFixture(
                new IngestUseCase() {
                    @Override
                    public IngestResult ingest(IngestCommand command) {
                        throw failure;
                    }
                },
                50);
    }

    private StatusRuntimeException callAndCaptureFailure() {
        return catchThrowableOfType(StatusRuntimeException.class, () -> grpc.blockingStubFor("streamflix")
                .sendBatch(Batch.newBuilder()
                        .addEvents(InProcessGrpcFixture.event(1))
                        .build()));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("quota exhausted → RESOURCE_EXHAUSTED, with RetryInfo in the trailers")
    void quotaMapsToResourceExhausted() {
        useCaseThatThrows(new QuotaExceededException("tenant over quota", Duration.ofSeconds(1)));

        StatusRuntimeException failure = callAndCaptureFailure();

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);

        // google.rpc.RetryInfo is the STANDARD error detail — a Go or Kotlin client reads it
        // with no StreamPulse-specific code, exactly as an HTTP client reads Retry-After.
        Metadata trailers = failure.getTrailers();
        assertThat(trailers).isNotNull();
        RetryInfo retryInfo = trailers.get(GrpcStatusMapper.RETRY_INFO_KEY);
        assertThat(retryInfo).as("a 'come back later' status must say WHEN").isNotNull();
        assertThat(retryInfo.getRetryDelay().getSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("sub-second retry advice keeps its milliseconds")
    void retryInfoCarriesSubSecondPrecision() {
        // The nanos half of a protobuf Duration is easy to forget, and forgetting it turns
        // "come back in 250ms" into "come back now" — which is how a rejected client turns
        // into a hot loop against a saturated gateway.
        useCaseThatThrows(new BackpressureException("saturated", Duration.ofMillis(250)));

        RetryInfo retryInfo = callAndCaptureFailure().getTrailers().get(GrpcStatusMapper.RETRY_INFO_KEY);

        assertThat(retryInfo.getRetryDelay().getSeconds()).isZero();
        assertThat(retryInfo.getRetryDelay().getNanos()).isEqualTo(250_000_000);
    }

    @Test
    @DisplayName("gateway saturation → UNAVAILABLE, because another instance may well cope")
    void backpressureMapsToUnavailable() {
        useCaseThatThrows(new BackpressureException("no capacity", Duration.ofMillis(250)));

        assertThat(callAndCaptureFailure().getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("the event stream being unreachable → UNAVAILABLE, never INTERNAL")
    void publishUnavailableMapsToUnavailable() {
        // The distinction that matters most in this file. gRPC clients, service configs and
        // service meshes all treat UNAVAILABLE as retryable and INTERNAL as fatal, so mapping
        // a temporarily unreachable broker to INTERNAL tells the whole fleet to give up on
        // data that would have succeeded a second later.
        useCaseThatThrows(new PublishUnavailableException("breaker open", Duration.ofSeconds(30), true, null));

        StatusRuntimeException failure = callAndCaptureFailure();

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(failure.getTrailers()
                        .get(GrpcStatusMapper.RETRY_INFO_KEY)
                        .getRetryDelay()
                        .getSeconds())
                .as("the advice is the breaker's own remaining open window, so the two cannot drift")
                .isEqualTo(30);
    }

    @Test
    @DisplayName("a malformed event id → INVALID_ARGUMENT, rejected at the boundary")
    void malformedEventIdMapsToInvalidArgument() {
        // Never reaches the use case: EventId.of rejects it while the adapter is still mapping,
        // which is the same place the REST adapter rejects it.
        grpc = new InProcessGrpcFixture(command -> IngestResult.of(command.size(), 0), 50);

        StatusRuntimeException failure =
                catchThrowableOfType(StatusRuntimeException.class, () -> grpc.blockingStubFor("streamflix")
                        .sendBatch(Batch.newBuilder()
                                .addEvents(InProcessGrpcFixture.event("not-a-uuid", "sess-1", 1))
                                .build()));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        // The description names the caller's own mistake — safe to return, and actionable.
        assertThat(failure.getStatus().getDescription()).contains("UUID");
    }

    @Test
    @DisplayName("an unexpected failure → INTERNAL, and the message is NOT leaked")
    void unexpectedFailureMapsToInternalWithoutDetail() {
        useCaseThatThrows(new IllegalStateException("redis-primary-3.internal:6379 refused the connection"));

        StatusRuntimeException failure = callAndCaptureFailure();

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        // An error response is attacker-visible, and that message names an internal host. The
        // full detail is in the log, where an operator can see it and a caller cannot.
        assertThat(failure.getStatus().getDescription()).doesNotContain("redis-primary-3.internal");
    }

    @Test
    @DisplayName("an event id that is valid still reaches the use case (the mapper is not swallowing)")
    void validCallStillSucceeds() {
        // A guard against the cheapest possible false positive: every test above would also
        // pass if the adapter failed every call for some unrelated reason.
        grpc = new InProcessGrpcFixture(command -> IngestResult.of(command.size(), 0), 50);

        var ack = grpc.blockingStubFor("streamflix")
                .sendBatch(Batch.newBuilder()
                        .addEvents(InProcessGrpcFixture.event(UUID.randomUUID().toString(), "sess-1", 1))
                        .build());

        assertThat(ack.getAccepted()).isEqualTo(1);
    }
}
