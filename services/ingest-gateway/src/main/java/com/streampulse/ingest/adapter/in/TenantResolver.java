/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in;

import com.streampulse.platform.domain.TenantId;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Derives the tenant from the <b>verified</b> JWT.
 *
 * <h2>This class is the multi-tenancy boundary</h2>
 *
 * REQ-IG-07 / REQ-IG-08. The tenant is read from a signed token claim and <b>never</b> from
 * the request body, a query parameter, or a header the client controls.
 *
 * <p>The failure this prevents is the worst one a multi-tenant system has:
 *
 * <pre>
 *   ❌  tenantId from the body
 *       POST /v1/events:batch  { "tenantId": "sportsnow", ... }
 *       → any authenticated caller writes into ANY tenant.
 *          Their token was valid. Authorization never happened.
 *
 *   ✅  tenantId from the token
 *       Authorization: Bearer eyJ…  ("tenant": "streamflix")
 *       → the caller can only ever write to the tenant the issuer put in the token,
 *          and the signature is what makes that unforgeable.
 * </pre>
 *
 * The defence is structural, not a check: {@code EventDto} has no tenant field at all, and
 * neither does the protobuf {@code TelemetryEvent}, so there is nothing to accidentally
 * trust. The safest way to not trust an input is not to accept it.
 *
 * <h2>Why it sits in {@code adapter.in} rather than {@code adapter.in.rest}</h2>
 *
 * Because BS-09 gave it a second caller. REST arrives with a Spring {@code Authentication}
 * that the filter chain has already built; gRPC arrives with a {@link Jwt} that the
 * {@code JwtAuthInterceptor} decoded itself — different plumbing, <b>identical</b> rule.
 * Keeping one implementation means the tenant rule cannot be right on one protocol and
 * wrong on the other, which is precisely the bug a second front end invites.
 */
@Component
public class TenantResolver {

    /** Claim carrying the tenant. Keycloak is configured to include it via a mapper. */
    static final String TENANT_CLAIM = "tenant";

    /**
     * The REST path: unwrap Spring Security's token, then apply the one rule below.
     *
     * @throws IllegalStateException if the token carries no tenant claim — fail closed. A
     *     token without a tenant is a misconfigured issuer, and guessing a default would
     *     silently write someone's data into the wrong place.
     */
    public TenantId resolve(Authentication authentication) {
        if (!(authentication
                instanceof
                org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("Expected a JWT authentication, got "
                    + (authentication == null
                            ? "none"
                            : authentication.getClass().getSimpleName()));
        }

        return resolve(jwtAuth.getToken());
    }

    /**
     * The rule itself, over an already-verified token.
     *
     * <p>The gRPC interceptor calls this directly: it has decoded the token from call
     * metadata and has no {@code Authentication} to hand. That is the whole reason this
     * overload exists — so the claim rule is stated once and both protocols obey the same
     * one.
     *
     * <p>Note the precondition in the name of the parameter's type: a {@link Jwt} is what a
     * {@code JwtDecoder} produces, and a decoder only produces one after checking the
     * signature, the issuer and the expiry. This method must never be handed a token that
     * has merely been parsed.
     *
     * @throws IllegalStateException if the token carries no tenant claim — fail closed.
     * @throws IllegalArgumentException if the claim is present but not a valid tenant id.
     */
    public TenantId resolve(Jwt jwt) {
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);

        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("Token has no '" + TENANT_CLAIM + "' claim; the issuer is misconfigured");
        }

        // TenantId validates the format, so a malformed claim is rejected here rather than
        // becoming a malformed Kinesis partition key or Redis prefix downstream.
        return TenantId.of(tenant);
    }
}
