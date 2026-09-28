/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter.in.rest;

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
 * The defence is structural, not a check: {@code EventDto} has no tenant field at all, so
 * there is nothing to accidentally trust. The safest way to not trust an input is not to
 * accept it.
 */
@Component
public class TenantResolver {

    /** Claim carrying the tenant. Keycloak is configured to include it via a mapper. */
    static final String TENANT_CLAIM = "tenant";

    /**
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

        Jwt jwt = jwtAuth.getToken();
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);

        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("Token has no '" + TENANT_CLAIM + "' claim; the issuer is misconfigured");
        }

        // TenantId validates the format, so a malformed claim is rejected here rather than
        // becoming a malformed Kinesis partition key or Redis prefix downstream.
        return TenantId.of(tenant);
    }
}
