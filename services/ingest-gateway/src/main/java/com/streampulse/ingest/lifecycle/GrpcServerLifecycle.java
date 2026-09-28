/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.lifecycle;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Owns the gRPC server's whole life: build, start, drain, stop (BS-09 / REQ-LC-01).
 *
 * <h2>Why a hand-managed server rather than a Spring Boot gRPC starter</h2>
 *
 * The community starters do this competently, and ADR-0010 records the trade honestly. Two
 * reasons decided it here:
 *
 * <ol>
 *   <li><b>Shutdown ordering.</b> BS-01 exists to prove an ordered drain, and that argument
 *       only holds if the gRPC server is a participant in it rather than something that stops
 *       whenever its own auto-configuration happens to stop. This class puts it in the same
 *       phase ladder as everything else.
 *   <li><b>It is about fifty lines.</b> Taking a dependency whose maintenance status has to be
 *       researched, in exchange for fifty lines that teach what {@code SmartLifecycle} is for,
 *       is a bad trade in a repo whose purpose is understanding the mechanism.
 * </ol>
 *
 * <h2>The phase ladder on shutdown</h2>
 *
 * {@code SmartLifecycle} phases stop in <b>descending</b> order, so a higher phase means
 * "stop earlier". The numbers below are read from the Boot 3.5.3 jar, not remembered:
 *
 * <pre>
 *   phase 2147482623  in-flight HTTP requests finish   (WebServerGracefulShutdownLifecycle
 *                                                       .SMART_LIFECYCLE_PHASE)
 *   phase 2147481599  the servlet connector stops      (WebServerStartStopLifecycle)
 *   phase 200         THIS: the gRPC server stops accepting, then drains
 *   phase 100         IngestDrainLifecycle: readiness → REFUSING_TRAFFIC, wait for in-flight
 *   phase 0           everything else closes
 * </pre>
 *
 * <p>The ordering that matters is 200 before 100: no new gRPC call may arrive while the drain
 * is counting in-flight batches down to zero. Reverse them and the drain would watch a number
 * that keeps going back up, time out, and log a data-loss warning caused purely by its own
 * position in the list.
 *
 * <h2>Why {@code shutdown()} then {@code shutdownNow()}</h2>
 *
 * <pre>
 *   shutdown()      stop accepting new calls; let in-flight ones finish. Returns immediately.
 *   awaitTermination(t)  wait, bounded.
 *   shutdownNow()   cancel what is left.
 * </pre>
 *
 * Only calling {@code shutdown()} risks hanging past Kubernetes' grace period and being
 * SIGKILLed mid-publish — which undoes the whole point of a graceful drain. Only calling
 * {@code shutdownNow()} kills calls that were two milliseconds from finishing. The bound is
 * the answer, and it must stay comfortably under {@code terminationGracePeriodSeconds}.
 */
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    /** Higher than the drain's 100, so gRPC stops accepting BEFORE the drain starts counting. */
    private static final int PHASE = 200;

    private final ServerServiceDefinition serviceDefinition;
    private final int configuredPort;
    private final Duration shutdownTimeout;

    private volatile Server server;
    private volatile ExecutorService executor;

    public GrpcServerLifecycle(
            ServerServiceDefinition serviceDefinition, int configuredPort, Duration shutdownTimeout) {
        this.serviceDefinition = serviceDefinition;
        this.configuredPort = configuredPort;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isRunning() {
        Server current = server;
        return current != null && !current.isShutdown();
    }

    @Override
    public void start() {
        if (isRunning()) {
            return;
        }
        // ONE virtual thread per call, mirroring what Boot does for HTTP (see the
        // spring-service convention plugin). This workload blocks on Redis and the broker, so
        // a blocked handler unmounts from its carrier instead of holding an OS thread, and
        // concurrency is bounded by memory rather than by a pool size someone guessed.
        //
        // gRPC's own default is a cached platform-thread pool: fine, but it would mean the two
        // protocols answer "what happens when the broker is slow?" differently, and the whole
        // point of this adapter is that they do not.
        executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            // ServerBuilder.forPort finds a transport through the ServiceLoader —
            // grpc-netty-shaded is on the runtime classpath for exactly this and is never
            // imported by name. Port 0 asks the OS for a free port, which is what the
            // integration tests use so two test classes cannot collide.
            server = ServerBuilder.forPort(configuredPort)
                    .addService(serviceDefinition)
                    .executor(executor)
                    .build()
                    .start();
        } catch (IOException e) {
            // Unchecked, and fatal on purpose: a gateway that cannot bind its port must fail
            // startup loudly. Logging and continuing would leave a pod that passes its
            // liveness probe while serving nothing.
            throw new UncheckedIOException("gRPC server failed to bind port " + configuredPort, e);
        }

        log.info("gRPC ingest listening on port {} (phase {})", server.getPort(), PHASE);
    }

    @Override
    public void stop() {
        Server current = server;
        if (current == null) {
            return;
        }

        log.info("gRPC server: refusing new calls, draining up to {}", shutdownTimeout);
        current.shutdown();

        try {
            if (!current.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // LOUD: this is the case where a client may lose an unacknowledged batch.
                // Idempotency makes the retry safe, but the operator should know it happened.
                log.error(
                        "gRPC drain TIMED OUT after {}; cancelling in-flight calls. "
                                + "Those clients receive no ACK and must resend.",
                        shutdownTimeout);
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            // Restore the flag. Swallowing an interrupt during shutdown makes the thread
            // un-cancellable, which is the opposite of what shutdown wants.
            Thread.currentThread().interrupt();
            current.shutdownNow();
        } finally {
            // After the server, never before: the server's handlers run ON this executor, so
            // shutting it down first would kill the calls we are trying to let finish.
            // shutdown(), not close(): since Java 19 close() waits without a bound, and an
            // unbounded wait here is how a pod gets SIGKILLed mid-drain.
            if (executor != null) {
                executor.shutdown();
            }
        }
    }

    /**
     * The port actually bound.
     *
     * <p>Only meaningful after {@link #start()}, and that is the point: with port 0 the real
     * port is chosen by the OS, so an integration test has to ask rather than assume.
     */
    public int port() {
        Server current = server;
        if (current == null) {
            throw new IllegalStateException("the gRPC server has not started");
        }
        return current.getPort();
    }
}
