/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import java.time.Instant;

final class ObservationFixtures {

    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private ObservationFixtures() {}

    /** A canonical UUID, because that is what the platform accepts as an event id. */
    static String id(int n) {
        return "00000000-0000-0000-0000-%012d".formatted(n);
    }

    static Observation event(String eventId, String sessionId) {
        return new Observation(eventId, "streamflix", sessionId, "title-1", "us-east-1", false, NOW);
    }

    static Observation error(String eventId, String sessionId) {
        return new Observation(eventId, "streamflix", sessionId, "title-1", "us-east-1", true, NOW);
    }
}
