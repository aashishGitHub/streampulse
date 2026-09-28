/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streampulse.contracts.grpc.v1.Ack;
import com.streampulse.contracts.grpc.v1.Batch;
import com.streampulse.contracts.grpc.v1.BatchAck;
import com.streampulse.contracts.grpc.v1.TelemetryEvent;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import com.streampulse.ingest.support.TestJwtSupport;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The gRPC adapter against a real in-process server (BS-09 acceptance: {@code GrpcIngestIT}).
 *
 * <h2>What these tests are for</h2>
 *
 * That the adapter is a <b>front end</b> and nothing more: the tenant comes from the token,
 * the message shape reaches the use case unchanged, and the streaming protocol's promises
 * (periodic ACKs, the last durable sequence, flow control) hold.
 *
 * <p>They deliberately do <em>not</em> re-test idempotency, quotas or backpressure logic.
 * Those belong to {@code IngestServiceTest}, which exercises them with no protocol at all —
 * and the fact that they need testing exactly once is the property this whole design is for.
 */
class GrpcIngestInProcessTest {

    /** Records what the adapter handed the use case, so tests can assert on the mapping. */
    private static final class RecordingUseCase implements IngestUseCase {
        final List<IngestCommand> received = new CopyOnWriteArrayList<>();

        @Override
        public IngestResult ingest(IngestCommand command) {
            received.add(command);
            return IngestResult.of(command.size(), 0);
        }
    }

    private final RecordingUseCase useCase = new RecordingUseCase();
    private InProcessGrpcFixture grpc;

    @AfterEach
    void tearDown() {
        if (grpc != null) {
            grpc.close();
        }
    }

    private void startWithFlushSize(int flushSize) {
        grpc = new InProcessGrpcFixture(useCase, flushSize);
    }

    // ===================================================================
    //  UNARY
    // ===================================================================

    @Nested
    @DisplayName("SendBatch (unary)")
    class SendBatchTests {

        @Test
        @DisplayName("a batch reaches the use case and the ack reports what was published")
        void unaryBatchIsAccepted() {
            startWithFlushSize(50);

            BatchAck ack = grpc.blockingStubFor("streamflix")
                    .sendBatch(Batch.newBuilder()
                            .addEvents(InProcessGrpcFixture.event(1))
                            .addEvents(InProcessGrpcFixture.event(2))
                            .build());

            assertThat(ack.getAccepted()).isEqualTo(2);
            assertThat(ack.getDuplicates()).isZero();
            assertThat(useCase.received).hasSize(1);
            assertThat(useCase.received.get(0).size()).isEqualTo(2);
        }

        @Test
        @DisplayName("every field of the protobuf event survives the mapping to the domain")
        void protobufIsMappedFaithfully() {
            startWithFlushSize(50);
            String eventId = UUID.randomUUID().toString();

            grpc.blockingStubFor("streamflix")
                    .sendBatch(Batch.newBuilder()
                            .addEvents(InProcessGrpcFixture.event(eventId, "sess-9", 1))
                            .build());

            IngestCommand.IngestEvent mapped = useCase.received.get(0).events().get(0);

            assertThat(mapped.eventId()).hasToString(eventId);
            assertThat(mapped.sessionId()).isEqualTo("sess-9");
            assertThat(mapped.titleId()).isEqualTo("title-42");
            assertThat(mapped.deviceType()).isEqualTo("SMART_TV");
            assertThat(mapped.region()).isEqualTo("us-east-1");
            assertThat(mapped.type()).isEqualTo("HEARTBEAT");
            // The well-known Timestamp really does round-trip to the same instant, rather than
            // to the epoch that an unset protobuf field would silently produce.
            assertThat(mapped.occurredAt()).isEqualTo(java.time.Instant.parse("2026-01-15T12:00:00Z"));
            // sessionId is the partition key, and that decision has to survive the new protocol
            // or events for one session stop being ordered.
            assertThat(mapped.partitionKey()).isEqualTo("sess-9");
        }

        @Test
        @DisplayName("an empty batch is INVALID_ARGUMENT, not an empty success")
        void emptyBatchIsRejected() {
            startWithFlushSize(50);

            assertThatThrownBy(() -> grpc.blockingStubFor("streamflix").sendBatch(Batch.getDefaultInstance()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT);

            assertThat(useCase.received).isEmpty();
        }
    }

    // ===================================================================
    //  AUTH — the multi-tenancy boundary (REQ-GR-05, REQ-IG-08)
    // ===================================================================

    @Nested
    @DisplayName("authentication")
    class AuthTests {

        @Test
        @DisplayName("no token is UNAUTHENTICATED — the gRPC equivalent of 401")
        void missingTokenIsRejected() {
            startWithFlushSize(50);

            assertThatThrownBy(() -> grpc.anonymousBlockingStub()
                            .sendBatch(Batch.newBuilder()
                                    .addEvents(InProcessGrpcFixture.event(1))
                                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED);

            // The important half of the assertion: rejected BEFORE any work was done.
            assertThat(useCase.received).isEmpty();
        }

        @Test
        @DisplayName("an EXPIRED token is UNAUTHENTICATED — expiry is really checked")
        void expiredTokenIsRejected() {
            // Worth asserting explicitly: an interceptor that parsed the token instead of
            // decoding it would look completely healthy until somebody replayed an old one.
            startWithFlushSize(50);

            assertThatThrownBy(() -> grpc.blockingStubWithToken(TestJwtSupport.expiredTokenFor("streamflix"))
                            .sendBatch(Batch.newBuilder()
                                    .addEvents(InProcessGrpcFixture.event(1))
                                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("a valid token with NO tenant claim fails closed")
        void tokenWithoutTenantIsRejected() {
            // Signature valid, issuer fine, not expired — but unattributable. Guessing a
            // default tenant here would write one customer's telemetry into another's stream.
            startWithFlushSize(50);

            assertThatThrownBy(() -> grpc.blockingStubWithToken(TestJwtSupport.tokenWithoutTenant())
                            .sendBatch(Batch.newBuilder()
                                    .addEvents(InProcessGrpcFixture.event(1))
                                    .build()))
                    .isInstanceOf(StatusRuntimeException.class)
                    .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED);

            assertThat(useCase.received).isEmpty();
        }

        @Test
        @DisplayName("the tenant comes from the TOKEN — the message has no field to override it")
        void tenantComesFromTheToken() {
            startWithFlushSize(50);

            grpc.blockingStubFor("sportsnow")
                    .sendBatch(Batch.newBuilder()
                            .addEvents(InProcessGrpcFixture.event(1))
                            .build());

            // This is structural rather than a check: TelemetryEvent has no tenant field at
            // all, so the REST test's "the body cannot override the tenant" has no gRPC
            // equivalent to write. There is nothing to override.
            assertThat(useCase.received.get(0).tenantId()).hasToString("sportsnow");
        }
    }

    // ===================================================================
    //  BIDIRECTIONAL STREAMING (REQ-GR-01, REQ-GR-04)
    // ===================================================================

    @Nested
    @DisplayName("StreamEvents (bidirectional)")
    class StreamTests {

        @Test
        @DisplayName("events are batched to the flush size, and each batch gets one ACK")
        void streamAcksEveryFlush() throws Exception {
            startWithFlushSize(3);

            var acks = new CopyOnWriteArrayList<Ack>();
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();

            StreamObserver<TelemetryEvent> request =
                    grpc.asyncStubFor("streamflix").streamEvents(observer(acks, error, done));

            // Nine events at a flush size of three: three calls into the use case, three ACKs.
            // The client sends one at a time, exactly as a TV SDK emitting heartbeats would.
            for (long seq = 1; seq <= 9; seq++) {
                request.onNext(InProcessGrpcFixture.event(seq));
            }
            request.onCompleted();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(error.get()).isNull();

            assertThat(acks).hasSize(3);
            assertThat(useCase.received).hasSize(3);
            assertThat(useCase.received)
                    .allSatisfy(cmd -> assertThat(cmd.size()).isEqualTo(3));
        }

        @Test
        @DisplayName("each ACK reports the last DURABLE sequence, so the client knows what to drop")
        void ackCarriesTheLastDurableSequence() throws Exception {
            startWithFlushSize(2);

            var acks = new CopyOnWriteArrayList<Ack>();
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();

            StreamObserver<TelemetryEvent> request =
                    grpc.asyncStubFor("streamflix").streamEvents(observer(acks, error, done));

            for (long seq = 1; seq <= 4; seq++) {
                request.onNext(InProcessGrpcFixture.event(seq));
            }
            request.onCompleted();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            // The whole contract of a streaming ingest: the client may free everything up to
            // this number, and NOT because the server received it — because ingest() returned,
            // which only happens after the broker acknowledged.
            assertThat(acks).extracting(Ack::getLastDurableSequence).containsExactly(2L, 4L);
            assertThat(acks).allSatisfy(ack -> assertThat(ack.getAccepted()).isEqualTo(2));
        }

        @Test
        @DisplayName("a partial final batch is still flushed when the client half-closes")
        void remainderIsFlushedOnHalfClose() throws Exception {
            startWithFlushSize(5);

            var acks = new CopyOnWriteArrayList<Ack>();
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();

            StreamObserver<TelemetryEvent> request =
                    grpc.asyncStubFor("streamflix").streamEvents(observer(acks, error, done));

            // Two events, flush size five. Without the half-close flush these would sit in the
            // buffer forever and the client would wait for an ACK that never comes — a hang
            // rather than an error, which is the worst kind of bug to diagnose in production.
            request.onNext(InProcessGrpcFixture.event(1));
            request.onNext(InProcessGrpcFixture.event(2));
            request.onCompleted();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(acks).hasSize(1);
            assertThat(acks.get(0).getAccepted()).isEqualTo(2);
            assertThat(acks.get(0).getLastDurableSequence()).isEqualTo(2L);
        }

        @Test
        @DisplayName("a failing batch closes the stream with the mapped status, not with UNKNOWN")
        void failureClosesTheStreamWithAStatus() throws Exception {
            var failing = new IngestUseCase() {
                @Override
                public IngestResult ingest(IngestCommand command) {
                    throw new com.streampulse.ingest.domain.QuotaExceededException(
                            "over quota", java.time.Duration.ofSeconds(2));
                }
            };
            grpc = new InProcessGrpcFixture(failing, 1);

            var acks = new CopyOnWriteArrayList<Ack>();
            var done = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();

            StreamObserver<TelemetryEvent> request =
                    grpc.asyncStubFor("streamflix").streamEvents(observer(acks, error, done));
            request.onNext(InProcessGrpcFixture.event(1));

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            // An exception escaping the handler would close the call as UNKNOWN with no
            // description, which tells the client nothing and is indistinguishable from a
            // transport bug. The status mapper is what makes this actionable.
            assertThat(error.get()).isInstanceOf(StatusRuntimeException.class);
            assertThat(((StatusRuntimeException) error.get()).getStatus().getCode())
                    .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
            assertThat(acks).isEmpty();
        }

        private StreamObserver<Ack> observer(List<Ack> acks, AtomicReference<Throwable> error, CountDownLatch done) {
            return new StreamObserver<>() {
                @Override
                public void onNext(Ack ack) {
                    acks.add(ack);
                }

                @Override
                public void onError(Throwable t) {
                    error.set(t);
                    done.countDown();
                }

                @Override
                public void onCompleted() {
                    done.countDown();
                }
            };
        }
    }
}
