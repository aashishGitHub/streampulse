/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

import java.time.Duration;

public final class Pacing {

    private Pacing() {}

    public static long intervalNanos(long eventsPerSecond, int batchSize) {
        if (eventsPerSecond < 1 || batchSize < 1) {
            throw new IllegalArgumentException("rate and batch size must be positive");
        }
        return Duration.ofSeconds(1).toNanos() * batchSize / eventsPerSecond;
    }

    public static long maxBatches(long eventsPerSecond, long durationSeconds, int batchSize) {
        long events = eventsPerSecond * durationSeconds;
        return (events + batchSize - 1) / batchSize;
    }

    /** A pause shifts the schedule instead of letting it burst to catch up afterwards. */
    public static long nextDue(long scheduledNanos, long pausedUntilNanos) {
        return Math.max(scheduledNanos, pausedUntilNanos);
    }

    /** Only the delta-seconds form of Retry-After is honoured; the HTTP-date form falls back. */
    public static long retryAfterNanos(String headerValue, Duration fallback, Duration cap) {
        long seconds = -1;
        if (headerValue != null) {
            try {
                seconds = Long.parseLong(headerValue.trim());
            } catch (NumberFormatException ignored) {
                seconds = -1;
            }
        }
        Duration wait = seconds < 0 ? fallback : Duration.ofSeconds(seconds);
        return (wait.compareTo(cap) > 0 ? cap : wait).toNanos();
    }
}
