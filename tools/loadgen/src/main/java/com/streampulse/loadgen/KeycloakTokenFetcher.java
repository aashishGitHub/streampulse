/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Standard OIDC client_credentials grant against the realm in docker/keycloak/realms/streampulse-realm.json. */
public final class KeycloakTokenFetcher implements TokenProvider.Fetcher {

    public static final String CLIENT_ID = "ingest-gateway";
    private static final String TOKEN_PATH = "/realms/streampulse/protocol/openid-connect/token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI tokenEndpoint;
    private final String clientSecret;

    public KeycloakTokenFetcher(HttpClient http, String keycloakBaseUrl, String clientSecret) {
        this.http = http;
        this.tokenEndpoint = URI.create(keycloakBaseUrl + TOKEN_PATH);
        this.clientSecret = clientSecret;
    }

    @Override
    public TokenProvider.Token fetch() throws IOException, InterruptedException {
        String form = "grant_type=client_credentials&client_id=" + encode(CLIENT_ID) + "&client_secret="
                + encode(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Keycloak token endpoint " + tokenEndpoint + " answered " + response.statusCode()
                    + " (401 usually means a wrong LOADGEN_CLIENT_SECRET)");
        }
        JsonNode body = JSON.readTree(response.body());
        JsonNode token = body.get("access_token");
        if (token == null || !token.isTextual()) {
            throw new IOException("Keycloak response had no access_token");
        }
        return new TokenProvider.Token(token.asText(), body.path("expires_in").asLong(60));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
