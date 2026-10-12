/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class IngestClient {

    public static final int TRANSPORT_FAILURE = -1;

    public record Outcome(int status, long latencyNanos, int accepted, String retryAfter) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;

    public IngestClient(HttpClient http, String ingestBaseUrl) {
        this.http = http;
        this.endpoint = URI.create(ingestBaseUrl + "/v1/events:batch");
    }

    public Outcome post(byte[] body, String bearerToken, int eventsInBatch) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearerToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.nanoTime() - start;
            int accepted = response.statusCode() == 202 ? acceptedCount(response.body(), eventsInBatch) : 0;
            return new Outcome(
                    response.statusCode(),
                    latency,
                    accepted,
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (IOException e) {
            return new Outcome(TRANSPORT_FAILURE, System.nanoTime() - start, 0, null);
        }
    }

    private static int acceptedCount(String body, int fallback) {
        try {
            JsonNode accepted = JSON.readTree(body).get("accepted");
            return accepted != null && accepted.isInt() ? accepted.asInt() : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }
}
