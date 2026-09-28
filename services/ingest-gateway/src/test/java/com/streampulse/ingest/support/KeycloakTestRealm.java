/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Drives a real Keycloak: container definition, token minting and the two admin-API calls
 * {@code KeycloakOidcIT} needs.
 *
 * <h2>Why a plain GenericContainer and not dasniko/testcontainers-keycloak</h2>
 *
 * The community module is real and maintained, and it would save the twenty lines below.
 * It was still not taken, for three reasons that outlive the convenience:
 *
 * <ol>
 *   <li><b>It pins its own Testcontainers version.</b> This build gets Testcontainers from
 *       the Spring Boot BOM, not from {@code libs.versions.toml} — the catalog says 1.21.3
 *       and the resolved jar is 1.21.2, because {@code io.spring.dependency-management}
 *       applies Maven-style precedence. Adding a library with its own opinion about that
 *       version puts a third party in the middle of an already surprising resolution.
 *   <li><b>It states "no LTS, no backports — stay up to date."</b> A learning project should
 *       not be blocked on someone else's release cadence. That is the same reasoning that
 *       rejected the community Avro Gradle plugin in ADR-0004.
 *   <li><b>The image tag stays identical to {@code docker/compose.yml}'s.</b> A wrapper that
 *       defaults to a different Keycloak would let the test prove things about a version
 *       nobody runs locally.
 * </ol>
 *
 * <p>What is lost: an admin client, and a nicer API for users and roles. Neither is needed —
 * this suite is about token validation, not identity management.
 */
public final class KeycloakTestRealm {

    /** Same tag as docker/compose.yml. If these drift, the test stops describing the stack. */
    public static final String IMAGE = "quay.io/keycloak/keycloak:26.0";

    public static final String REALM = "streampulse";
    public static final String ROGUE_REALM = "rogue-idp";

    /** The audience the gateway accepts. Set by the realm's oidc-audience-mapper. */
    public static final String INGEST_AUDIENCE = "streampulse-ingest";

    public static final String INGEST_CLIENT = "ingest-gateway";
    public static final String INGEST_SECRET = "ingest-gateway-local-secret";

    /** Same realm, same signing key, different audience — the control case for `aud`. */
    public static final String ANALYTICS_CLIENT = "analytics-dashboard";

    public static final String ANALYTICS_SECRET = "analytics-dashboard-local-secret";

    private static final String ADMIN = "admin";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String baseUrl;

    public KeycloakTestRealm(GenericContainer<?> keycloak) {
        this.baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    /**
     * The container, importing the same realm file {@code make up-core} mounts.
     *
     * <p>Only the streampulse realm is imported at boot. The adversary realm is created later
     * over the admin API, deliberately: it is a test fixture, and a fixture that ships in the
     * directory compose mounts would end up running in everybody's dev stack.
     *
     * <p>(That is also the pragmatic answer to a Keycloak 26.0.8 behaviour observed here —
     * two realm files in the import directory both import and the server then fails to start
     * with "Session not bound to a realm". One file works. Root cause not established; see
     * MILESTONES.)
     */
    public static GenericContainer<?> container() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("realms/streampulse-realm.json"),
                        "/opt/keycloak/data/import/streampulse-realm.json")
                .withCommand("start-dev", "--import-realm")
                // NOT the /health endpoint: Keycloak 26 serves management on port 9000, not
                // 8080. The realm's own discovery document is the better probe anyway — it
                // answers 200 only once the IMPORT has finished, which is what we are waiting
                // for. Waiting on the port alone would race the import.
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                        .forPort(8080)
                        .forStatusCode(200))
                // Keycloak runs Liquibase over 148 changesets on first boot. ~40s observed on
                // an M-series Mac with the image already pulled; three minutes is headroom for
                // a slower machine, not an expectation.
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    /** The issuer as Keycloak will spell it in the {@code iss} claim of tokens from that realm. */
    public String issuerUri(String realm) {
        return baseUrl + "/realms/" + realm;
    }

    /**
     * A real access token via the {@code client_credentials} grant.
     *
     * <p>Machine-to-machine, so there is no user and no browser. The client authenticates with
     * its own secret and Keycloak issues a token for its service account.
     */
    public String tokenFor(String realm, String clientId, String clientSecret) {
        JsonNode body = postForm(
                issuerUri(realm) + "/protocol/openid-connect/token",
                null,
                "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + clientSecret);
        return body.path("access_token").asText();
    }

    /**
     * Stands up a SECOND issuer from a realm file on the test classpath.
     *
     * <p>This is the attacker's move made concrete: anyone can run an OIDC provider and mint
     * a token asserting whatever they like. What stops it is not the claims — they are
     * identical — but that the signature is made by a key our issuer's JWKS does not list.
     */
    public void createRealmFrom(String classpathResource) {
        String realmJson = readClasspath(classpathResource);
        int status = postJson("/admin/realms", realmJson);
        if (status != 201 && status != 409) { // 409 = already there, fine on a reused container
            throw new IllegalStateException("Creating realm from " + classpathResource + " returned " + status);
        }
    }

    /**
     * Rotates the realm's active RS256 signing key.
     *
     * <p>Keycloak signs with the highest-priority active provider, so adding one at priority
     * 200 beside the generated default at 100 makes the new key the signer. The old key stays
     * in the JWKS — that overlap is the point of a rotation: tokens already issued must keep
     * verifying while new ones are signed by the new key.
     *
     * <p>Verified by observation rather than assumed: the {@code kid} in the token header
     * changes, and {@code /protocol/openid-connect/certs} then lists both.
     */
    public void rotateSigningKey(String realm) {
        String component =
                """
                {"name":"rotated-rsa","providerId":"rsa-generated",
                 "providerType":"org.keycloak.keys.KeyProvider","parentId":"%s",
                 "config":{"priority":["200"],"algorithm":["RS256"],
                           "enabled":["true"],"active":["true"]}}"""
                        .formatted(realm);

        int status = postJson("/admin/realms/" + realm + "/components", component);
        if (status != 201) {
            throw new IllegalStateException("Adding a signing key to '" + realm + "' returned " + status);
        }
    }

    // ------------------------------------------------------------------
    // Reading a JWT WITHOUT verifying it.
    //
    // Only ever used to assert on the fixture — "this token really is RS256", "these two
    // tokens really do share a kid". Never to make a decision: the production path decodes
    // through Spring Security, which is the whole thing under test.
    // ------------------------------------------------------------------

    public static JsonNode headerOf(String jwt) {
        return decodeSegment(jwt, 0);
    }

    public static JsonNode claimsOf(String jwt) {
        return decodeSegment(jwt, 1);
    }

    public static String kidOf(String jwt) {
        return headerOf(jwt).path("kid").asText();
    }

    private static JsonNode decodeSegment(String jwt, int index) {
        try {
            String segment = jwt.split("\\.")[index];
            // JWT uses base64URL WITHOUT padding. Decoder.decode tolerates the missing '='.
            return JSON.readTree(Base64.getUrlDecoder().decode(segment));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a readable JWT: " + jwt, e);
        }
    }

    // ------------------------------------------------------------------

    /** The master-realm admin token, for the two admin-API calls above. */
    private String adminToken() {
        return postForm(
                        baseUrl + "/realms/master/protocol/openid-connect/token",
                        null,
                        "grant_type=password&client_id=admin-cli&username=" + ADMIN + "&password=" + ADMIN)
                .path("access_token")
                .asText();
    }

    private int postJson(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + adminToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request).statusCode();
    }

    private JsonNode postForm(String url, String bearer, String form) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }

        HttpResponse<String> response = send(builder.build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Keycloak returned " + response.statusCode() + ": " + response.body());
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak returned unparseable JSON", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak call failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: a test runner that is shutting down
            // must not find a thread that has quietly forgotten it was interrupted.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + request.uri(), e);
        }
    }

    private static String readClasspath(String resource) {
        try (var in = KeycloakTestRealm.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Not on the test classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
