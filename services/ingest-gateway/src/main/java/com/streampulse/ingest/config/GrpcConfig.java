/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import com.streampulse.ingest.adapter.in.TenantResolver;
import com.streampulse.ingest.adapter.in.grpc.GrpcStatusMapper;
import com.streampulse.ingest.adapter.in.grpc.JwtAuthInterceptor;
import com.streampulse.ingest.adapter.in.grpc.TelemetryIngestGrpcService;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.lifecycle.GrpcServerLifecycle;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Composition root for the gRPC adapter (BS-09).
 *
 * <p>Separate from {@code IngestConfig} because it wires one protocol, and a reader asking
 * "how is gRPC set up?" should find the answer in a file with that name rather than halfway
 * down a hundred lines about circuit breakers.
 *
 * <h2>The one bean worth reading twice</h2>
 *
 * {@link #telemetryIngestServiceDefinition} is the <b>intercepted</b> service: the handler
 * with authentication already wrapped around it. Both the production server and the
 * in-process test server bind <em>that</em> object.
 *
 * <p>That is deliberate, and it is a testing decision rather than a wiring one. If the test
 * assembled its own {@code ServerInterceptors.intercept(service, interceptor)}, it would be
 * asserting against an arrangement it built itself — and a production server accidentally
 * bound without the auth interceptor would sail past a green suite. Sharing the definition
 * means the tests exercise the real one.
 */
@Configuration
class GrpcConfig {

    @Bean
    GrpcStatusMapper grpcStatusMapper() {
        return new GrpcStatusMapper();
    }

    /**
     * The same {@code JwtDecoder} the REST filter chain uses.
     *
     * <p>In production it is auto-configured from
     * {@code spring.security.oauth2.resourceserver.jwt.*}; in tests it is the symmetric-key
     * decoder from {@code TestJwtSupport}. Either way there is exactly one, so a token that
     * REST rejects cannot be accepted over gRPC.
     */
    @Bean
    JwtAuthInterceptor jwtAuthInterceptor(JwtDecoder jwtDecoder, TenantResolver tenantResolver) {
        return new JwtAuthInterceptor(jwtDecoder, tenantResolver);
    }

    @Bean
    TelemetryIngestGrpcService telemetryIngestGrpcService(
            IngestUseCase ingest, GrpcStatusMapper statusMapper, GrpcProperties props) {
        // Clock.systemUTC() rather than Instant.now() inside the service: CLAUDE.md's rule,
        // and it is what would let a future test assert on receivedAt without sleeping.
        return new TelemetryIngestGrpcService(ingest, statusMapper, Clock.systemUTC(), props.streamFlushSize());
    }

    /**
     * The service with its interceptors applied.
     *
     * <p>{@code intercept} rather than {@code interceptForward}: with several interceptors the
     * two differ in ordering, and {@code intercept} applies them in reverse of the argument
     * order. With one it makes no difference — but choosing the one whose ordering you can
     * state is how the next person adding a tracing interceptor gets it right.
     */
    @Bean
    ServerServiceDefinition telemetryIngestServiceDefinition(
            TelemetryIngestGrpcService service, JwtAuthInterceptor authInterceptor) {
        return ServerInterceptors.intercept(service, authInterceptor);
    }

    /**
     * The server itself, as a lifecycle bean.
     *
     * <p>Note that no {@code io.grpc.Server} bean is exposed. Spring infers a destroy method
     * for beans that have {@code close()} or {@code shutdown()}, and {@code Server} has
     * {@code shutdown()} — so a {@code Server} bean would be shut down by the container at
     * context-close time, racing the ordered drain this class exists to take part in. Keeping
     * the server inside the lifecycle leaves exactly one thing in charge of stopping it.
     */
    @Bean
    GrpcServerLifecycle grpcServerLifecycle(ServerServiceDefinition definition, GrpcProperties props) {
        return new GrpcServerLifecycle(definition, props.port(), props.shutdownTimeout());
    }
}
