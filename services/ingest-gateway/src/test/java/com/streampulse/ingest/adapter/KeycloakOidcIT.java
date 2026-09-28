/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streampulse.ingest.support.InMemoryIngestBackend;
import com.streampulse.ingest.support.KeycloakTestRealm;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OIDC validation against a REAL Keycloak (REQ-IG-07/08/09, ADR-0011).
 *
 * <h2>Why this exists when IngestEndToEndIT already tests JWTs</h2>
 *
 * {@code TestJwtSupport} supplies a symmetric-key {@code JwtDecoder} and mints HS256 tokens
 * with the same secret. That suite is fast, needs no Docker, and genuinely proves signature
 * verification, expiry and the tenant claim.
 *
 * <p>It cannot prove anything about the three things production actually depends on, because
 * in that setup they do not exist:
 *
 * <table border="1">
 *   <caption>What each suite can and cannot see</caption>
 *   <tr><th></th><th>symmetric-key suite</th><th>this suite</th></tr>
 *   <tr><td>signature, expiry, tenant claim</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>RS256 + a public key fetched over HTTP</td>
 *       <td>no — one shared secret, no key to fetch</td><td>yes</td></tr>
 *   <tr><td>key rotation</td>
 *       <td>no — a constant cannot rotate</td><td>yes</td></tr>
 *   <tr><td>{@code iss} validation</td>
 *       <td>no — the decoder has no configured issuer to compare against</td><td>yes</td></tr>
 *   <tr><td>{@code aud} validation</td>
 *       <td>no — the test tokens carry no {@code aud} at all</td><td>yes</td></tr>
 * </table>
 *
 * <p>The sharpest way to put it: with a shared secret, <b>every party that can verify a token
 * can also mint one</b>. There is no asymmetry, so there is no trust boundary, so there is
 * nothing for an issuer check to protect. That is why this is an addition and not a
 * replacement — the fast suite keeps its job, and this one takes the job it never had.
 *
 * <h2>How the three negatives are isolated</h2>
 *
 * A test that rejects a token proves nothing unless you know <em>why</em> it was rejected.
 * Each negative here differs from the accepted token in exactly one respect, and the test
 * asserts that premise before asserting the 401:
 *
 * <pre>
 *   accepted      iss=streampulse   kid=K1   aud=[streampulse-ingest, account]   tenant=streamflix
 *   wrong aud     iss=streampulse   kid=K1   aud=[streampulse-analytics, …]      tenant=streamflix
 *                 └── same issuer, SAME SIGNING KEY, valid tenant. Only `aud` differs.
 *   wrong issuer  iss=rogue-idp     kid=K2   aud=[streampulse-ingest, account]   tenant=streamflix
 *                 └── identical claims. A different realm signs with a different key.
 * </pre>
 *
 * <h2>Cost</h2>
 *
 * One container. Redis and Kinesis are replaced by {@link InMemoryIngestBackend}: they are
 * already proven by {@code IngestEndToEndIT}, and re-running them here would give a Keycloak
 * failure two extra suspects. Keycloak itself takes ~40s to boot (Liquibase runs 148
 * changesets on first start), which is why this is not in the Docker-free suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InMemoryIngestBackend.class)
@Testcontainers
class KeycloakOidcIT {

    @Container
    static final GenericContainer<?> KEYCLOAK = KeycloakTestRealm.container();

    private static final String REALM = KeycloakTestRealm.REALM;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static KeycloakTestRealm keycloak;

    /** Built lazily: the container's mapped port only exists once Testcontainers has started it. */
    private static KeycloakTestRealm realm() {
        if (keycloak == null) {
            keycloak = new KeycloakTestRealm(KEYCLOAK);
        }
        return keycloak;
    }

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        // The ONLY security configuration this service is given. No key, no algorithm, no
        // JWKS url — Spring Boot turns issuer-uri into NimbusJwtDecoder.withIssuerLocation(),
        // which reads the discovery document, follows jwks_uri, and installs a
        // JwtIssuerValidator. Verified against spring-boot-autoconfigure 3.5.3's bytecode
        // before being relied on here.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realm().issuerUri(REALM));
        // Audience is a separate property because it is a separate decision: the issuer says
        // WHO minted the token, the audience says WHICH api it was minted for.
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> KeycloakTestRealm.INGEST_AUDIENCE);
    }

    @BeforeAll
    static void standUpTheAdversary() {
        // A second issuer, created over the admin API rather than imported at boot: it is a
        // test fixture, and anything in docker/keycloak/realms/ ends up in everyone's dev
        // stack via `make up-core`.
        realm().createRealmFrom("keycloak/rogue-realm.json");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private InMemoryIngestBackend.RecordingPublisher published;

    // ------------------------------------------------------------------

    private static String anEvent() {
        return """
                {"eventId":"%s","sessionId":"sess-%s","titleId":"title-42",
                 "deviceType":"SMART_TV","region":"us-east-1",
                 "occurredAt":"2026-01-15T12:00:00Z","type":"HEARTBEAT"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID());
    }

    private ResponseEntity<String> postWith(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        String body = "{\"events\":[" + anEvent() + "]}";
        return rest.postForEntity("/v1/events:batch", new HttpEntity<>(body, headers), String.class);
    }

    private static String ingestToken() {
        return realm().tokenFor(REALM, KeycloakTestRealm.INGEST_CLIENT, KeycloakTestRealm.INGEST_SECRET);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the fixture itself")
    class Fixture {

        @Test
        @DisplayName("the realm imported, and its discovery document names the issuer Spring was given")
        void discoveryDocumentIsTheOneSpringWillRead() throws Exception {
            // Cheap and loud. If the realm import silently failed, every other test in this
            // class fails with an opaque 401 and the cause is three layers down.
            //
            // It also pins the thing withIssuerLocation() asserts for us: Spring Security
            // refuses a discovery document whose `issuer` differs from the url it was fetched
            // from, because otherwise an attacker who can influence discovery could point the
            // resource server at their own key set.
            String issuer = realm().issuerUri(REALM);
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode doc = JSON.readTree(response.body());
            assertThat(doc.path("issuer").asText()).isEqualTo(issuer);
            assertThat(doc.path("jwks_uri").asText()).startsWith(issuer);
        }
    }

    @Nested
    @DisplayName("a real Keycloak token")
    class Accepted {

        @Test
        @DisplayName("REQ-IG-09: RS256, verified against a key this service never held, and the tenant claim wins")
        void realTokenIsAcceptedAndTenantIsHonoured() {
            String token = ingestToken();

            // The premise, asserted rather than assumed. RS256 means the signature was made
            // with a PRIVATE key we do not have and cannot have — the gateway can only verify,
            // never mint. That asymmetry is the entire difference from the HS256 suite.
            assertThat(KeycloakTestRealm.headerOf(token).path("alg").asText())
                    .as("Keycloak must be signing asymmetrically")
                    .isEqualTo("RS256");
            assertThat(KeycloakTestRealm.kidOf(token)).isNotBlank();

            published.clear();
            ResponseEntity<String> response = postWith(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            // And the tenant reached the publisher from the VERIFIED claim. Without this the
            // test would only prove the request got past the filter chain, not that the
            // multi-tenancy boundary is fed by the token.
            assertThat(published.lastTenant())
                    .as("the tenant must come from Keycloak's `tenant` claim")
                    .isEqualTo("streamflix");
        }

        @Test
        @DisplayName("a key ROTATION is picked up with no restart, and tokens signed by the OLD key still verify")
        void keyRotationIsPickedUp() {
            String beforeRotation = ingestToken();

            // Warm the decoder's JWK cache, so the rotation genuinely has to be noticed rather
            // than simply being what was fetched the first time.
            assertThat(postWith(beforeRotation).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            realm().rotateSigningKey(REALM);
            String afterRotation = ingestToken();

            assertThat(KeycloakTestRealm.kidOf(afterRotation))
                    .as("the rotation must actually have changed the signing key")
                    .isNotEqualTo(KeycloakTestRealm.kidOf(beforeRotation));

            // THE assertion. A `kid` the cached JWK set does not contain forces a re-fetch
            // from the issuer. Verified from spring-security-oauth2-jose 6.5.1's bytecode
            // that this path is NOT rate limited — the builder calls rateLimited(false) —
            // so the new key is available on the very next request and this needs no polling.
            //
            // Get this wrong and nothing fails until the day someone rotates a production
            // key, at which point every pod starts 401ing until it is restarted.
            assertThat(postWith(afterRotation).getStatusCode())
                    .as("a token signed by the NEW key must be accepted without a restart")
                    .isEqualTo(HttpStatus.ACCEPTED);

            // The other half, and the reason rotation is safe at all: the old key stays in the
            // JWKS during the overlap, so tokens already in flight keep working. A rotation
            // that invalidated them would be an outage, not a rotation.
            assertThat(postWith(beforeRotation).getStatusCode())
                    .as("tokens issued before the rotation must survive the overlap window")
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
    }

    @Nested
    @DisplayName("tokens that must NOT be accepted")
    class Rejected {

        @Test
        @DisplayName("a token from a DIFFERENT ISSUER is rejected with 401 — identical claims, foreign key")
        void tokenFromAnotherIssuerIsRejected() {
            String rogue = realm().tokenFor(
                            KeycloakTestRealm.ROGUE_REALM,
                            KeycloakTestRealm.INGEST_CLIENT,
                            KeycloakTestRealm.INGEST_SECRET);

            // The premise: every claim the gateway reads says this token is fine. Anyone can
            // run an OIDC provider and assert whatever they like — claims are worth exactly
            // what the issuer behind them is worth.
            JsonNode claims = KeycloakTestRealm.claimsOf(rogue);
            assertThat(claims.path("tenant").asText()).isEqualTo("streamflix");
            assertThat(claims.path("aud").toString()).contains(KeycloakTestRealm.INGEST_AUDIENCE);
            assertThat(claims.path("iss").asText())
                    .as("the one thing that differs")
                    .isEqualTo(realm().issuerUri(KeycloakTestRealm.ROGUE_REALM))
                    .isNotEqualTo(realm().issuerUri(REALM));

            assertThat(postWith(rogue).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // Worth being precise about what this does and does not isolate: a foreign realm
            // has its own keypair, so the signature check fails FIRST and the issuer check is
            // the second line of defence rather than the one that fired. That ordering is
            // correct — an unknown key should never get as far as claim validation — and it is
            // also why the `aud` case below uses a client of the SAME realm, where the
            // signature is genuinely valid and only one claim can be responsible.
        }

        @Test
        @DisplayName("a token with the WRONG AUDIENCE is rejected with 401 — same issuer, SAME signing key")
        void tokenForAnotherAudienceIsRejected() {
            String accepted = ingestToken();
            String analytics =
                    realm().tokenFor(REALM, KeycloakTestRealm.ANALYTICS_CLIENT, KeycloakTestRealm.ANALYTICS_SECRET);

            // The premise, and the whole point of the test: this token is signed by the same
            // key, by the same issuer, is unexpired, and even carries a valid tenant claim.
            // The signature check cannot tell the two clients apart, because one realm has one
            // key. Only `aud` can.
            assertThat(KeycloakTestRealm.kidOf(analytics))
                    .as("same realm ⇒ same signing key, so signature validity is NOT what differs")
                    .isEqualTo(KeycloakTestRealm.kidOf(accepted));

            JsonNode claims = KeycloakTestRealm.claimsOf(analytics);
            assertThat(claims.path("iss").asText()).isEqualTo(realm().issuerUri(REALM));
            assertThat(claims.path("tenant").asText()).isEqualTo("streamflix");
            assertThat(claims.path("aud").toString())
                    .as("the one thing that differs")
                    .doesNotContain(KeycloakTestRealm.INGEST_AUDIENCE);

            // Delete `audiences` from application.yml and this is a 202: every client of the
            // company IdP becomes a valid writer of every tenant's telemetry, and a leaked
            // read-only dashboard credential becomes a write credential.
            assertThat(postWith(analytics).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
