/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.streampulse.contracts.grpc.v1.Batch;
import com.streampulse.ingest.application.IngestUseCase;
import com.streampulse.ingest.domain.IngestCommand;
import com.streampulse.ingest.domain.IngestResult;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deadlines and cancellation actually reach the work (REQ-GR-03).
 *
 * <h2>Deadline vs timeout — the distinction the whole file is about</h2>
 *
 * <pre>
 *   TIMEOUT    a duration each hop invents for itself. Three hops with a 5s timeout each
 *              means the caller can wait 15s, and hop 3 happily starts work for a caller
 *              who left ten seconds ago.
 *
 *   DEADLINE   an ABSOLUTE point in time, set once by the caller and propagated. Every hop
 *              inherits the same instant, so hop 3 can see there is no time left and refuse
 *              to start rather than doing work nobody will read.
 * </pre>
 *
 * gRPC propagates the deadline through {@link Context}, which is why this is testable at all:
 * the use case runs inside the call's Context, so "did the deadline reach the business logic?"
 * is a question with a real answer rather than an architectural aspiration.
 *
 * <h2>Where this stops, honestly</h2>
 *
 * Cancellation is <b>observable</b>, not pre-emptive. It does not interrupt a publish that is
 * already in flight — nothing here calls {@code Thread.interrupt()} on the handler. What it
 * bounds is how much <em>new</em> work the server starts for a caller who has gone. Cutting an
 * in-flight publish short is the time limiter's job (ADR-0009), and the two are deliberately
 * separate: one is the client's budget, the other is ours.
 */
class GrpcDeadlineCancellationTest {

    private InProcessGrpcFixture grpc;

    @AfterEach
    void tearDown() {
        if (grpc != null) {
            grpc.close();
        }
    }

    @Test
    @DisplayName("a client deadline reaches the use case: the gRPC Context is cancelled mid-call")
    void deadlineCancelsTheServerSideContext() throws Exception {
        var contextCancelled = new CountDownLatch(1);
        var sawCancellationInsideTheCall = new AtomicBoolean(false);

        // The use case blocks, as a slow publish to a struggling broker would. While it blocks,
        // the client's deadline expires.
        IngestUseCase slowUseCase = new IngestUseCase() {
            @Override
            public IngestResult ingest(IngestCommand command) {
                Context callContext = Context.current();
                // Runnable::run — fire the listener on whichever thread cancels, so the latch
                // trips the instant gRPC notices rather than after a scheduling hop.
                callContext.addListener(ignored -> contextCancelled.countDown(), Runnable::run);
                try {
                    // Wait well past the 200ms deadline below. If nothing cancels, the await
                    // simply times out and the assertions below fail loudly instead of hanging.
                    contextCancelled.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sawCancellationInsideTheCall.set(callContext.isCancelled());
                return IngestResult.of(command.size(), 0);
            }
        };

        grpc = new InProcessGrpcFixture(slowUseCase, 50);

        StatusRuntimeException failure =
                catchThrowableOfType(StatusRuntimeException.class, () -> grpc.blockingStubFor("streamflix")
                        // withDeadlineAfter, not a socket timeout: this travels ON the call,
                        // as the grpc-timeout header, and is what the server reads.
                        .withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                        .sendBatch(Batch.newBuilder()
                                .addEvents(InProcessGrpcFixture.event(1))
                                .build()));

        // 1. The CLIENT is told why it got nothing, and told it in a way that distinguishes
        //    "you ran out of time" from "the server is broken".
        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);

        // 2. And — the half that matters — the SERVER learned about it. Without propagation the
        //    server would finish the publish, build a response and write it to a dead stream,
        //    holding an in-flight permit the whole time. Under load that is how a gateway ends
        //    up spending all its capacity on callers who have already given up.
        assertThat(contextCancelled.await(5, TimeUnit.SECONDS))
                .as("the server-side Context must be cancelled when the client's deadline expires")
                .isTrue();
        assertThat(sawCancellationInsideTheCall)
                .as("business logic can ask Context.isCancelled() and get a truthful answer")
                .isTrue();
    }

    @Test
    @DisplayName("an explicit client cancellation cancels the server-side Context too")
    void clientCancellationCancelsTheServerSideContext() throws Exception {
        var contextCancelled = new CountDownLatch(1);
        var callStarted = new CountDownLatch(1);

        IngestUseCase blockingUseCase = new IngestUseCase() {
            @Override
            public IngestResult ingest(IngestCommand command) {
                Context.current().addListener(ignored -> contextCancelled.countDown(), Runnable::run);
                callStarted.countDown();
                try {
                    contextCancelled.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return IngestResult.of(command.size(), 0);
            }
        };

        grpc = new InProcessGrpcFixture(blockingUseCase, 50);

        // A cancellable Context on the CLIENT side. Cancelling it is what a caller does when
        // the user navigated away, or when an upstream request of its own was abandoned —
        // exactly the case a deadline does not cover, because no time has run out.
        Context.CancellableContext clientContext = Context.current().withCancellation();
        var call = new Thread(() -> clientContext.run(() -> {
            try {
                grpc.blockingStubFor("streamflix")
                        .sendBatch(Batch.newBuilder()
                                .addEvents(InProcessGrpcFixture.event(1))
                                .build());
            } catch (StatusRuntimeException expected) {
                // CANCELLED, once the context below is cancelled. Asserted via the latch.
            }
        }));
        call.start();

        assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();
        clientContext.cancel(new RuntimeException("caller gave up"));

        assertThat(contextCancelled.await(5, TimeUnit.SECONDS))
                .as("cancellation must propagate to the server, not just abandon the client's future")
                .isTrue();

        call.join(5_000);
    }
}
