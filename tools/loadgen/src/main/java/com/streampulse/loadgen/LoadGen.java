/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class LoadGen {

    private static final String SECRET_ENV = "LOADGEN_CLIENT_SECRET";
    private static final int MAX_IN_FLIGHT = 256;
    private static final double FRESHNESS_QUANTILE = 0.99;

    private LoadGen() {}

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.print(ArgParser.USAGE);
                return;
            }
        }

        LoadGenConfig config;
        Optional<RampPlan> ramp;
        try {
            config = ArgParser.parse(args);
            ramp = ArgParser.parseRamp(args);
        } catch (ArgumentException e) {
            System.err.println("loadgen: " + e.getMessage());
            System.err.print(ArgParser.USAGE);
            System.exit(2);
            return;
        }

        String secret = System.getenv(SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            System.err.println("loadgen: " + SECRET_ENV + " is not set. It is the 'ingest-gateway' client secret"
                    + " from docker/keycloak/realms/streampulse-realm.json.");
            System.exit(2);
            return;
        }

        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        TokenProvider tokens =
                new TokenProvider(Clock.systemUTC(), new KeycloakTokenFetcher(http, config.keycloakUrl(), secret));
        BatchBuilder batches = new BatchBuilder(
                Clock.systemUTC(),
                ThreadLocalRandom.current(),
                UUID::randomUUID,
                config.sessions(),
                config.lateFraction());
        if (ramp.isPresent()) {
            runRamp(ramp.get(), config, tokens, new IngestClient(http, config.ingestUrl()), batches);
            return;
        }
        Runner runner = new Runner(config, tokens, new IngestClient(http, config.ingestUrl()), batches, MAX_IN_FLIGHT);
        FreshnessScraper scraper = new FreshnessScraper(http, config.freshnessUrl());

        System.out.printf(
                "loadgen: target %d events/s for %d s, batch %d, %d sessions, late fraction %.2f -> %s%n",
                config.rate(),
                config.durationSeconds(),
                config.batch(),
                config.sessions(),
                config.lateFraction(),
                config.ingestUrl());

        Optional<BucketHistogram> before = scraper.scrape();
        RunSummary summary;
        try {
            summary = runner.run();
        } catch (java.io.IOException e) {
            System.err.println("loadgen: could not obtain a token: " + e.getMessage());
            System.exit(1);
            return;
        }
        Optional<BucketHistogram> after = scraper.scrape();

        System.out.print(ReportFormatter.format(withFreshness(summary, before, after)));
    }

    private static void runRamp(
            RampPlan plan, LoadGenConfig config, TokenProvider tokens, IngestClient ingest, BatchBuilder batches)
            throws Exception {
        System.out.printf(
                "loadgen: saturation search %d -> %d events/s in steps of %d, %d s per step, batch %d -> %s%n",
                plan.startRate(), plan.ceiling(), plan.step(), plan.stepSeconds(), config.batch(), config.ingestUrl());
        SaturationReport report = RampRunner.run(plan, (rate, seconds) -> {
            LoadGenConfig step = new LoadGenConfig(
                    rate,
                    seconds,
                    config.batch(),
                    config.sessions(),
                    config.lateFraction(),
                    config.ingestUrl(),
                    config.keycloakUrl(),
                    config.freshnessUrl());
            return new Runner(step, tokens, ingest, batches, MAX_IN_FLIGHT, false).run();
        });
        System.out.print(RampReportFormatter.format(report));
    }

    private static RunSummary withFreshness(
            RunSummary summary, Optional<BucketHistogram> before, Optional<BucketHistogram> after) {
        if (after.isEmpty()) {
            return summary.withFreshness(OptionalDouble.empty(), "endpoint unreachable or metric absent");
        }
        Optional<BucketHistogram> runOnly = before.flatMap(b -> after.get().minus(b));
        BucketHistogram histogram = runOnly.orElse(after.get());
        String note = runOnly.isPresent()
                ? "observations made during this run"
                : "since sink-writer started, no usable pre-run scrape";
        OptionalDouble p99 = histogram.quantile(FRESHNESS_QUANTILE);
        return p99.isPresent()
                ? summary.withFreshness(p99, note)
                : summary.withFreshness(OptionalDouble.empty(), "histogram has no observations");
    }
}
