/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.loadgen;

public record LoadEvent(
        String eventId,
        String sessionId,
        String titleId,
        String deviceType,
        String region,
        String occurredAt,
        String type) {}
