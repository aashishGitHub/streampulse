/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunnerTest {

    private HttpServer server;
    private final AtomicInteger ingestStatus = new AtomicInteger(202);
    private final List<String> tokenForms = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger ingestCalls = new AtomicInteger();

    @BeforeEach
    void startFakeKeycloakAndIngest() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/realms/streampulse/protocol/openid-connect/token", exchange -> {
            tokenForms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "{\"access_token\":\"fake-token\",\"expires_in\":300}", null);
        });
        server.createContext("/v1/events:batch", exchange -> {
            ingestCalls.incrementAndGet();
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            int status = ingestStatus.get();
            reply(
                    exchange,
                    status,
                    status == 202 ? "{\"accepted\":50,\"duplicates\":0}" : "{}",
                    status == 429 ? "1" : null);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Runner runner(LoadGenConfig config) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        TokenProvider tokens = new TokenProvider(Clock.systemUTC(), new KeycloakTokenFetcher(http, base, "s3cret&=+"));
        BatchBuilder batches = new BatchBuilder(Clock.systemUTC(), new Random(1), UUID::randomUUID, 10, 0);
        return new Runner(config, tokens, new IngestClient(http, base), batches, 16);
    }

    private Runner runnerThatIgnoresRetryAfter(LoadGenConfig config) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        TokenProvider tokens = new TokenProvider(Clock.systemUTC(), new KeycloakTokenFetcher(http, base, "s"));
        BatchBuilder batches = new BatchBuilder(Clock.systemUTC(), new Random(1), UUID::randomUUID, 10, 0);
        return new Runner(config, tokens, new IngestClient(http, base), batches, 16, false);
    }

    private LoadGenConfig config(long rate, long seconds) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new LoadGenConfig(rate, seconds, 50, 10, 0, base, base, base + "/none");
    }

    @Test
    void sendsBearerTokenFromClientCredentialsGrantAndCountsAcceptedEvents() throws Exception {
        RunSummary summary = runner(config(200, 2)).run();

        assertThat(tokenForms).hasSize(1);
        assertThat(tokenForms.get(0))
                .contains("grant_type=client_credentials")
                .contains("client_id=ingest-gateway")
                .contains("client_secret=s3cret%26%3D%2B");
        assertThat(authHeaders).isNotEmpty().allMatch("Bearer fake-token"::equals);
        assertThat(summary.status202()).isBetween(6L, 9L);
        assertThat(summary.eventsAccepted()).isEqualTo(summary.status202() * 50);
        assertThat(summary.status429() + summary.status503() + summary.statusOther() + summary.transportFailures())
                .isZero();
        assertThat(summary.p99Nanos()).isPresent();
    }

    @Test
    void honoursRetryAfterOn429InsteadOfHammeringTheServer() throws Exception {
        ingestStatus.set(429);

        RunSummary summary = runner(config(400, 3)).run();

        assertThat(summary.status429()).isBetween(1L, 5L);
        assertThat(summary.status202()).isZero();
        assertThat(summary.p50Nanos()).isEmpty();
        assertThat(ingestCalls.get()).isLessThan(24);
    }

    @Test
    void keepsOfferingTheRequestedRateWhenToldNotToBackOff() throws Exception {
        ingestStatus.set(429);

        // 400 events/s in batches of 50 is 8 requests/s, so 2 s asks for about 16. A client that obeyed
        // Retry-After: 1 would send a handful; a saturation search must not do that.
        RunSummary summary = runnerThatIgnoresRetryAfter(config(400, 2)).run();

        assertThat(summary.status429()).isGreaterThan(10);
        assertThat(ingestCalls.get()).isGreaterThan(10);
    }

    @Test
    void countsServiceUnavailableSeparatelyFromOtherErrors() throws Exception {
        ingestStatus.set(503);

        RunSummary summary = runner(config(400, 2)).run();

        assertThat(summary.status503()).isPositive();
        assertThat(summary.statusOther()).isZero();
    }

    @Test
    void countsUnexpectedStatusesAsOther() throws Exception {
        ingestStatus.set(500);

        RunSummary summary = runner(config(200, 1)).run();

        assertThat(summary.statusOther()).isPositive();
        assertThat(summary.status429()).isZero();
    }
}
