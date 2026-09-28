/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import com.streampulse.platform.domain.TenantId;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Carries the verified tenant from the auth interceptor to the service method.
 *
 * <h2>What {@link Context} is, and what it is not</h2>
 *
 * gRPC's {@code Context} is an immutable, per-call map that gRPC attaches to the thread for
 * the duration of every callback of that call. It is the gRPC answer to the same question
 * Spring Security answers with {@code SecurityContextHolder}: <em>how does work started by an
 * interceptor reach the handler without being threaded through every signature?</em>
 *
 * <pre>
 *   JwtAuthInterceptor                     TelemetryIngestGrpcService
 *   ┌──────────────────────────┐           ┌───────────────────────────────┐
 *   │ decode + verify the JWT  │           │ onNext / sendBatch            │
 *   │ TenantId from the claim  │──────────▶│ GrpcTenantContext.require()   │
 *   │ Context.withValue(TENANT)│  attached │ → the SAME TenantId           │
 *   └──────────────────────────┘  per call └───────────────────────────────┘
 * </pre>
 *
 * <p>The difference from a {@code ThreadLocal} matters: a {@code Context} is <b>immutable and
 * explicitly propagated</b>. It cannot leak into the next call on a pooled thread, because
 * gRPC attaches and detaches it around each callback rather than leaving it set. That is also
 * why it survives a virtual thread being unmounted and rescheduled.
 *
 * <h2>Why a dedicated key rather than passing the tenant in the message</h2>
 *
 * REQ-IG-08. The protobuf {@code TelemetryEvent} has no tenant field at all, so the only
 * route from the wire to the tenant is a signed token that an interceptor verified. Removing
 * the option is stronger than validating it.
 */
public final class GrpcTenantContext {

    private GrpcTenantContext() {}

    /**
     * The key. {@code Context.key} does NOT store a value — it creates a typed handle that is
     * then used to read and write one, so two libraries choosing the same string are still
     * independent keys.
     */
    static final Context.Key<TenantId> TENANT = Context.key("streampulse.tenant");

    /**
     * @throws StatusRuntimeException UNAUTHENTICATED if no tenant is attached to this call.
     *     <p>Defence in depth: {@link JwtAuthInterceptor} rejects an unauthenticated call long
     *     before the service method runs, so reaching this branch means the service was bound
     *     <em>without</em> the interceptor. Failing closed makes that misconfiguration an
     *     immediate, obvious 401-equivalent rather than a batch quietly published under a null
     *     tenant.
     */
    static TenantId requireTenant() {
        TenantId tenant = TENANT.get();
        if (tenant == null) {
            throw Status.UNAUTHENTICATED
                    .withDescription("no verified tenant on this call")
                    .asRuntimeException();
        }
        return tenant;
    }
}
