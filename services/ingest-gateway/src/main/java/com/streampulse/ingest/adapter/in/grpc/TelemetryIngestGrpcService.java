/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import com.streampulse.contracts.grpc.v1.Ack;
import com.streampulse.contracts.grpc.v1.Batch;
import com.streampulse.contracts.grpc.v1.BatchAck;
import com.streampulse.contracts.grpc.v1.TelemetryEvent;
import com.streampulse.contracts.grpc.v1.TelemetryIngestGrpc;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import com.streampulse.platform.domain.EventId;
import com.streampulse.platform.domain.TenantId;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gRPC front door — the second inbound adapter over the <b>same</b> use case (REQ-GR-02).
 *
 * <h2>Why this class is the payoff of the hexagon</h2>
 *
 * It contains no validation, no idempotency, no quota check and no broker. It maps protobuf
 * to the domain, calls {@link IngestUseCase}, and maps the result back — exactly the three
 * things {@code IngestController} does with JSON. Everything that decides correctness happens
 * in one place that neither adapter can reach around.
 *
 * <pre>
 *   POST /v1/events:batch ──▶ IngestController ──┐
 *                                                ├──▶ IngestUseCase ──▶ EventPublisher ──▶ stream
 *   TelemetryIngest/SendBatch ──▶ this class ────┘        (quota, dedup, backpressure)
 * </pre>
 *
 * {@code ProtocolParityTest} is what keeps that claim honest: it sends the same event down
 * both paths and asserts the records on the stream are identical field for field. The moment
 * a rule is implemented here instead of in the use case, that test goes red.
 *
 * <h2>Two RPCs, two shapes of the same work</h2>
 *
 * <ul>
 *   <li>{@code SendBatch} — unary. One request, one response. The direct analogue of the REST
 *       endpoint, and the one the parity test uses precisely because it has no extra moving
 *       parts to explain a difference away.
 *   <li>{@code StreamEvents} — bidirectional. The long-lived connection a TV SDK holds open:
 *       events flow up continuously, ACKs flow down periodically carrying the last sequence
 *       that is durable, so the client knows what it may drop.
 * </ul>
 */
public class TelemetryIngestGrpcService extends TelemetryIngestGrpc.TelemetryIngestImplBase {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestGrpcService.class);

    private final IngestUseCase ingest;
    private final GrpcStatusMapper statusMapper;
    private final Clock clock;

    /**
     * How many streamed events are gathered before one call into the use case.
     *
     * <p>It is simultaneously the ACK interval and the flow-control window — deliberately, so
     * they cannot disagree. See {@link EventStreamHandler}.
     */
    private final int streamFlushSize;

    public TelemetryIngestGrpcService(
            IngestUseCase ingest, GrpcStatusMapper statusMapper, Clock clock, int streamFlushSize) {
        this.ingest = ingest;
        this.statusMapper = statusMapper;
        this.clock = clock;
        this.streamFlushSize = streamFlushSize;
    }

    // =======================================================================
    //  UNARY
    // =======================================================================

    @Override
    public void sendBatch(Batch request, StreamObserver<BatchAck> responseObserver) {
        try {
            TenantId tenant = GrpcTenantContext.requireTenant();

            // REQ-GR-03. Cheap, and it matters most exactly when the system is slow: under
            // load a call can sit in the server's queue past its own deadline, and starting
            // work for a client that has already given up spends a permit, a Redis round trip
            // and a publish on a response nobody will read.
            if (Context.current().isCancelled()) {
                responseObserver.onError(Status.CANCELLED
                        .withDescription("client cancelled or the deadline expired before work began")
                        .asRuntimeException());
                return;
            }

            IngestResult result = ingest.ingest(toCommand(tenant, request.getEventsList()));

            responseObserver.onNext(BatchAck.newBuilder()
                    .setAccepted(result.published())
                    .setDuplicates(result.duplicates())
                    .setDegraded(result.idempotencyDegraded())
                    .build());
            responseObserver.onCompleted();

        } catch (RuntimeException e) {
            // onError, never rethrow. An exception escaping a gRPC handler is closed as
            // UNKNOWN with no description — the client is told something went wrong and
            // nothing else, which is indistinguishable from a bug in the transport.
            responseObserver.onError(statusMapper.toStatusException(e));
        }
    }

    // =======================================================================
    //  BIDIRECTIONAL STREAM
    // =======================================================================

    @Override
    public StreamObserver<TelemetryEvent> streamEvents(StreamObserver<Ack> responseObserver) {
        // The cast is safe for every streaming call: gRPC's ServerCalls always passes a
        // ServerCallStreamObserver here. It is how the server reaches the flow-control and
        // cancellation controls that the plain StreamObserver interface does not expose.
        var response = (ServerCallStreamObserver<Ack>) responseObserver;

        // ---- MANUAL INBOUND FLOW CONTROL (REQ-GR-04) --------------------
        // By default gRPC requests one more message every time the handler returns, so a fast
        // client streams as fast as the network allows and the server buffers whatever it
        // cannot process. That is the same unbounded-queue failure the REST path refuses: it
        // does not prevent overload, it relocates it to the heap.
        //
        // disableAutoRequest() hands the credit decision to us. From here nothing arrives
        // until we ask, and we only ask once the previous window is DURABLE:
        //
        //     request(N) ─▶ N events arrive ─▶ ingest() ─▶ broker ack ─▶ Ack ─▶ request(N)
        //                                          │
        //                                          └─ blocked on the in-flight semaphore?
        //                                             then no credit is granted, and the
        //                                             backpressure reaches the CLIENT as a
        //                                             stalled HTTP/2 flow-control window —
        //                                             the same bound REST expresses as a 503.
        //
        // That is backpressure parity: one semaphore, two protocols, expressed in whichever
        // vocabulary each protocol has.
        response.disableAutoRequest();

        TenantId tenant = GrpcTenantContext.requireTenant();
        var handler = new EventStreamHandler(tenant, response);

        // Fires when the CLIENT goes away (cancel, deadline, broken connection). Without it
        // the server keeps publishing batches for a stream nobody is listening to.
        response.setOnCancelHandler(handler::abandon);

        response.request(streamFlushSize);
        return handler;
    }

    /**
     * One live stream.
     *
     * <h2>Why a plain ArrayList is safe here</h2>
     *
     * gRPC guarantees the callbacks of a single call are delivered <b>serially</b> — never two
     * at once, and with a happens-before edge between them. So this is single-threaded state
     * despite living on a server handling thousands of streams. Reaching for a concurrent
     * collection would imply a guarantee we do not have and hide the one we do.
     */
    private final class EventStreamHandler implements StreamObserver<TelemetryEvent> {

        private final TenantId tenant;
        private final ServerCallStreamObserver<Ack> response;
        private final List<TelemetryEvent> buffered = new ArrayList<>();

        /**
         * The client's own counter for the newest buffered event.
         *
         * <p>Taken as "the last one seen" rather than a running maximum, because HTTP/2
         * delivers a single stream's frames in order and the contract says the counter is
         * monotonic. If either were untrue, an ACK would promise durability for events that
         * had not been published.
         */
        private long lastBufferedSequence;

        /** Set once the call has been closed, so no callback closes it a second time. */
        private boolean closed;

        EventStreamHandler(TenantId tenant, ServerCallStreamObserver<Ack> response) {
            this.tenant = tenant;
            this.response = response;
        }

        @Override
        public void onNext(TelemetryEvent event) {
            if (closed) {
                return;
            }
            buffered.add(event);
            lastBufferedSequence = event.getSequence();

            if (buffered.size() >= streamFlushSize) {
                flush();
            }
        }

        /** The client half-closed: it has sent everything and is waiting for the last ACKs. */
        @Override
        public void onCompleted() {
            if (closed) {
                return;
            }
            if (!buffered.isEmpty()) {
                flush();
            }
            if (!closed) {
                response.onCompleted();
                closed = true;
            }
        }

        /**
         * The CLIENT failed or cancelled. gRPC has already torn the call down, so there is
         * nothing to close and calling onError here would throw.
         *
         * <p>Discarding the buffer is the correct choice, not a shortcut: those events were
         * never acknowledged, so the client still owns them and will resend. Publishing them
         * anyway would be doing work whose outcome nobody can learn.
         */
        @Override
        public void onError(Throwable t) {
            log.debug("gRPC stream ended by the client: {}", t.toString());
            abandon();
        }

        void abandon() {
            closed = true;
            buffered.clear();
        }

        private void flush() {
            // Two different questions, both worth asking. isCancelled() is the transport's
            // view ("this call is dead"); Context.isCancelled() also covers the deadline
            // having passed on a call the transport has not torn down yet.
            if (response.isCancelled() || Context.current().isCancelled()) {
                abandon();
                return;
            }

            try {
                IngestResult result = ingest.ingest(toCommand(tenant, buffered));

                // The ACK is sent only AFTER ingest() returns, and ingest() only returns once
                // the broker has acknowledged. That ordering is the entire promise of
                // last_durable_sequence: the client drops its copy on the strength of it.
                response.onNext(Ack.newBuilder()
                        .setLastDurableSequence(lastBufferedSequence)
                        .setAccepted(result.published())
                        .setDuplicates(result.duplicates())
                        .setDegraded(result.idempotencyDegraded())
                        .build());

                buffered.clear();

                // Only now is the next window granted. Moving this line above ingest() would
                // turn the whole mechanism back into "read as fast as the client can send".
                response.request(streamFlushSize);

            } catch (RuntimeException e) {
                closed = true;
                buffered.clear();
                response.onError(statusMapper.toStatusException(e));
            }
        }
    }

    // =======================================================================
    //  WIRE → DOMAIN
    // =======================================================================

    /**
     * Maps protobuf to the domain type the use case consumes.
     *
     * <p>This is the whole of the adapter's "logic", and it is deliberately dumb. Note that
     * the tenant is a parameter and not a field of the message — see {@link GrpcTenantContext}
     * and REQ-IG-08.
     */
    private IngestCommand toCommand(TenantId tenant, List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            // IngestCommand would reject this with IllegalArgumentException anyway. Doing it
            // here with a message that names the RPC is kinder to the client, and matches
            // what Bean Validation reports on the REST side.
            throw Status.INVALID_ARGUMENT
                    .withDescription("a batch must contain at least one event")
                    .asRuntimeException();
        }

        List<IngestCommand.IngestEvent> mapped = events.stream()
                .map(e -> new IngestCommand.IngestEvent(
                        // EventId validates the UUID at the boundary, exactly as the REST
                        // adapter does, so everything downstream can assume it is well-formed.
                        // A malformed one becomes INVALID_ARGUMENT via the status mapper.
                        EventId.of(e.getEventId()),
                        e.getSessionId(),
                        e.getTitleId(),
                        e.getDeviceType(),
                        e.getRegion(),
                        toInstant(e),
                        e.getType(),
                        null)) // payload variants land in Phase 3, with the consumer
                .toList();

        return new IngestCommand(tenant, mapped, clock.instant());
    }

    /**
     * protobuf {@code Timestamp} → {@code java.time.Instant}.
     *
     * <p>proto3 has no null: an absent {@code occurred_at} reads back as the default instance,
     * which is the epoch. Publishing 1970 as an event time would quietly corrupt every
     * downstream aggregation, so an unset timestamp is rejected as the client error it is.
     */
    private Instant toInstant(TelemetryEvent event) {
        if (!event.hasOccurredAt()) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        var ts = event.getOccurredAt();
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }
}
