/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

public final class FreshnessScraper {

    /** Micrometer Timer {@code streampulse.sink.freshness} with a histogram, in Prometheus text form. */
    public static final String BUCKET_METRIC = "streampulse_sink_freshness_seconds_bucket";

    private final HttpClient http;
    private final URI endpoint;

    public FreshnessScraper(HttpClient http, String freshnessUrl) {
        this.http = http;
        this.endpoint = URI.create(freshnessUrl);
    }

    public Optional<BucketHistogram> scrape() throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "text/plain")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            BucketHistogram histogram = BucketHistogram.parse(response.body(), BUCKET_METRIC);
            return histogram.buckets().isEmpty() ? Optional.empty() : Optional.of(histogram);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
