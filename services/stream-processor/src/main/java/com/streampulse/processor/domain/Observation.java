/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.processor.domain;

import java.time.Instant;

/**
 * The part of a telemetry event this service reasons about, as plain Java.
 *
 * <p>Deliberately not the Avro type: the domain stays free of the wire format, so the rules
 * below run in a unit test with no Avro on the classpath, and a schema change touches one
 * mapper rather than every rule. Fields are raw (unvalidated) because rejecting an event
 * needs a representation of the event being rejected.
 *
 * @param titleId nullable; a session can start before a title is chosen
 * @param error whether the event is of the error type; the only type distinction a metric needs
 */
public record Observation(
        String eventId,
        String tenantId,
        String sessionId,
        String titleId,
        String region,
        boolean error,
        Instant occurredAt) {}
