/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.grpc;

import com.streampulse.ingest.adapter.in.TenantResolver;
import com.streampulse.platform.domain.TenantId;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Authenticates every gRPC call from a bearer token in the call metadata (REQ-GR-05).
 *
 * <h2>This is the gRPC equivalent of the servlet filter chain</h2>
 *
 * <pre>
 *   REST                                     gRPC
 *   ─────────────────────────────────────    ─────────────────────────────────────
 *   SecurityFilterChain                      ServerInterceptor  ← this class
 *     BearerTokenAuthenticationFilter          reads metadata "authorization"
 *       reads Authorization: Bearer …          decodes with the SAME JwtDecoder
 *       → SecurityContextHolder                → gRPC Context
 *   DispatcherServlet → IngestController     ServerCall.Listener → GrpcService
 * </pre>
 *
 * Both run <b>before</b> the handler, so neither handler has to re-check anything. The thing
 * worth noticing is what is shared and what is not: the <b>plumbing</b> differs completely,
 * the <b>decoder</b> and the <b>tenant rule</b> are literally the same objects. A bug fixed
 * in {@link TenantResolver} is fixed on both protocols; a bug fixed in only one of two copies
 * is the failure this design exists to prevent.
 *
 * <h2>Why reuse Spring Security's {@code JwtDecoder} rather than parse the token here</h2>
 *
 * Because "validate a JWT" is not one check, it is five: signature, algorithm, issuer,
 * audience, expiry. Hand-rolling them for the second protocol is how a service ends up
 * accepting, over gRPC, a token it would have rejected over HTTP. Reusing the decoder bean
 * means the JWKS endpoint, the key rotation and the clock skew allowance are configured once.
 *
 * <h2>Why every failure is UNAUTHENTICATED and none of them says why</h2>
 *
 * Expired, wrong signature, missing header, missing tenant claim — all the same status and
 * the same terse description. The detail goes to the log, where an operator can see it, and
 * not to the caller, who may be probing. Distinguishing "expired" from "bad signature" tells
 * an attacker which half of the token to work on.
 */
public class JwtAuthInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    /**
     * HTTP/2 header names are lower-case, and gRPC metadata keys are case-insensitive but
     * normalise to lower-case. Writing "Authorization" here works, but writing it lower-case
     * is what the wire actually carries.
     */
    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final TenantResolver tenantResolver;

    public JwtAuthInterceptor(JwtDecoder jwtDecoder, TenantResolver tenantResolver) {
        this.jwtDecoder = jwtDecoder;
        this.tenantResolver = tenantResolver;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return reject(call, "missing or malformed bearer token", null);
        }

        TenantId tenant;
        try {
            Jwt jwt = jwtDecoder.decode(authorization.substring(BEARER.length()));
            // The same one rule the REST path applies. Note what this rejects: a perfectly
            // valid, correctly signed, unexpired token that carries no tenant claim. Guessing
            // a default there would write one customer's telemetry into another's namespace.
            tenant = tenantResolver.resolve(jwt);
        } catch (JwtException e) {
            return reject(call, "token rejected by the decoder", e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // No tenant claim, or a claim that is not a valid tenant id. Over REST this
            // surfaces as a 500 because it reaches the generic handler; here it is treated as
            // what it is — a caller we cannot attribute to a tenant. See ADR-0010 for why the
            // two protocols differ on this one case and which of them is right.
            return reject(call, "token carries no usable tenant claim", e);
        }

        // Attach the tenant for the whole call, then hand over. Contexts.interceptCall is what
        // makes the Context *current* inside every later callback — onMessage, onHalfClose,
        // onCancel. Returning `next.startCall(call, headers)` directly instead would leave the
        // value attached to nothing and the service would find an empty Context.
        Context contextWithTenant = Context.current().withValue(GrpcTenantContext.TENANT, tenant);
        return Contexts.interceptCall(contextWithTenant, call, headers, next);
    }

    /**
     * Closes the call and returns a listener that does nothing.
     *
     * <p>The no-op listener is required, not optional: gRPC hands every inbound message to the
     * returned listener, and returning {@code null} throws inside the transport. Closing first
     * means the client is told immediately rather than after streaming a batch we will ignore.
     */
    private <ReqT, RespT> ServerCall.Listener<ReqT> reject(
            ServerCall<ReqT, RespT> call, String reason, Exception cause) {

        // DEBUG, not WARN. Unauthenticated calls are constant background noise on any public
        // endpoint; logging each one at WARN trains everybody to ignore WARN.
        log.debug("gRPC call rejected: {}", reason, cause);

        call.close(Status.UNAUTHENTICATED.withDescription("invalid or missing credentials"), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
