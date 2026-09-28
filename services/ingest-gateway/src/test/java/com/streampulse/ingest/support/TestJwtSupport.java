/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only JWT issuing and verification.
 *
 * <h2>Why not just run Keycloak</h2>
 *
 * Production validates RS256 tokens against Keycloak's JWKS endpoint. Booting Keycloak for
 * every integration test would add roughly a minute per run and a container that does
 * nothing the test is about.
 *
 * <p>Instead the test profile supplies a {@link JwtDecoder} backed by a <b>symmetric</b>
 * key, and mints tokens with the same key. The seam is Spring Security's own
 * {@code JwtDecoder} bean, so everything downstream — the filter chain, the
 * {@code JwtAuthenticationToken}, {@code TenantResolver} — is the real thing.
 *
 * <h2>What this does and does not prove</h2>
 *
 * <b>Proves:</b> that an unsigned or missing token is rejected; that a token without the
 * tenant claim fails closed; that the tenant is taken from the token and not the body;
 * that an expired token is refused.
 *
 * <b>Does not prove:</b> JWKS rotation, {@code iss} validation or {@code aud} validation.
 * Not because these tests are thin, but because in this setup <b>those things do not
 * exist</b>: the decoder is given no issuer to compare against, and the tokens below carry
 * no {@code aud} claim at all. {@code KeycloakOidcIT} covers all three against a real
 * issuer, and ADR-0011 explains why that had to be a separate suite.
 *
 * <p>HS256 here is a <b>test</b> choice, and the reason is the same one: with a shared
 * secret, every party that can verify a token can also mint one. No asymmetry means no
 * trust boundary — which is fine for a fixture and disqualifying for a resource server.
 *
 * <p>This class stays because it is <b>fast</b>: the thirteen tests in
 * {@code IngestEndToEndIT} need no identity provider, and that is worth keeping.
 */
@TestConfiguration
public class TestJwtSupport {

    /** 32 bytes: HMAC-SHA256 requires a key at least as long as its output. */
    public static final String SECRET = "streampulse-test-secret-key-32b!";

    @Bean
    JwtDecoder testJwtDecoder() {
        return decoder();
    }

    /**
     * The same decoder, without a Spring context.
     *
     * <p>The gRPC tests build an in-process server out of plain objects — no context at all —
     * and still need the real {@code JwtAuthInterceptor} with a real decoder behind it. Sharing
     * this factory with the {@code @Bean} above is what keeps "the token the tests mint" and
     * "the token the interceptor accepts" the same thing by construction.
     */
    public static JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(SECRET.getBytes(), "HmacSHA256"))
                .build();
    }

    /** Mints a valid token carrying the tenant claim. */
    public static String tokenFor(String tenant) {
        return token(tenant, Instant.now().plusSeconds(300));
    }

    /** Mints a token that has already expired, to prove expiry is actually checked. */
    public static String expiredTokenFor(String tenant) {
        return token(tenant, Instant.now().minusSeconds(60));
    }

    /** Mints a token with NO tenant claim, to prove we fail closed rather than guessing. */
    public static String tokenWithoutTenant() {
        return token(null, Instant.now().plusSeconds(300));
    }

    private static String token(String tenant, Instant expiry) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .subject("test-client")
                    .issuer("https://test.streampulse.dev")
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiry));
            if (tenant != null) {
                claims.claim("tenant", tenant);
            }
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(SECRET.getBytes()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint a test JWT", e);
        }
    }
}
