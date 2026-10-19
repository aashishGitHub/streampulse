/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ArgParser {

    private static final Set<String> KNOWN = Set.of(
            "--rate",
            "--duration",
            "--batch",
            "--sessions",
            "--late-fraction",
            "--url",
            "--keycloak",
            "--freshness-url",
            "--ramp-to",
            "--ramp-step",
            "--step-duration");

    public static final String USAGE =
            """
            usage: loadgen [options]
              --rate N            target events per second            (default 1000)
              --duration S        seconds to generate load            (default 30)
              --batch N           events per request, 1-500           (default 100)
              --sessions N        distinct session ids                (default 1000)
              --late-fraction F   0..1, share of events 1-4 s old     (default 0)
              --url U             ingest base URL                     (default http://localhost:8080)
              --keycloak U        Keycloak base URL                   (default http://localhost:8081)
              --freshness-url U   sink Prometheus endpoint            (default http://localhost:8084/actuator/prometheus)
            saturation search (replaces the single run; --rate is the first step, ignores Retry-After):
              --ramp-to N         highest events/s to try; setting it turns the search on
              --ramp-step N       events/s added per step             (default 500)
              --step-duration S   seconds per step                    (default 20)
            env: LOADGEN_CLIENT_SECRET (required) is the ingest-gateway client secret.
            """;

    private ArgParser() {}

    public static LoadGenConfig parse(String[] args) {
        return config(values(args));
    }

    public static Optional<RampPlan> parseRamp(String[] args) {
        Map<String, String> values = values(args);
        if (!values.containsKey("--ramp-to")) {
            return Optional.empty();
        }
        return Optional.of(new RampPlan(
                positiveLong(values, "--rate", LoadGenConfig.DEFAULT_RATE),
                positiveLong(values, "--ramp-step", RampPlan.DEFAULT_STEP),
                positiveLong(values, "--ramp-to", 0),
                positiveLong(values, "--step-duration", RampPlan.DEFAULT_STEP_SECONDS)));
    }

    private static Map<String, String> values(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new ArgumentException("unexpected argument '" + arg + "'");
            }
            String flag = arg;
            String value;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                flag = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            } else if (i + 1 < args.length) {
                value = args[++i];
            } else {
                throw new ArgumentException(arg + " needs a value");
            }
            if (!KNOWN.contains(flag)) {
                throw new ArgumentException("unknown option " + flag);
            }
            values.put(flag, value);
        }
        return values;
    }

    private static LoadGenConfig config(Map<String, String> values) {
        long rate = positiveLong(values, "--rate", LoadGenConfig.DEFAULT_RATE);
        long duration = positiveLong(values, "--duration", LoadGenConfig.DEFAULT_DURATION_SECONDS);
        long batch = positiveLong(values, "--batch", LoadGenConfig.DEFAULT_BATCH);
        if (batch > LoadGenConfig.MAX_BATCH) {
            throw new ArgumentException("--batch must be between 1 and " + LoadGenConfig.MAX_BATCH + ", got " + batch);
        }
        long sessions = positiveLong(values, "--sessions", LoadGenConfig.DEFAULT_SESSIONS);
        if (sessions > Integer.MAX_VALUE) {
            throw new ArgumentException("--sessions is too large: " + sessions);
        }

        return new LoadGenConfig(
                rate,
                duration,
                (int) batch,
                (int) sessions,
                fraction(values, "--late-fraction"),
                url(values, "--url", LoadGenConfig.DEFAULT_INGEST_URL),
                url(values, "--keycloak", LoadGenConfig.DEFAULT_KEYCLOAK_URL),
                url(values, "--freshness-url", LoadGenConfig.DEFAULT_FRESHNESS_URL));
    }

    private static long positiveLong(Map<String, String> values, String flag, long defaultValue) {
        String raw = values.get(flag);
        if (raw == null) {
            return defaultValue;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ArgumentException(flag + " must be a whole number, got '" + raw + "'");
        }
        if (parsed < 1) {
            throw new ArgumentException(flag + " must be at least 1, got " + parsed);
        }
        return parsed;
    }

    private static double fraction(Map<String, String> values, String flag) {
        String raw = values.get(flag);
        if (raw == null) {
            return 0;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ArgumentException(flag + " must be a number, got '" + raw + "'");
        }
        if (!(parsed >= 0 && parsed <= 1)) {
            throw new ArgumentException(flag + " must be between 0 and 1, got '" + raw + "'");
        }
        return parsed;
    }

    private static String url(Map<String, String> values, String flag, String defaultValue) {
        String raw = values.getOrDefault(flag, defaultValue).trim();
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            throw new ArgumentException(flag + " must start with http:// or https://, got '" + raw + "'");
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }
}
