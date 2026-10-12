/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

public record LoadGenConfig(
        long rate,
        long durationSeconds,
        int batch,
        int sessions,
        double lateFraction,
        String ingestUrl,
        String keycloakUrl,
        String freshnessUrl) {

    public static final int MAX_BATCH = 500;
    public static final long DEFAULT_RATE = 1000;
    public static final long DEFAULT_DURATION_SECONDS = 30;
    public static final int DEFAULT_BATCH = 100;
    public static final int DEFAULT_SESSIONS = 1000;
    public static final String DEFAULT_INGEST_URL = "http://localhost:8080";
    public static final String DEFAULT_KEYCLOAK_URL = "http://localhost:8081";
    public static final String DEFAULT_FRESHNESS_URL = "http://localhost:8084/actuator/prometheus";
}
