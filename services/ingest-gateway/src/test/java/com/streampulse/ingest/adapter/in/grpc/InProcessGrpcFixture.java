/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import com.google.protobuf.Timestamp;
import com.streampulse.contracts.grpc.v1.TelemetryEvent;
import com.streampulse.contracts.grpc.v1.TelemetryIngestGrpc;
import com.streampulse.ingest.adapter.in.TenantResolver;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.support.TestJwtSupport;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A real gRPC server with no sockets, for the tests that are about the protocol.
 *
 * <h2>Why in-process rather than a port, or a mock</h2>
 *
 * <pre>
 *   mock the stub          fast, proves nothing — no interceptor, no status codes,
 *                          no flow control, no serialization
 *   in-process transport   ← here. Real interceptors, real Context propagation, real
 *                          deadlines, real Status on the wire. No port to collide with,
 *                          no TCP, milliseconds.
 *   real port (Netty)      everything above plus the transport itself. Worth it once —
 *                          ProtocolParityTest does exactly that — and wasteful per test.
 * </pre>
 *
 * The middle row is the sweet spot for "does the adapter behave", and it is what makes
 * asserting on {@code Status.RESOURCE_EXHAUSTED} meaningful rather than tautological: the
 * status really is produced by the mapper, carried by gRPC, and read back by a generated stub.
 *
 * <h2>What is genuinely production here</h2>
 *
 * The service, the status mapper, {@link JwtAuthInterceptor}, {@link TenantResolver} and the
 * JWT decoder are all the real classes. Only the {@link IngestUseCase} is a stand-in, because
 * these tests are about the adapter and not about idempotency — which
 * {@code IngestServiceTest} already covers exhaustively without any protocol at all.
 */
final class InProcessGrpcFixture implements AutoCloseable {

    private final Server server;
    private final ManagedChannel channel;

    InProcessGrpcFixture(IngestUseCase useCase, int streamFlushSize) {
        var service =
                new TelemetryIngestGrpcService(useCase, new GrpcStatusMapper(), Clock.systemUTC(), streamFlushSize);
        var interceptor = new JwtAuthInterceptor(TestJwtSupport.decoder(), new TenantResolver());

        // A unique name per fixture: several test classes run in one JVM and the in-process
        // transport's registry is global, so a fixed name would have them serving each other.
        String name = InProcessServerBuilder.generateName();

        try {
            server = InProcessServerBuilder.forName(name)
                    // NOT directExecutor(), deliberately. It is tempting — it makes streaming
                    // assertions deterministic and puts handler failures on the test's own
                    // stack. But it runs the handler on the CALLER's thread, so a test whose
                    // handler blocks (the deadline tests) would block the very thread waiting
                    // for the response, and the test would deadlock rather than fail.
                    //
                    // Every test here synchronises with latches instead, which is both safe
                    // and closer to how the production server behaves.
                    //
                    // The SAME intercepted definition the production server binds — see
                    // GrpcConfig. Assembling a different one here would let a production
                    // server bound without auth pass a green suite.
                    .addService(ServerInterceptors.intercept(service, interceptor))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start the in-process gRPC server", e);
        }

        channel = InProcessChannelBuilder.forName(name).build();
    }

    /** A blocking stub carrying a valid token for {@code tenant}. */
    TelemetryIngestGrpc.TelemetryIngestBlockingStub blockingStubFor(String tenant) {
        return blockingStubWithToken(TestJwtSupport.tokenFor(tenant));
    }

    TelemetryIngestGrpc.TelemetryIngestBlockingStub blockingStubWithToken(String token) {
        return TelemetryIngestGrpc.newBlockingStub(channel).withInterceptors(authInterceptor(token));
    }

    /** An async stub, which is the only kind that can drive a bidirectional stream. */
    TelemetryIngestGrpc.TelemetryIngestStub asyncStubFor(String tenant) {
        return TelemetryIngestGrpc.newStub(channel).withInterceptors(authInterceptor(TestJwtSupport.tokenFor(tenant)));
    }

    /** No credentials at all, for the unauthenticated case. */
    TelemetryIngestGrpc.TelemetryIngestBlockingStub anonymousBlockingStub() {
        return TelemetryIngestGrpc.newBlockingStub(channel);
    }

    private static io.grpc.ClientInterceptor authInterceptor(String token) {
        Metadata metadata = new Metadata();
        // The client side of what JwtAuthInterceptor reads. Note it is metadata, not a field
        // of the message — the tenant can only ever come from a signed token.
        metadata.put(JwtAuthInterceptor.AUTHORIZATION, "Bearer " + token);
        return MetadataUtils.newAttachHeadersInterceptor(metadata);
    }

    /** A well-formed event. Random ids, so repeated runs are never deduplicated against. */
    static TelemetryEvent event(long sequence) {
        return event(UUID.randomUUID().toString(), "sess-" + UUID.randomUUID(), sequence);
    }

    static TelemetryEvent event(String eventId, String sessionId, long sequence) {
        Instant occurredAt = Instant.parse("2026-01-15T12:00:00Z");
        return TelemetryEvent.newBuilder()
                .setEventId(eventId)
                .setSessionId(sessionId)
                .setTitleId("title-42")
                .setDeviceType("SMART_TV")
                .setRegion("us-east-1")
                .setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(occurredAt.getEpochSecond())
                        .setNanos(occurredAt.getNano())
                        .build())
                .setType("HEARTBEAT")
                .setSequence(sequence)
                .build();
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
        try {
            // Bounded: a test that leaks a server would otherwise hang the whole build rather
            // than failing, which is much harder to diagnose than a failed assertion.
            channel.awaitTermination(5, TimeUnit.SECONDS);
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
